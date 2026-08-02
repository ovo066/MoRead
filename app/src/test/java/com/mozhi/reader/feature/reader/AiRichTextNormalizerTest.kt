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
}
