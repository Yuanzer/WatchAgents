package com.watchagents.wa.agent.tool

import android.content.Context
import com.watchagents.wa.agent.model.AgentHttpClient
import com.watchagents.wa.agent.model.AgentModelClient
import com.watchagents.wa.agent.skill.SkillCompatibilityChecker
import com.watchagents.wa.agent.skill.SkillLoader
import com.watchagents.wa.agent.skill.SkillResourceReader
import com.watchagents.wa.agent.skill.SkillRuntime
import com.watchagents.wa.data.repository.AgentMemoryException
import com.watchagents.wa.data.repository.AgentMemoryMutation
import com.watchagents.wa.data.repository.AgentMemoryRepository
import com.watchagents.wa.data.repository.AgentMemoryWriteResult
import java.net.URLEncoder
import java.util.Locale
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch4 精简版工具执行器（无 root / 无系统级能力）。
 *
 * 只执行手表上真正可用的本地工具：
 * - 记忆：memory_get / memory_search / memory_write（本地 MEMORY.md + BM25）
 * - 技能：skills_list / skills_read / skills_read_resource（本地 filesDir/skills）
 * - 本地文件：files_list / files_read / files_write / files_delete（filesDir/workspace 沙盒）
 * - 相册/文档：media_list / media_read（MediaStore，运行时权限守卫）
 * - 联网检索：web_search（Bing→DuckDuckGo 多引擎回退）/ fetch_url
 *
 * 其余工具一律返回 UNKNOWN_TOOL，不暴露设备/终端/无障碍等 root 能力。
 */
