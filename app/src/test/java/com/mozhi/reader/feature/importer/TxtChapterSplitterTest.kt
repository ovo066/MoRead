package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtChapterSplitterTest {
    private val splitter = TxtChapterSplitter()
    private val chineseRule = TxtTocRule(
        id = 1,
        enable = true,
        name = "中文章节",
        rule = "^[ \\t　]{0,4}第[一二三四五六七八九十百千万\\d]+章.{0,30}$"
    )

    @Test
    fun `splits chinese chapters and keeps preface`() {
        val text = buildString {
            appendLine("这是序言。")
            appendLine()
            repeat(5) { index ->
                appendLine("第${index + 1}章 标题${index + 1}")
                appendLine("这是第${index + 1}章的正文。".repeat(40))
                appendLine()
            }
        }

        val result = splitter.splitWithRule(text, chineseRule)

        requireNotNull(result)
        assertEquals(6, result.chapters.size)
        assertEquals("序章", result.chapters.first().title)
        assertEquals("第1章 标题1", result.chapters[1].title)
        assertFalse(result.usedFallback)
    }

    @Test
    fun `chooses english rule when it yields more chapters`() {
        val englishRule = TxtTocRule(
            id = 2,
            enable = true,
            name = "English",
            rule = "^[Cc]hapter\\s+\\d+.{0,30}$"
        )
        val text = (1..8).joinToString("\n\n") { chapter ->
            "Chapter $chapter Sample\n${"Body sentence. ".repeat(60)}"
        }

        val result = splitter.chooseBest(text, listOf(chineseRule, englishRule))

        assertEquals(englishRule.id, result.rule?.id)
        assertEquals(8, result.chapters.size)
    }

    @Test
    fun `falls back to fixed length sections when no headings exist`() {
        val text = "没有章节标题的长文本。".repeat(2_500)

        val result = splitter.chooseBest(text, listOf(chineseRule))

        assertTrue(result.usedFallback)
        assertTrue(result.chapters.size >= 2)
        assertTrue(result.chapters.all { it.content.isNotBlank() })
    }

    @Test
    fun `supports a custom regex`() {
        val text = (1..5).joinToString("\n") { index ->
            "==== $index 自定义标题 ====\n正文正文正文正文正文正文正文正文"
        }

        val result = splitter.splitWithCustomRegex(text, "^====\\s+\\d+.*====$")

        requireNotNull(result)
        assertEquals(5, result.chapters.size)
        assertEquals("自定义规则", result.rule?.name)
    }

    @Test(timeout = 10_000)
    fun `recognizes a six hundred chapter web novel`() {
        val text = buildString {
            repeat(600) { index ->
                appendLine("第${index + 1}章 墨知长篇测试${index + 1}")
                appendLine("这是用于验证千章级导入性能与章节识别准确率的正文。".repeat(12))
                appendLine()
            }
        }

        val result = splitter.chooseBest(text, listOf(chineseRule))

        assertEquals(600, result.chapters.size)
        assertEquals("第1章 墨知长篇测试1", result.chapters.first().title)
        assertEquals("第600章 墨知长篇测试600", result.chapters.last().title)
        assertFalse(result.usedFallback)
    }
}
