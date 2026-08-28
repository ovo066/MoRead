package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多气泡拆分与语音标记解析。核心不变量：
 * 开关只影响文本段是否合并，**标记永远被解析掉**——关掉开关不该让方括号漏进正文。
 */
class CompanionMessagePartsTest {

    @Test
    fun `multi bubble splits each line into its own bubble`() {
        val parts = parseCompanionParts("我刚读到那段\n真的有点起鸡皮疙瘩\n你读到哪了？", multiBubble = true)
        assertEquals(
            listOf("我刚读到那段", "真的有点起鸡皮疙瘩", "你读到哪了？"),
            parts.map { it.text }
        )
        assertTrue(parts.all { it is CompanionBubblePart.Text })
    }

    @Test
    fun `single bubble merges adjacent lines but keeps the line breaks`() {
        val parts = parseCompanionParts("第一行\n第二行\n第三行", multiBubble = false)
        assertEquals(1, parts.size)
        assertEquals("第一行\n第二行\n第三行", parts.single().text)
    }

    /**
     * 关掉多气泡时必须原样还原空行：Markdown 里单换行不是段落分隔，
     * 把「段一\n\n段二」压成「段一\n段二」会让整条回复渲染成一大坨。
     */
    @Test
    fun `single bubble restores the blank lines between paragraphs`() {
        val content = "开头这段说了一件事。\n\n第二段说了另一件事。\n\n第三段收尾。"
        val parts = parseCompanionParts(content, multiBubble = false)
        assertEquals(1, parts.size)
        assertEquals(content, parts.single().text)
    }

    @Test
    fun `single bubble reproduces mixed single and blank line breaks`() {
        val content = "引子：\n- 甲\n- 乙\n\n所以我觉得如此。"
        val parts = parseCompanionParts(content, multiBubble = false)
        assertEquals(1, parts.size)
        assertEquals(content, parts.single().text)
    }

    @Test
    fun `single bubble keeps a fenced block separated from its lead-in`() {
        val content = "这样写：\n\n```kotlin\nfun main() {}\n```\n\n看懂了吗？"
        val parts = parseCompanionParts(content, multiBubble = false)
        assertEquals(1, parts.size)
        assertEquals(content, parts.single().text)
    }

    @Test
    fun `blank lines separate blocks without producing empty bubbles`() {
        val parts = parseCompanionParts("上半段\n\n\n下半段", multiBubble = true)
        assertEquals(listOf("上半段", "下半段"), parts.map { it.text })
    }

    @Test
    fun `blank input yields no bubbles`() {
        assertTrue(parseCompanionParts("", multiBubble = true).isEmpty())
        assertTrue(parseCompanionParts("   \n\n  ", multiBubble = false).isEmpty())
    }

    // ---- 不可拆的整体 ----

    @Test
    fun `fenced code blocks survive multi bubble intact`() {
        val content = """
            这样写：
            ```kotlin
            fun main() {

                println("hi")
            }
            ```
            看懂了吗？
        """.trimIndent()
        val parts = parseCompanionParts(content, multiBubble = true)
        assertEquals(3, parts.size)
        assertEquals("这样写：", parts[0].text)
        // 代码块连同内部空行整块保留，绝不能被逐行切碎。
        assertTrue(parts[1].text.startsWith("```kotlin"))
        assertTrue(parts[1].text.endsWith("```"))
        assertTrue(parts[1].text.contains("println(\"hi\")"))
        assertEquals("看懂了吗？", parts[2].text)
    }

    @Test
    fun `a tilde fence is not closed by a backtick fence`() {
        val content = "~~~\n```\n还在块里\n~~~\n块外"
        val parts = parseCompanionParts(content, multiBubble = true)
        assertEquals(2, parts.size)
        assertTrue(parts[0].text.contains("还在块里"))
        assertEquals("块外", parts[1].text)
    }

    @Test
    fun `an unclosed fence from a truncated stream keeps its content`() {
        val parts = parseCompanionParts("```\nfun main() {", multiBubble = true)
        assertEquals(1, parts.size)
        assertTrue(parts.single().text.contains("fun main() {"))
    }

    @Test
    fun `tables and lists stay glued to their lead-in line`() {
        val content = "对比如下：\n| 项 | 值 |\n|---|---|\n| a | 1 |\n再说说列表：\n- 第一条\n- 第二条\n你怎么看？"
        val parts = parseCompanionParts(content, multiBubble = true)
        assertEquals(
            listOf(
                "对比如下：\n| 项 | 值 |\n|---|---|\n| a | 1 |",
                "再说说列表：\n- 第一条\n- 第二条",
                "你怎么看？"
            ),
            parts.map { it.text }
        )
    }

    @Test
    fun `ordered list items are treated as continuations`() {
        val parts = parseCompanionParts("三层意思：\n1. 一层\n2. 二层", multiBubble = true)
        assertEquals(1, parts.size)
        assertTrue(parts.single().text.contains("2. 二层"))
    }

    // ---- 模型主动圈出的整段 ----

