package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenPurifyRuleTest {
    @Test
    fun `净化文本但保留原文坐标`() {
        val body = "前文 ★★你好！！ 后文"
        val result = purifyForListening(
            body = body,
            startCharOffset = 3,
            endCharOffset = 9,
            rules = listOf(
                ReaderTextReplacementRule(1, "装饰符", "[★☆]", forListenOnly = true),
                ReaderTextReplacementRule(2, "连续叹号", "！+", "！", forListenOnly = true)
            )
        )
        assertEquals(3, result.startCharOffset)
        assertEquals(9, result.endCharOffset)
        assertEquals("你好！", result.text)
    }
}
