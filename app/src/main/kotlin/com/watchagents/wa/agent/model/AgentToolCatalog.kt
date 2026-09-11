package com.watchagents.wa.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    /**
     * Watch4 精简版：装配手表可执行工具——本地记忆(memory_*)、本地技能(skills_*)、
     * 轻量联网检索(web_search / fetch_url)、本地沙盒文件与媒体读取(files_* / media_*)。
     * 不暴露设备直达/手势/无障碍/终端/GitHub 安装等 root 或全屏工具。
     */
    fun build(
        terminalTools: Boolean = false,
        browserTools: Boolean = false,
        deviceDirectTools: Boolean = false,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        localFileTools: Boolean = false,
    ): JSONArray =
        JSONArray().also { tools ->
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = false,
                githubInstall = false,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools)
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools)
            }
            if (localFileTools) {
                AgentLocalFileToolCatalog.appendTo(tools)
            }
        }
}

/** Watch 联网检索工具（HTTP 轻量实现，无 WebView/root；多引擎自动回退）。 */
internal object AgentWebToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "web_search",
                    description = "按关键词联网搜索（自动多引擎：Bing → DuckDuckGo，取最新相关结果），返回若干条结果的标题/摘要/来源链接/引擎。适合查新闻、实时信息、资料、事实核对。一次结果不够时换个更具体的关键词再搜，最多可连搜数轮；中文问题优先用中文关键词，再视结果补充英文关键词。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "query",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 300)
                                        .put("description", "搜索关键词，中文/英文皆可；复杂问题请拆成单一子问题后逐次搜索"),
                                )
                                .put(
                                    "maxResults",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 10)
                                        .put("description", "期望返回结果条数，默认 5"),
                                ),
                        )
                        .put("required", JSONArray().put("query")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "fetch_url",
                    description = "抓取指定 URL 网页的正文纯文本（截断到 maxChars）。当 web_search 摘要不足以回答时，用它读取来源原文；优先抓取权威站点原文而不是转载页。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "url",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 2_000)
                                        .put("description", "完整 http(s) URL"),
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 20_000)
                                        .put("description", "最多返回字符数，默认 6000"),
                                ),
                        )
                        .put("required", JSONArray().put("url")),
                ),
            )
    }
}

/** Watch 本地文件与媒体读取工具（沙盒工作区 + MediaStore 照片/文档，权限守卫在 Executor）。 */
internal object AgentLocalFileToolCatalog {
    fun appendTo(tools: JSONArray) {
        tools
            .put(
                AgentToolSchema.function(
                    name = "files_list",
                    description = "列出手表本地工作区（App 私有沙盒）中的文件与子目录。用户要求“打开我存的文件/看看有哪些文件/整理文件”时先用它定位。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("description", "可选：相对目录路径，省略则列出根目录"),
                                )
                                .put(
                                    "maxItems",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 200)
                                        .put("description", "返回条目上限，默认 100"),
                                ),
                        ),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "files_read",
                    description = "读取工作区文本文件内容（txt/md/json/csv/log 等 UTF-8 文本）。修改或续写文件前先读一遍再决定改动。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "相对路径，如 notes/今日.md"),
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 60_000)
                                        .put("description", "最多返回字符数，默认 20000；超长时返回头部并提示 totalChars"),
                                ),
                        )
                        .put("required", JSONArray().put("path")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "files_write",
                    description = "创建/覆盖/追加写入工作区文本文件（自动创建父目录）。用于保存笔记、清单、草稿、生成的文档、runbook 等用户要持久化的内容。写入后告知用户保存路径。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "相对路径，如 notes/清单.md"),
                                )
                                .put(
                                    "content",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 200_000)
                                        .put("description", "写入内容"),
                                )
                                .put(
                                    "mode",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("overwrite").put("append"))
                                        .put("description", "overwrite=覆盖全文（默认）；append=追加到文件末尾"),
                                ),
                        )
                        .put("required", JSONArray().put("path").put("content")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "files_delete",
                    description = "删除工作区中的一个文件（不删除目录）。删除前先与用户确认（用户明确要求删除时才调用）。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "path",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 500)
                                        .put("description", "相对路径"),
                                ),
                        )
                        .put("required", JSONArray().put("path")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "media_list",
                    description = "列出手表相册照片或文档文件（名称/时间/大小/类型），按时间倒序。用户提到“我照片里/最近拍的文件、收件箱/下载的文档”等本机内容时先用它检索。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "kind",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("images").put("documents"))
                                        .put("description", "images=相册照片；documents=文档（文本类文件）"),
                                )
                                .put(
                                    "keyword",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 100)
                                        .put("description", "可选文件名关键词过滤"),
                                )
                                .put(
                                    "maxItems",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 1)
                                        .put("maximum", 60)
                                        .put("description", "返回条目上限，默认 20"),
                                ),
                        )
                        .put("required", JSONArray().put("kind")),
                ),
            )
            .put(
                AgentToolSchema.function(
                    name = "media_read",
                    description = "读取一条已列出的媒体条目：文本类文档返回正文文字；照片返回元数据（无法以纯文本模型“看图”）。必须先 media_list 拿到 id。",
                    parameters = JSONObject()
                        .put("type", "object")
                        .put(
                            "properties",
                            JSONObject()
                                .put(
                                    "kind",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("enum", JSONArray().put("images").put("documents"))
                                        .put("description", "与 media_list 的 kind 一致"),
                                )
                                .put(
                                    "id",
                                    JSONObject()
                                        .put("type", "string")
                                        .put("maxLength", 80)
                                        .put("description", "media_list 返回的条目 id"),
                                )
                                .put(
                                    "maxChars",
                                    JSONObject()
                                        .put("type", "integer")
                                        .put("minimum", 512)
                                        .put("maximum", 60_000)
                                        .put("description", "最多返回字符数，默认 20000"),
                                ),
                        )
                        .put("required", JSONArray().put("kind").put("id")),
                ),
            )
    }
}
