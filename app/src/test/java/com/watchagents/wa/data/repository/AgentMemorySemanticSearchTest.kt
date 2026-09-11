package com.watchagents.wa.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemorySemanticSearchTest {
    @Test
    fun cjkBigramsMatchMeaningBeyondExactKeyword() {
        val content = """
            # 核心记忆
            用户偏好使用普通话交流

            ## 项目
            WA Agent 使用 Kotlin 开发

            ## 详细背景
            咖啡因过敏，不喝咖啡
        """.trimIndent()

        // "开发语言" 与 "使用 Kotlin 开发" 没有完全相同的词，但 bigram 语义重叠
        val hits = AgentMemorySemanticSearch.search(content, "开发语言是什么", maxHits = 3)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits[0].text.contains("Kotlin"))
    }

    @Test
    fun latinTokensMatchCaseInsensitively() {
        val content = "# 项目\nBuild with Kotlin and Compose\n# 偏好\nLove Python"
        val hits = AgentMemorySemanticSearch.search(content, "kotlin compose")
        assertTrue(hits.isNotEmpty())
        assertEquals(1, hits.size)
        assertTrue(hits[0].text.contains("Kotlin and Compose"))
    }

    @Test
    fun sectionChunksCarryLineRanges() {
        val content = "# 核心记忆\nA\n\n## 偏好\nB\n\n## 项目\nC\nD"
        val hits = AgentMemorySemanticSearch.search(content, "项目 C", maxHits = 3)
        assertTrue(hits.isNotEmpty())
        val top = hits[0]
        assertEquals(7, top.startLine)
        assertEquals(9, top.endLine)
    }

    @Test
    fun emptyQueryOrContentReturnsNothing() {
        assertTrue(AgentMemorySemanticSearch.search("", "x").isEmpty())
        assertTrue(AgentMemorySemanticSearch.search("内容", "   ").isEmpty())
        assertTrue(AgentMemorySemanticSearch.search("内容", "nothing-here-zzz").isEmpty())
    }

    @Test
    fun unrelatedSectionsDoNotMatch() {
        val content = "# 核心记忆\n喜欢蓝色\n## 项目\nWA 开发"
        val hits = AgentMemorySemanticSearch.search(content, "去哪个餐厅吃饭", maxHits = 3)
        assertTrue(hits.isEmpty())
    }

    @Test
    fun maxCharsBudgetLimitsRenderedSnippets() {
        val content = (1..20).joinToString("\n") { "## 章节 $it\n内容 $it 详细信息" }
        val hits = AgentMemorySemanticSearch.search(content, "章节", maxHits = 6, maxChars = 300)
        assertTrue(hits.isNotEmpty())
        val total = hits.sumOf { it.text.length }
        assertTrue(total <= 400)
    }
}
