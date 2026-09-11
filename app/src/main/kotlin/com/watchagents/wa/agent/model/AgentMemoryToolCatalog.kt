package com.watchagents.wa.agent.model

import com.watchagents.wa.data.repository.AgentMemoryStore
import org.json.JSONArray
import org.json.JSONObject

/** 声明持久记忆的有界读取、语义检索与原子局部更新工具。 */
internal object AgentMemoryToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "memory_get",
                    description = "Read persistent cross-conversation memory from MEMORY.md. Core memory and the heading index are already provided at run start, so call this only to inspect details, answer what is remembered, or refresh after a conflict. Use query for just-in-time keyword retrieval instead of reading the whole file; for semantic recall where wording may differ, prefer memory_search.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "Optional case-insensitive keyword to search for in the full memory file."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "1-based first line for paged reading when query is omitted; default 1."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", AgentMemoryStore.MIN_READ_CHARS)
                                        .put("maximum", AgentMemoryStore.MAX_READ_CHARS)
                                        .put("description", "Maximum returned characters; default 12000, maximum 32000."),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_search",
                    description = "Semantic recall over MEMORY.md using local CJK-aware retrieval: matches meaning and paraphrases, not only exact keywords. Returns ranked snippets with line ranges and scores. Use when the user asks what you remember, when a keyword memory_get misses, or when the current task needs relevant past context that the run-start recall may not have covered.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 1)
                                        .put("maxLength", 500)
                                        .put("description", "What to search for; natural language works better than bare keywords."),
                                )
                                .put(
                                    "limit",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 12)
                                        .put("description", "Max snippets to return; default 6."),
                                )
                                .put(
                                    "max_chars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 12_000)
                                        .put("description", "Maximum total characters of returned snippets; default 6000."),
                                ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "memory_write",
                    description = "Atomically update persistent MEMORY.md. Store only durable cross-conversation facts, preferences, relationships, and ongoing project context; never store secrets, credentials, verification codes, or transient requests. Keep '# 核心记忆' concise, correct stale facts, and prefer replacing an existing section over blindly appending duplicates. Use the revision supplied in the run-start memory context or the latest memory_get result.",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put(
                                            "enum",
                                            JSONArray()
                                                .put("replace_range")
                                                .put("append")
                                                .put("clear"),
                                        )
                                        .put("description", "replace_range edits inclusive 1-based lines; append adds a distinct section; clear removes all memory."),
                                )
                                .put(
                                    "revision",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("minLength", 64)
                                        .put("maxLength", 64)
                                        .put("description", "Exact SHA-256 revision from the run-start memory context or latest memory_get result."),
                                )
                                .put(
                                    "start_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based first line."),
                                )
                                .put(
                                    "end_line",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("description", "Required for replace_range; inclusive 1-based last line."),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", AgentMemoryStore.MAX_WRITE_CONTENT_CHARS)
                                        .put("description", "Replacement or appended Markdown, at most 3500 characters. Empty content deletes a replace_range."),
                                ),
                        )
                        .put("required", JSONArray().put("mode").put("revision")),
                ),
            )
    }
}
