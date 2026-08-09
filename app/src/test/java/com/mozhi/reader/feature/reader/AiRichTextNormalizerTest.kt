package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRichTextNormalizerTest {

    @Test
    fun `plain markdown stays byte for byte unchanged`() {
        val markdown = "## 标题\n\n- 一\n- 二\n\n`code`"
        assertEquals(markdown, AiRichTextNormalizer.toMarkdown(markdown))
    }

    @Test
    fun `common html becomes markdown structure`() {
        val output = AiRichTextNormalizer.toMarkdown(
            "<h2>人物</h2><p><strong>阿青</strong>来到<em>渡口</em>。</p>" +
                "<ul><li>线索一</li><li>线索二</li></ul>"
        )

        assertTrue(output.contains("## 人物"))
        assertTrue(output.contains("**阿青**"))
        assertTrue(output.contains("*渡口*"))
        assertTrue(output.contains("- 线索一"))
        assertTrue(output.contains("- 线索二"))
    }

    @Test
    fun `executable html and unsafe links are removed`() {
        val output = AiRichTextNormalizer.toMarkdown(
            "<p>安全</p><script>alert('x')</script>" +
                "<a href='javascript:alert(1)'>不要执行</a>" +
                "<a href='https://example.com'>可访问</a>"
        )

        assertFalse(output.contains("alert('x')"))
        assertFalse(output.contains("javascript:"))
        assertTrue(output.contains("不要执行"))
        assertTrue(output.contains("[可访问](https://example.com)"))
    }

    @Test
    fun `html table gets a markdown header row`() {
        val output = AiRichTextNormalizer.toMarkdown(
            "<table><tr><th>人物</th><th>状态</th></tr>" +
                "<tr><td>甲</td><td>登场</td></tr></table>"
        )

        assertTrue(output.contains("| 人物 | 状态 |"))
        assertTrue(output.contains("| --- | --- |"))
        assertTrue(output.contains("| 甲 | 登场 |"))
    }

    @Test
    fun `流式分块 - 按空行切段`() {
        val blocks = AiRichTextNormalizer.splitStreamingBlocks("第一段。\n\n第二段。\n\n第三段还没写完")
        assertEquals(listOf("第一段。", "第二段。", "第三段还没写完"), blocks)
    }

    @Test
    fun `流式分块 - 多个空行等价于一个分隔`() {
        val blocks = AiRichTextNormalizer.splitStreamingBlocks("甲\n\n\n\n乙")
        assertEquals(listOf("甲", "乙"), blocks)
    }

    @Test
    fun `流式分块 - 代码围栏内的空行不切`() {
        val source = "看这段代码：\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\n结束。"
        val blocks = AiRichTextNormalizer.splitStreamingBlocks(source)
        assertEquals(3, blocks.size)
        assertEquals("```kotlin\nval a = 1\n\nval b = 2\n```", blocks[1])
    }

    @Test
    fun `流式分块 - 未闭合围栏把余下内容留成一块`() {
        val source = "开头\n\n```\n流式中\n\n还在围栏里"
        val blocks = AiRichTextNormalizer.splitStreamingBlocks(source)
        assertEquals(2, blocks.size)
        assertEquals("```\n流式中\n\n还在围栏里", blocks[1])
    }

    @Test
    fun `流式分块 - 单段与空串原样返回`() {
        assertEquals(listOf("只有一段"), AiRichTextNormalizer.splitStreamingBlocks("只有一段"))
        assertEquals(listOf(""), AiRichTextNormalizer.splitStreamingBlocks(""))
    }

    @Test
    fun `流式分块 - 紧凑列表保持一块`() {
        val blocks = AiRichTextNormalizer.splitStreamingBlocks("- 一\n- 二\n- 三")
        assertEquals(listOf("- 一\n- 二\n- 三"), blocks)
    }
}