internal class WatchToolExecutor(
    context: Context,
) : AgentModelClient.ToolExecutor {

    private val appContext = context.applicationContext
    private val indexService by lazy { SkillRuntime.createIndexService(appContext) }
    private val loader by lazy { SkillLoader(SkillRuntime.skillsRoot(appContext)) }
    private val resourceReader by lazy {
        SkillResourceReader(SkillRuntime.skillsRoot(appContext))
    }
    private val localFiles by lazy { WatchLocalFileTools(appContext) }

    override fun execute(toolCall: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val content = try {
            val args = JSONObject(toolCall.argumentsJson.ifBlank { "{}" })
            when (toolCall.name) {
                "memory_get" -> memoryGet(args)
                "memory_search" -> memorySearch(args)
                "memory_write" -> memoryWrite(args)
                "skills_list" -> skillsList(args)
                "skills_read" -> skillsRead(args)
                "skills_read_resource" -> skillsReadResource(args)
                "files_list" -> localFiles.list(
                    path = args.optString("path"),
                    maxItems = args.optInt("maxItems", 100).coerceIn(1, 200),
                ).toString()
                "files_read" -> localFiles.read(
                    path = args.optString("path"),
                    maxChars = args.optInt("maxChars", 20_000).coerceIn(512, 60_000),
                ).toString()
                "files_write" -> localFiles.write(
                    path = args.optString("path"),
                    content = args.optString("content"),
                    mode = args.optString("mode", "overwrite"),
                ).toString()
                "files_delete" -> localFiles.delete(args.optString("path")).toString()
                "media_list" -> localFiles.mediaList(
                    kind = args.optString("kind"),
                    keyword = args.optString("keyword"),
                    maxItems = args.optInt("maxItems", 20).coerceIn(1, 60),
                ).toString()
                "media_read" -> localFiles.mediaRead(
                    kind = args.optString("kind"),
                    id = args.optString("id"),
                    maxChars = args.optInt("maxChars", 20_000).coerceIn(512, 60_000),
                ).toString()
                "web_search" -> webSearch(args)
                "fetch_url" -> fetchUrl(args)
                else -> errorResult(
                    "UNKNOWN_TOOL",
                    "当前设备不支持工具：${toolCall.name}",
                )
            }
        } catch (throwable: Throwable) {
            errorResult(
                "TOOL_ERROR",
                throwable.message ?: throwable.javaClass.simpleName,
            )
        }
        return AgentModelClient.ToolResult(content = content)
    }

    // ==================== Memory ====================

    private fun memoryGet(args: JSONObject): String = try {
        val result = AgentMemoryRepository.read(
            query = args.optString("query").takeIf(String::isNotBlank),
            startLine = args.optInt("start_line", 1),
            maxChars = args.optInt("max_chars", 12_000),
        )
        JSONObject()
            .put("ok", true)
            .put("revision", result.snapshot.revision)
            .put("bytes", result.snapshot.byteSize)
            .put("line_count", result.snapshot.lineCount)
            .put("start_line", result.startLine ?: JSONObject.NULL)
            .put("end_line", result.endLine ?: JSONObject.NULL)
            .put("matched_lines", result.matchedLines)
            .put("has_more", result.hasMore)
            .put("content", result.content)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆读取失败")
    }

    private fun memorySearch(args: JSONObject): String = try {
        val query = args.getString("query").trim()
        if (query.isBlank()) return errorResult("INVALID_ARGUMENT", "query 不能为空")
        val hits = AgentMemoryRepository.search(
            query = query,
            maxHits = args.optInt("limit", 6).coerceIn(1, 12),
            maxChars = args.optInt("max_chars", 6_000).coerceIn(512, 12_000),
        )
        if (hits.isEmpty()) {
            return JSONObject()
                .put("ok", true)
                .put("query", query)
                .put("count", 0)
                .put("hits", JSONArray())
                .put("hint", "没有找到语义相关的记忆片段；可尝试 memory_get 查看完整记忆或换一种表述")
                .toString()
        }
        val items = JSONArray()
        hits.forEachIndexed { index, hit ->
            items.put(
                JSONObject()
                    .put("rank", index + 1)
                    .put("start_line", hit.startLine)
                    .put("end_line", hit.endLine)
                    .put("score", hit.score)
                    .put("content", hit.content),
            )
        }
        JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", hits.size)
            .put("hits", items)
            .toString()
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆检索失败")
    }

    private fun memoryWrite(args: JSONObject): String = try {
        val revision = args.getString("revision")
        val mutation = when (args.getString("mode")) {
            "replace_range" -> AgentMemoryMutation.ReplaceRange(
                revision = revision,
                startLine = args.getInt("start_line"),
                endLine = args.getInt("end_line"),
                content = args.getString("content"),
            )
            "append" -> AgentMemoryMutation.Append(
                revision = revision,
                content = args.getString("content"),
            )
            "clear" -> AgentMemoryMutation.Clear(revision)
            else -> error("不支持的记忆写入模式")
        }
        when (val result = AgentMemoryRepository.mutate(mutation)) {
            is AgentMemoryWriteResult.Success -> JSONObject()
                .put("ok", true)
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
            is AgentMemoryWriteResult.Conflict -> JSONObject()
                .put("ok", false)
                .put("code", "MEMORY_CONFLICT")
                .put("message", "记忆已发生变化，请先调用 memory_get 获取最新内容")
                .put("revision", result.snapshot.revision)
                .put("bytes", result.snapshot.byteSize)
                .put("line_count", result.snapshot.lineCount)
                .toString()
        }
    } catch (failure: AgentMemoryException) {
        errorResult(failure.code, failure.message ?: "记忆写入失败")
    }

    // ==================== Skills ====================

    private fun skillsList(args: JSONObject): String {
        val query = args.optString("query").trim().lowercase()
        val limit = args.optInt("limit", 50).coerceIn(1, 200)
        val entries = indexService.listInstalledSkills()
            .filter { entry -> SkillCompatibilityChecker.evaluate(entry).available }
            .filter { entry ->
                if (query.isBlank()) true
                else listOf(entry.id, entry.name, entry.description, entry.skillFilePath, entry.rootPath)
                    .any { it.lowercase().contains(query) }
            }
            .take(limit)
        val items = JSONArray()
        entries.forEach { entry ->
            val capabilities = JSONArray()
            if (entry.hasScripts) capabilities.put("scripts")
            if (entry.hasReferences) capabilities.put("references")
            if (entry.hasAssets) capabilities.put("assets")
            if (entry.hasEvals) capabilities.put("evals")
            items.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("description", entry.description)
                    .put("enabled", entry.enabled)
                    .put("source", entry.source)
                    .put("rootPath", entry.rootPath)
                    .put("skillFilePath", entry.skillFilePath)
                    .put("capabilities", capabilities),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", entries.size)
            .put("items", items)
            .toString()
    }

    private fun skillsRead(args: JSONObject): String {
        val skillId = args.optString("skillId").trim()
        if (skillId.isBlank()) return errorResult("MISSING_PARAM", "缺少 skillId")
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到 skill：$skillId")
        val compat = SkillCompatibilityChecker.evaluate(entry)
        if (!compat.available) return errorResult("INCOMPATIBLE", compat.reason ?: "当前环境不可用")
        val resolved = loader.load(entry, "agent 主动读取 skill")
            ?: return errorResult("READ_FAILED", "读取 SKILL.md 失败：${entry.skillFilePath}")
        val body = if (resolved.bodyMarkdown.length <= maxChars) {
            resolved.bodyMarkdown
        } else {
            resolved.bodyMarkdown.take(maxChars) + "\n..."
        }
        val references = JSONArray()
        resolved.loadedReferences.forEach { references.put(it) }
        val frontmatter = JSONObject()
        resolved.frontmatter.forEach { (k, v) -> frontmatter.put(k, v) }
        return JSONObject()
            .put("ok", true)
            .put("id", entry.id)
            .put("name", entry.name)
            .put("description", entry.description)
            .put("rootPath", entry.rootPath)
            .put("skillFilePath", entry.skillFilePath)
            .put("scriptsDir", resolved.scriptsDir ?: JSONObject.NULL)
            .put("assetsDir", resolved.assetsDir ?: JSONObject.NULL)
            .put("references", references)
            .put("frontmatter", frontmatter)
            .put("bodyMarkdown", body)
            .toString()
    }

    private fun skillsReadResource(args: JSONObject): String {
        val skillId = args.getString("skillId").trim()
        val relativePath = args.getString("relativePath").trim()
        val maxChars = args.optInt("maxChars", 16_000).coerceIn(512, 64_000)
        val entry = indexService.findInstalledSkill(skillId)
            ?: return errorResult("NOT_FOUND", "未找到已启用 Skill：$skillId")
        val compatibility = SkillCompatibilityChecker.evaluate(entry)
        if (!compatibility.available) {
            return errorResult(
                "INCOMPATIBLE",
                compatibility.reason ?: "当前环境不可用",
            )
        }
        return when (val result = resourceReader.readText(entry, relativePath)) {
            is com.watchagents.wa.agent.skill.SkillResourceReadResult.Success -> {
                val truncated = result.text.length > maxChars
                val visibleText = if (truncated) {
                    result.text.take(maxChars).let { prefix ->
                        if (prefix.lastOrNull()?.isHighSurrogate() == true) {
                            prefix.dropLast(1)
                        } else {
                            prefix
                        }
                    }
                } else {
                    result.text
                }
                JSONObject()
                    .put("ok", true)
                    .put("skillId", entry.id)
                    .put("relativePath", result.relativePath)
                    .put("text", visibleText)
                    .put("truncated", truncated)
                    .put("totalChars", result.text.length)
                    .toString()
            }
            is com.watchagents.wa.agent.skill.SkillResourceReadResult.Failure -> errorResult(
                code = result.error.code.name,
                message = result.error.message,
            )
        }
    }

    // ==================== 联网检索（HTTP 轻量，无 WebView） ====================

    private fun webSearch(args: JSONObject): String = try {
        val query = args.getString("query").trim()
        if (query.isBlank()) return errorResult("INVALID_ARGUMENT", "query 不能为空")
        val limit = args.optInt("maxResults", 5).coerceIn(1, 10)

        // 多引擎自动回退：Bing RSS 优先（无需 key），不足/失败再补 DuckDuckGo HTML
        val bingHits = runCatching { searchBingRss(query, limit) }.getOrDefault(emptyList())
        var merged = bingHits
        if (merged.size < limit) {
            val ddgHits = runCatching { searchDuckDuckGo(query, limit) }.getOrDefault(emptyList())
            merged = dedupeSearchHits(merged + ddgHits)
        }
        merged = merged.take(limit)
        if (merged.isEmpty()) {
            return JSONObject()
                .put("ok", false)
                .put("code", "NO_RESULTS")
                .put(
                    "message",
                    "多个搜索引擎都没有返回结果：可换更具体的关键词（中文/英文各试一次），" +
                        "或直接 fetch_url 访问已知的相关网址。",
                )
                .toString()
        }
        val items = JSONArray()
        val engines = linkedSetOf<String>()
        merged.forEachIndexed { index, hit ->
            engines += hit.engine
            items.put(
                JSONObject()
                    .put("rank", index + 1)
                    .put("title", stripHtml(hit.title).trim())
                    .put("url", hit.url)
                    .put("snippet", stripHtml(hit.snippet).trim())
                    .put("engine", hit.engine),
            )
        }
        val enginesJson = JSONArray()
        engines.forEach { enginesJson.put(it) }
        JSONObject()
            .put("ok", true)
            .put("query", query)
            .put("count", items.length())
            .put("engines", enginesJson)
            .put(
                "hint",
                "摘要不够时对最相关的 1~2 条用 fetch_url 读原文再作答；涉及时间敏感信息请核对发布时间。" +
                    "若这些结果都不相关，请换关键词再搜（每轮最多建议再搜 1~2 次），不要编造。",
            )
            .put("items", items)
            .toString()
    } catch (throwable: Throwable) {
        errorResult("SEARCH_ERROR", throwable.message ?: "搜索失败")
    }

    private data class SearchHit(
        val title: String,
        val url: String,
        val snippet: String,
        val engine: String,
    )

    private fun dedupeSearchHits(hits: List<SearchHit>): List<SearchHit> {
        val seen = linkedSetOf<String>()
        val out = mutableListOf<SearchHit>()
        hits.forEach { hit ->
            val key = hit.url
                .substringAfter("://", hit.url)
                .trimEnd('/')
                .lowercase(Locale.ROOT)
            if (key.isNotBlank() && seen.add(key)) {
                out += hit
            }
        }
        return out
    }

    /** Bing RSS：无需 API key 的 XML 结果流。 */
    private fun searchBingRss(query: String, limit: Int): List<SearchHit> {
        val url = "https://www.bing.com/search?q=" +
            URLEncoder.encode(query, "UTF-8") + "&format=rss&count=$limit"
        val xml = httpGet(url, maxBytes = 300_000)
            ?: return emptyList()
        return parseRssItems(xml, limit).map { (title, link, snippet) ->
            SearchHit(
                title = decodeEntities(title),
                url = decodeEntities(link),
                snippet = htmlToText(decodeEntities(snippet)).trim().take(500),
                engine = "bing",
            )
        }.filter { it.url.startsWith("http") }
    }

    /** DuckDuckGo HTML：抓取结果页并解析 result__a / result__snippet。 */
    private fun searchDuckDuckGo(query: String, limit: Int): List<SearchHit> {
        val url = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8")
        val html = httpGet(url, maxBytes = 500_000)
            ?: return emptyList()
        val titles = ArrayList<String>()
        val links = ArrayList<String>()
        val snippets = ArrayList<String>()
        // 标题与链接：<a rel="nofollow" class="result__a" href="...">Title</a>
        Regex("""class="result__a" href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .forEach { match ->
                links += decodeEntities(htmlUnescapeRedirect(match.groupValues[1]))
                titles += stripHtml(decodeEntities(match.groupValues[2])).trim()
            }
        // 摘要：<a class="result__snippet" ...>text</a>
        Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(html)
            .forEach { match ->
                snippets += stripHtml(decodeEntities(match.groupValues[1])).trim()
            }
        val size = minOf(links.size, titles.size, limit)
        return (0 until size).mapNotNull { index ->
            val link = links[index]
            if (!link.startsWith("http")) null
            else SearchHit(
                title = titles.getOrElse(index) { "" },
                url = link,
                snippet = snippets.getOrElse(index) { "" }.take(500),
                engine = "duckduckgo",
            )
        }
    }

    /** DDG 反代跳转链解出真实 URL。 */
    private fun htmlUnescapeRedirect(raw: String): String {
        var value = raw.trim()
        if (value.startsWith("//")) value = "https:$value"
        if (value.startsWith("https://duckduckgo.com/l/?uddg=")) {
            val encoded = value.substringAfter("uddg=").substringBefore("&")
            runCatching {
                java.net.URLDecoder.decode(encoded, "UTF-8").let { value = it }
            }
        }
        return value
    }

    private fun fetchUrl(args: JSONObject): String = try {
        val url = args.getString("url").trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return errorResult("INVALID_ARGUMENT", "url 必须以 http(s):// 开头")
        }
        val maxChars = args.optInt("maxChars", 6_000).coerceIn(512, 20_000)
        val bytes = httpGetBytes(url, maxBytes = 800_000)
            ?: return errorResult("NETWORK_ERROR", "抓取网页失败：$url")
        // PDF 二进制保护：不喂给文本解析
        if (bytes.size >= 4 && bytes[0] == 0x25.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x44.toByte() && bytes[3] == 0x46.toByte()
        ) {
            return errorResult(
                "UNSUPPORTED_FORMAT",
                "该 URL 是 PDF 文档，当前无法直接解析 PDF 正文：可让 WA 换关键词 web_search 查找该文档的摘要信息。",
            )
        }
        val text = htmlToText(String(bytes, Charsets.UTF_8))
        val truncated = text.length > maxChars
        val visible = if (truncated) text.take(maxChars) else text
        JSONObject()
            .put("ok", true)
            .put("url", url)
            .put("text", visible)
            .put("truncated", truncated)
            .put("totalChars", text.length)
            .toString()
    } catch (throwable: Throwable) {
        errorResult("FETCH_ERROR", throwable.message ?: "抓取失败")
    }

    private fun httpGet(url: String, maxBytes: Int): String? =
        httpGetBytes(url, maxBytes)?.let { bytes ->
            runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()
        }

    private fun httpGetBytes(url: String, maxBytes: Int): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/120.0 Mobile Safari/537.36 WA/4.0",
            )
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .get()
            .build()
        AgentHttpClient.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val bytes = body.bytes()
            if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
        }
    }.getOrNull()

    /** 极简 RSS <item> 解析：title/link/description。 */
    private fun parseRssItems(xml: String, limit: Int): List<Triple<String, String, String>> {
        val items = mutableListOf<Triple<String, String, String>>()
        var cursor = 0
        while (items.size < limit) {
            val itemStart = xml.indexOf("<item", cursor)
            if (itemStart < 0) break
            val itemEnd = xml.indexOf("</item>", itemStart)
            if (itemEnd < 0) break
            val item = xml.substring(itemStart, itemEnd)
            items.add(
                Triple(
                    extractTag(item, "title"),
                    extractTag(item, "link"),
                    extractTag(item, "description"),
                )
            )
            cursor = itemEnd + 7
        }
        return items
    }

    private fun extractTag(xml: String, tag: String): String {
        val start = xml.indexOf("<$tag>")
        if (start < 0) return ""
        val valueStart = start + tag.length + 2
        val end = xml.indexOf("</$tag>", valueStart)
        if (end < 0) return ""
        var value = xml.substring(valueStart, end).trim()
        // RSS 常以 CDATA 包裹正文
        if (value.startsWith("<![CDATA[") && value.endsWith("]]>")) {
            value = value.removePrefix("<![CDATA[").removeSuffix("]]>").trim()
        }
        return value
    }

    private fun stripHtml(raw: String): String = htmlToText(raw)

    /** 基础 HTML → 纯文本（去脚本/样式/标签、解实体、压缩空行）。 */
    private fun htmlToText(html: String): String {
        val noScripts = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        val withoutTags = noScripts.replace(Regex("(?s)<[^>]+>"), " ")
        val decoded = decodeEntities(withoutTags)
        return decoded
            .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
            .replace(Regex("\\n\\s*\\n+"), "\n")
            .trim()
    }

    private fun decodeEntities(input: String): String {
        var out = input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
        // 数字实体（常见中文网页）
        out = Regex("&#(\\d+);").replace(out) { match ->
            match.groupValues[1].toIntOrNull()
                ?.let { code -> runCatching { String(Character.toChars(code)) }.getOrNull() }
                ?: match.value
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)
                ?.let { code -> runCatching { String(Character.toChars(code)) }.getOrNull() }
                ?: match.value
        }
        return out
    }

    private fun errorResult(code: String, message: String): String =
        JSONObject()
            .put("ok", false)
            .put("code", code)
            .put("message", message)
            .toString()
}
