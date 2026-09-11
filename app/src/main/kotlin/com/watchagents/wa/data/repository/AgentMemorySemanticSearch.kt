package com.watchagents.wa.data.repository

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 本机轻量语义检索：对 MEMORY.md 做章节分块，用 CJK 感知的 n-gram 分词 + BM25 排序，
 * 让查询在措辞不同的情况下也能召回语义相近的记忆片段（对应 OpenClaw memory_search 的
 * 本地 BM25 路径，无需端侧 embedding 模型）。
 *
 * 分块规则：按 Markdown 标题（# ~ ###）切分章节；没有标题时按空行分段；段落过长时再
 * 按行窗口拆分，保证每块大小有界。
 */
internal object AgentMemorySemanticSearch {

    internal data class MemoryChunk(
        val startLine: Int,
        val endLine: Int,
        val heading: String?,
        val text: String,
    )

    internal data class MemoryHit(
        val startLine: Int,
        val endLine: Int,
        val score: Double,
        val text: String,
    )

    /** 对单条文本分词：CJK 字符产出 bigram + 单字，拉丁/数字产出小写词元。 */
    internal fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>(16)
        var cjkRun = StringBuilder()
        var latinRun = StringBuilder()
        fun flushCjk() {
            if (cjkRun.isEmpty()) return
            val run = cjkRun.toString()
            if (run.length == 1) {
                tokens += run
            } else {
                for (i in 0 until run.length - 1) {
                    tokens += run.substring(i, i + 2)
                }
                // 单字词元保留，便于短查询与专名命中
                for (i in run.indices) {
                    tokens += run[i].toString()
                }
            }
            cjkRun = StringBuilder()
        }
        fun flushLatin() {
            if (latinRun.isEmpty()) return
            val word = latinRun.toString().lowercase(Locale.ROOT)
            tokens += word
            latinRun = StringBuilder()
        }
        for (char in text) {
            when {
                char.isCjk() -> {
                    flushLatin()
                    cjkRun.append(char)
                }
                char.isLetterOrDigit() -> {
                    flushCjk()
                    latinRun.append(char)
                }
                else -> {
                    flushCjk()
                    flushLatin()
                }
            }
        }
        flushCjk()
        flushLatin()
        return tokens
    }

    private fun Char.isCjk(): Boolean {
        val code = code
        return code in 0x4E00..0x9FFF ||
            code in 0x3400..0x4DBF ||
            code in 0xF900..0xFAFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    /** 把完整记忆正文切成有行号范围的章节块。 */
    internal fun chunk(content: String): List<MemoryChunk> {
        if (content.isEmpty()) return emptyList()
        val lines = content.split('\n')
        val chunks = ArrayList<MemoryChunk>(16)
        var start = 0
        while (start < lines.size) {
            val headingLine = lines[start].trim()
            if (isHeading(headingLine)) {
                var end = start + 1
                while (end < lines.size && !isHeading(lines[end].trim())) end++
                chunks += MemoryChunk(
                    startLine = start + 1,
                    endLine = end,
                    heading = headingLine,
                    text = lines.subList(start, end).joinToString("\n").trim(),
                )
                start = end
            } else {
                // 无标题区域：按空行分段，段落内再按窗口拆分
                var end = start + 1
                while (end < lines.size && lines[end].isNotBlank() && !isHeading(lines[end].trim())) end++
                splitParagraph(lines, start, end) { chunkStart, chunkEnd, text ->
                    chunks += MemoryChunk(
                        startLine = chunkStart + 1,
                        endLine = chunkEnd + 1,
                        heading = null,
                        text = text,
                    )
                }
                start = end
            }
        }
        return chunks
    }

    private fun isHeading(line: String): Boolean =
        HEADING.matches(line) && line.trimEnd().length <= 80

    private fun splitParagraph(
        lines: List<String>,
        start: Int,
        end: Int,
        emit: (Int, Int, String) -> Unit,
    ) {
        val total = (end - start).coerceAtLeast(1)
        if (total <= MAX_PARAGRAPH_LINES) {
            emit(start, end, lines.subList(start, end).joinToString("\n").trim())
            return
        }
        var cursor = start
        while (cursor < end) {
            val windowEnd = (cursor + MAX_PARAGRAPH_LINES).coerceAtMost(end)
            emit(cursor, windowEnd, lines.subList(cursor, windowEnd).joinToString("\n").trim())
            cursor = windowEnd
        }
    }

    /**
     * 语义检索：返回按相关度排序的命中片段。
     *
     * @param maxHits 最多返回片段数
     * @param maxChars 全部片段文本的字符预算（含行号标记）
     */
    internal fun search(
        content: String,
        query: String,
        maxHits: Int = 6,
        maxChars: Int = 6_000,
    ): List<MemoryHit> {
        val trimmed = query.trim()
        if (content.isBlank() || trimmed.isBlank()) return emptyList()
        val chunks = chunk(content)
        if (chunks.isEmpty()) return emptyList()
        val queryTokens = tokenize(trimmed)
            .filter { it.length >= 2 || it.isLatinToken() }
        if (queryTokens.isEmpty()) return emptyList()

        // 词频与文档频率
        val df = HashMap<String, Int>(64)
        val chunkTokens = chunks.map { chunk ->
            val tokens = tokenize(chunk.text)
            val freq = HashMap<String, Int>(32)
            tokens.forEach { token -> freq[token] = (freq[token] ?: 0) + 1 }
            tokens.distinct().forEach { token -> df[token] = (df[token] ?: 0) + 1 }
            freq
        }
        val docCount = chunks.size
        val avgLen = chunkTokens.map { it.values.sum() }
            .takeIf { it.isNotEmpty() }
            ?.let { it.sum().toDouble() / it.size }
            ?: 0.0

        // BM25 打分 + 标题命中加权
        val scored = chunks.indices.map { index ->
            val chunk = chunks[index]
            val freq = chunkTokens[index]
            val length = freq.values.sum()
            var score = 0.0
            queryTokens.distinct().forEach { token ->
                val tf = freq[token] ?: 0
                if (tf == 0) return@forEach
                val idf = ln(1.0 + (docCount - (df[token] ?: 0) + 0.5) / ((df[token] ?: 0) + 0.5))
                val tfNorm = tf * (K1 + 1.0) / (tf + K1 * (1.0 - B + B * length / avgLen.coerceAtLeast(1.0)))
                score += idf * tfNorm
            }
            if (chunk.heading != null) {
                val headingTokens = tokenize(chunk.heading)
                val headingHits = queryTokens.count { headingTokens.contains(it) }
                if (headingHits > 0) {
                    score *= (1.0 + HEADING_BOOST * headingHits)
                }
            }
            if (chunk.text.contains(trimmed, ignoreCase = true)) {
                score *= 1.0 + EXACT_BOOST
            }
            index to score
        }.filter { it.second > 0.0 }
            .sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })

        val hits = scored.take(maxHits.coerceAtLeast(1)).mapNotNull { (index, score) ->
            val chunk = chunks[index]
            val rendered = renderChunk(chunk)
            if (rendered.isBlank()) null else MemoryHit(
                startLine = chunk.startLine,
                endLine = chunk.endLine,
                score = score,
                text = rendered,
            )
        }
        return trimToBudget(hits, maxChars)
    }

    private fun renderChunk(chunk: MemoryChunk): String = buildString {
        chunk.heading?.let { appendLine(it) }
        append(chunk.text)
    }.trim()

    private fun String.isLatinToken(): Boolean =
        any { it.isLetter() && it.code !in 0x4E00..0x9FFF && it.code !in 0x3040..0x30FF }

    private fun trimToBudget(hits: List<MemoryHit>, maxChars: Int): List<MemoryHit> {
        if (maxChars <= 0) return emptyList()
        val result = ArrayList<MemoryHit>(hits.size)
        var budget = maxChars
        for (hit in hits) {
            val rendered = "${hit.startLine}:${hit.endLine} [${"%.2f".format(hit.score)}] ${hit.text}"
            if (result.isNotEmpty() && budget < rendered.length) break
            result += hit
            budget -= rendered.length
        }
        return result
    }

    private const val MAX_PARAGRAPH_LINES = 12
    private const val K1 = 1.2
    private const val B = 0.75
    private const val HEADING_BOOST = 0.6
    private const val EXACT_BOOST = 0.5
    private val HEADING = Regex("^#{1,3}\\s+.+$")
}