    @Test
    fun `a marked block becomes one bubble and its markers are stripped`() {
        val content = "先说结论：\n[整段]\n一、铺垫\n二、转折\n三、收束\n[/整段]\n你觉得呢？"
        val parts = parseCompanionParts(content, multiBubble = true)
        assertEquals(3, parts.size)
        assertEquals("先说结论：", parts[0].text)
        assertEquals("一、铺垫\n二、转折\n三、收束", parts[1].text)
        assertEquals("你觉得呢？", parts[2].text)
    }

    @Test
    fun `marker spellings are forgiving and case-insensitive`() {
        listOf("[整段]" to "[/整段]", "[[整段]]" to "[[/整段]]", "[block]" to "[/block]", "[BLOCK]" to "[/BLOCK]")
            .forEach { (open, close) ->
                val parts = parseCompanionParts("$open\n甲\n乙\n$close", multiBubble = true)
                assertEquals(open, 1, parts.size)
                assertEquals(open, "甲\n乙", parts.single().text)
            }
    }

    @Test
    fun `nothing inside a marked block is reinterpreted`() {
        // 块内的空行、语音标记、列表符号都属于内容本身，不该再被解析。
        val content = "[整段]\n[语音] 这是文字不是语音\n\n- 列表项\n[/整段]"
        val parts = parseCompanionParts(content, multiBubble = true)
        assertEquals(1, parts.size)
        assertTrue(parts.single() is CompanionBubblePart.Text)
        assertEquals("[语音] 这是文字不是语音\n\n- 列表项", parts.single().text)
    }

    @Test
    fun `an unclosed block from a truncated stream keeps its content`() {
        val parts = parseCompanionParts("[整段]\n写到一半就断了", multiBubble = true)
        assertEquals(1, parts.size)
        assertEquals("写到一半就断了", parts.single().text)
    }

    @Test
    fun `with multi bubble off the markers are still stripped and everything merges`() {
        // 关掉多气泡本来就是「整条消息一个气泡」，整段标记此时只需消失，不需要再分泡。
        val parts = parseCompanionParts("引子\n[整段]\n甲\n乙\n[/整段]", multiBubble = false)
        assertEquals(1, parts.size)
        assertEquals("引子\n甲\n乙", parts.single().text)
    }

    @Test
    fun `a marker inside a line is ordinary text`() {
        val parts = parseCompanionParts("我用 [整段] 标记来分段", multiBubble = true)
        assertEquals(1, parts.size)
        assertEquals("我用 [整段] 标记来分段", parts.single().text)
    }

    // ---- 语音标记 ----

    @Test
    fun `all four voice marker spellings are recognised and stripped`() {
        listOf("[语音]", "[[语音]]", "[voice]", "[[VOICE]]").forEach { marker ->
            val parts = parseCompanionParts("$marker 这句我想说给你听", multiBubble = true)
            assertEquals(marker, 1, parts.size)
            assertTrue(marker, parts.single() is CompanionBubblePart.Voice)
            assertEquals(marker, "这句我想说给你听", parts.single().text)
        }
    }

    @Test
    fun `voice lines stay separate bubbles even when multi bubble is off`() {
        val content = "先说个背景\n[语音] 这段我念给你听\n然后你再看这句"
        val parts = parseCompanionParts(content, multiBubble = false)
        assertEquals(3, parts.size)
        assertEquals("先说个背景", parts[0].text)
        assertTrue(parts[1] is CompanionBubblePart.Voice)
        assertEquals("这段我念给你听", parts[1].text)
        assertEquals("然后你再看这句", parts[2].text)
        assertTrue(parts.hasVoice())
    }

    @Test
    fun `two text runs around a voice line merge separately when multi bubble is off`() {
        val content = "甲\n乙\n[语音] 丙\n丁\n戊"
        val parts = parseCompanionParts(content, multiBubble = false)
        assertEquals(listOf("甲\n乙", "丙", "丁\n戊"), parts.map { it.text })
    }

    @Test
    fun `a marker with no spoken text produces no bubble`() {
        assertTrue(parseCompanionParts("[语音]", multiBubble = true).isEmpty())
        assertTrue(parseCompanionParts("[语音]   ", multiBubble = false).isEmpty())
    }

    @Test
    fun `a marker in the middle of a line is left alone`() {
        val parts = parseCompanionParts("他说 [语音] 两个字很奇怪", multiBubble = true)
        assertEquals(1, parts.size)
        assertTrue(parts.single() is CompanionBubblePart.Text)
        assertEquals("他说 [语音] 两个字很奇怪", parts.single().text)
    }

    @Test
    fun `plain replies report no voice`() {
        assertFalse(parseCompanionParts("就是普通的一句话", multiBubble = true).hasVoice())
    }

    @Test
    fun `only first two voice lines stay voice bubbles`() {
        val parts = parseCompanionParts(
            "[语音] 第一条\n[语音] 第二条\n[语音] 第三条",
            multiBubble = true
        )
        assertTrue(parts[0] is CompanionBubblePart.Voice)
        assertTrue(parts[1] is CompanionBubblePart.Voice)
        assertTrue(parts[2] is CompanionBubblePart.Text)
    }
}
