package com.mozhi.reader.ai.audiobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueRuleSegmenterTest {
    @Test
    fun `识别前置与后置说话人`() {
        val text = "林渊说道：“别怕。”\n“我没怕。”苏晚轻声说。"
        val dialogue = DialogueRuleSegmenter.segment(text)
            .filter { it.kind == AudiobookSegmentKind.DIALOGUE }
        assertEquals(listOf("林渊", "苏晚"), dialogue.map(DraftAudiobookSegment::roleName))
        assertEquals("“别怕。”", text.substring(dialogue[0].startCharOffset, dialogue[0].endCharOffset))
    }

    @Test
    fun `支持嵌套引号与破折号对白`() {
        val text = "他说：“她只说了『走吧』。”\n——我不会走。"
        val result = DialogueRuleSegmenter.segment(text)
        val dialogue = result.filter { it.kind == AudiobookSegmentKind.DIALOGUE }
        assertEquals(2, dialogue.size)
        assertEquals("“她只说了『走吧』。”", text.substring(dialogue[0].startCharOffset, dialogue[0].endCharOffset))
        result.zipWithNext().forEach { (previous, next) ->
            assertTrue(previous.endCharOffset <= next.startCharOffset)
        }
        result.forEach { assertTrue(it.startCharOffset in 0 until it.endCharOffset) }
    }

    @Test
    fun `连续对白继承最近明确角色`() {
        val text = "林渊道：“第一句。”\n“第二句。”"
        val dialogue = DialogueRuleSegmenter.segment(text)
            .filter { it.kind == AudiobookSegmentKind.DIALOGUE }
        assertEquals("林渊", dialogue[0].roleName)
        assertEquals("林渊", dialogue[1].roleName)
    }
}
