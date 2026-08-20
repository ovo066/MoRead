package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AudiobookRevisionTest {
    @Test
    fun `普通显示规则变化不影响有声书版本`() {
        val body = "原始正文"
        val first = listOf(ReaderTextReplacementRule(1, "显示", "原始", "替换", enabled = true))
        val second = listOf(ReaderTextReplacementRule(1, "显示", "原始", "另一项", enabled = true))
        assertEquals(audiobookRevision(body, first), audiobookRevision(body, second))
    }

    @Test
    fun `正文或听书净化规则变化会使版本失效`() {
        val rules = listOf(
            ReaderTextReplacementRule(2, "听书", "旁白", "", enabled = true, forListenOnly = true)
        )
        assertNotEquals(audiobookRevision("正文甲", rules), audiobookRevision("正文乙", rules))
        assertNotEquals(
            audiobookRevision("正文甲", rules),
            audiobookRevision("正文甲", rules.map { it.copy(replacement = "停顿") })
        )
    }
}
