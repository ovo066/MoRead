package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSyntaxHighlighterTest {
    @Test
    fun matchesMultiplePairedSegmentsAcrossLines() {
        val rule = ReaderSyntaxRule(7, "对白", "“", "”", 0xFF112233.toInt())
        val text = "他说：“第一句。\n第二句。”然后“再见”。"

        val spans = ReaderSyntaxHighlighter.spans(text, listOf(rule))

        assertEquals(2, spans.size)
        assertEquals("“第一句。\n第二句。”", text.substring(spans[0].start, spans[0].endExclusive))
        assertEquals("“再见”", text.substring(spans[1].start, spans[1].endExclusive))
    }

    @Test
    fun canStyleContentWithoutDelimitersAndRoundTripRules() {
        val rule = ReaderSyntaxRule(
            id = 9,
            name = "书名",
            startDelimiter = "《",
            endDelimiter = "》",
            colorArgb = 0xFFAABBCC.toInt(),
            includeDelimiters = false,
            underline = true
        )
        val text = "阅读《山海经》。"
        val span = ReaderSyntaxHighlighter.spans(text, listOf(rule)).single()

        assertEquals("山海经", text.substring(span.start, span.endExclusive))
        assertTrue(span.underline)
        val decoded = ReaderSyntaxRuleCodec.decode(ReaderSyntaxRuleCodec.encode(listOf(rule))).single()
        assertEquals(rule, decoded)
        assertFalse(decoded.includeDelimiters)
    }

    @Test
    fun `regex rule carries custom font background and text effects`() {
        val rule = ReaderSyntaxRule(
            id = 11,
            name = "提示行",
            startDelimiter = "",
            endDelimiter = "",
            colorArgb = 0xFF102030.toInt(),
            underline = true,
            matchMode = ReaderSyntaxMatchMode.REGEX,
            pattern = "^notice:.+$",
            ignoreCase = true,
            backgroundArgb = 0x33112233,
            font = ReaderSyntaxFont.MONOSPACE,
            bold = true,
            italic = true,
            strikethrough = true
        )
        val text = "普通行\nNOTICE: important\n结束"

        val span = ReaderSyntaxHighlighter.spans(text, listOf(rule)).single()

        assertEquals("NOTICE: important", text.substring(span.start, span.endExclusive))
        assertEquals(rule.backgroundArgb, span.backgroundArgb)
        assertEquals(ReaderSyntaxFont.MONOSPACE, span.font)
        assertTrue(span.bold)
        assertTrue(span.italic)
        assertTrue(span.underline)
        assertTrue(span.strikethrough)
        assertEquals(rule, ReaderSyntaxRuleCodec.decode(ReaderSyntaxRuleCodec.encode(listOf(rule))).single())
    }

    @Test
    fun `invalid regex is ignored without affecting later rules`() {
        val invalid = ReaderSyntaxRule(
            id = 12,
            name = "无效",
            startDelimiter = "",
            endDelimiter = "",
            colorArgb = 0,
            matchMode = ReaderSyntaxMatchMode.REGEX,
            pattern = "(["
        )
        val valid = ReaderSyntaxRule(13, "书名", "《", "》", 0xFF556677.toInt())

        val spans = ReaderSyntaxHighlighter.spans("《可用》", listOf(invalid, valid))

        assertEquals(1, spans.size)
        assertEquals(13, spans.single().ruleId)
    }
}
