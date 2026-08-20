package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParagraphSegmenterTest {
    @Test
    fun `短段落保持完整而不逐句切开`() {
        val body = "第一句。第二句。\n下一段。"
        val spans = SentenceSegmenter.segmentParagraphs(body, 400)
        assertEquals(listOf("第一句。第二句。", "下一段。"), spans.map { body.substring(it.start, it.end) })
    }

    @Test
    fun `超长段回退到句边界且不越界`() {
        val body = "甲".repeat(60) + "。" + "乙".repeat(60) + "。"
        val spans = SentenceSegmenter.segmentParagraphs(body, 70)
        assertTrue(spans.size >= 2)
        assertEquals(0, spans.first().start)
        assertEquals(body.length, spans.last().end)
    }
}
