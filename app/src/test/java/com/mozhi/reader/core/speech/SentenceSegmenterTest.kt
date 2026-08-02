package com.mozhi.reader.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {

    private fun texts(body: String, maxChars: Int = SentenceSegmenter.DEFAULT_MAX_CHARS): List<String> =
        SentenceSegmenter.segment(body, maxChars).map { body.substring(it.start, it.end) }

    @Test
    fun `按终止标点断句且收尾引号归前句`() {
        val body = "他说：“今天不去了。”她愣了一下！随后点头？好。"
        assertEquals(
            listOf("他说：“今天不去了。”", "她愣了一下！", "随后点头？", "好。"),
            texts(body)
        )
    }

    @Test
    fun `跨行切段且跳过空行`() {
        val body = "第一段落。\n\n　　第二段落没有句号\n第三段。"
        assertEquals(listOf("第一段落。", "第二段落没有句号", "第三段。"), texts(body))
    }

    @Test
    fun `超长句优先在逗号处折分`() {
        val long = "一二三四五六七八九十，好句子来了这里继续说话直到结束"
        val spans = SentenceSegmenter.segment(long, maxChars = 12)
        val pieces = spans.map { long.substring(it.start, it.end) }
        assertTrue(pieces.size >= 2)
        assertEquals("一二三四五六七八九十，", pieces.first())
        // 区间递增且互不重叠
        spans.zipWithNext().forEach { (a, b) -> assertTrue(a.end <= b.start) }
    }

    @Test
    fun `无停顿超长句硬切`() {
        val long = "呜".repeat(30)
        val spans = SentenceSegmenter.segment(long, maxChars = 12)
        assertEquals(listOf(12, 12, 6), spans.map(SentenceSpan::length))
        assertEquals(0, spans.first().start)
        assertEquals(30, spans.last().end)
    }

    @Test
    fun `区间坐标对准原文且清洗内联图占位符`() {
        val body = "　　开头有缩进。￼图后正文继续。"
        val spans = SentenceSegmenter.segment(body)
        assertEquals("开头有缩进。", body.substring(spans[0].start, spans[0].end))
        // 第二句以占位符开头时应被裁掉
        val second = spans[1]
        assertEquals("图后正文继续。", body.substring(second.start, second.end))
        assertEquals(
            "图后正文继续。",
            SentenceSegmenter.speakableText(body, second.start, second.end)
        )
    }

    @Test
    fun `indexAt 定位包含偏移的句子`() {
        val body = "甲句。乙句。丙句。"
        val spans = SentenceSegmenter.segment(body)
        assertEquals(0, SentenceSegmenter.indexAt(spans, 0))
        assertEquals(1, SentenceSegmenter.indexAt(spans, 3))
        assertEquals(2, SentenceSegmenter.indexAt(spans, 8))
        assertEquals(spans.size, SentenceSegmenter.indexAt(spans, body.length))
    }

    @Test
    fun `纯空白正文没有句子`() {
        assertTrue(SentenceSegmenter.segment("  \n　　\n").isEmpty())
    }
}
