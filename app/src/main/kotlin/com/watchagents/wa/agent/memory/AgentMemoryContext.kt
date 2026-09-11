package com.watchagents.wa.agent.memory

import com.watchagents.wa.data.repository.AgentMemorySnapshot

internal data class AgentMemoryContext(
    val enabled: Boolean,
    val revision: String,
    val byteSize: Int,
    val coreContent: String,
    val coreTruncated: Boolean,
    val headingIndex: String,
    val coreBudgetChars: Int,
    val recallContent: String = "",
    val recallTruncated: Boolean = false,
    val recallBudgetChars: Int = 0,
) {
    companion object {
        val DISABLED = AgentMemoryContext(
            enabled = false,
            revision = "",
            byteSize = 0,
            coreContent = "",
            coreTruncated = false,
            headingIndex = "",
            coreBudgetChars = 0,
        )
    }
}

internal object AgentMemoryContextBuilder {
    fun empty(contextWindow: Int?): AgentMemoryContext = build(
        snapshot = AgentMemorySnapshot(
            content = "",
            revision = EMPTY_SHA256,
            byteSize = 0,
            lineCount = 0,
        ),
        contextWindow = contextWindow,
    )

    fun build(
        snapshot: AgentMemorySnapshot,
        contextWindow: Int?,
        query: String? = null,
    ): AgentMemoryContext {
        val coreBudget = coreBudgetChars(contextWindow)
        val recallBudget = recallBudgetChars(contextWindow)
        val core = extractCore(snapshot.content)
        val headings = snapshot.content.lineSequence()
            .filter { line -> HEADING.matches(line.trimEnd()) }
            .joinToString("\n")
            .take(MAX_HEADING_INDEX_CHARS)
        val recall = buildRecall(snapshot.content, query, recallBudget)
        return AgentMemoryContext(
            enabled = true,
            revision = snapshot.revision,
            byteSize = snapshot.byteSize,
            coreContent = core.take(coreBudget),
            coreTruncated = core.length > coreBudget,
            headingIndex = headings,
            coreBudgetChars = coreBudget,
            recallContent = recall.content,
            recallTruncated = recall.truncated,
            recallBudgetChars = recallBudget,
        )
    }

    fun coreBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / CONTEXT_WINDOW_DIVISOR)
            .coerceIn(MIN_CORE_CHARS, MAX_CORE_CHARS)
    }

    fun recallBudgetChars(contextWindow: Int?): Int {
        val resolvedWindow = contextWindow?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW
        return (resolvedWindow / RECALL_WINDOW_DIVISOR)
            .coerceIn(MIN_RECALL_CHARS, MAX_RECALL_CHARS)
    }

    private fun buildRecall(
        content: String,
        query: String?,
        budget: Int,
    ): RecallResult {
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isBlank() || content.isBlank() || budget <= 0) {
            return RecallResult("", false)
        }
        val hits = com.watchagents.wa.data.repository.AgentMemorySemanticSearch.search(
            content = content,
            query = trimmed,
            maxHits = MAX_RECALL_HITS,
            maxChars = budget,
        )
        if (hits.isEmpty()) return RecallResult("", false)
        val body = hits.joinToString("\n\n") { hit ->
            "【L${hit.startLine}-${hit.endLine}】\n${hit.text}"
        }
        val truncated = body.length > budget
        return RecallResult(body.take(budget), truncated)
    }

    private data class RecallResult(
        val content: String,
        val truncated: Boolean,
    )

    private fun extractCore(content: String): String {
        if (content.isEmpty()) return ""
        val lines = content.split('\n')
        val start = lines.indexOfFirst { it.trim() == CORE_HEADING }
        if (start < 0) return ""
        val end = ((start + 1) until lines.size)
            .firstOrNull { index -> lines[index].startsWith("# ") }
            ?: lines.size
        return lines.subList(start, end).joinToString("\n")
    }

    private val HEADING = Regex("^#{1,2}\\s+.+$")
    private const val CORE_HEADING = "# 核心记忆"
    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val CONTEXT_WINDOW_DIVISOR = 16
    private const val RECALL_WINDOW_DIVISOR = 24
    private const val MIN_CORE_CHARS = 4_000
    private const val MAX_CORE_CHARS = 32_000
    private const val MIN_RECALL_CHARS = 2_000
    private const val MAX_RECALL_CHARS = 12_000
    private const val MAX_RECALL_HITS = 5
    private const val MAX_HEADING_INDEX_CHARS = 4_000
    private const val EMPTY_SHA256 =
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
