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
}
