package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageAssetMergerTest {
    private val beat = StageBeat(BeatKind.DIALOGUE, "测试对白", SourceRange(10, 20), emotion = StageEmotion.HAPPY, instruction = "轻声")
    private fun script(b: StageBeat = beat) = StageScript(0, "测试", listOf(b), emptyList(), "温以南", emptyList())
    private fun segment(start: Int = 10, end: Int = 18, narrator: Boolean = false, emotion: String? = "中性", instruction: String? = "低声") = StageScriptSegment(start, end, "温以南", narrator, emotion, instruction)

    @Test fun `达到百分之八十才合并且输入保持不变`() {
        val input = script()
        assertEquals(input, StageAssetMerger.applyAudiobook(input, listOf(segment(end = 17))))
        val result = StageAssetMerger.applyAudiobook(input, listOf(segment()))
        assertEquals("温以南", result.beats.single().speaker!!.label)
        assertTrue(result.beats.single().speaker!!.isProtagonist)
        assertEquals(.95f, result.beats.single().speaker!!.confidence)
        assertEquals(StageEmotion.NEUTRAL, result.beats.single().emotion)
        assertEquals("低声", result.beats.single().instruction)
        assertEquals(StageSource.AUDIOBOOK, result.beats.single().sources["emotion"])
        assertEquals(script(), input)
        assertNull(input.beats.single().speaker)
    }

    @Test fun `旁白段空角色与非对白都不改`() {
        val input = script()
        assertEquals(input, StageAssetMerger.applyAudiobook(input, listOf(segment(narrator = true))))
        assertEquals(input, StageAssetMerger.applyAudiobook(input, listOf(segment().copy(roleName = " "))))
        val narration = script(beat.copy(kind = BeatKind.NARRATION))
        assertEquals(narration, StageAssetMerger.applyAudiobook(narration, listOf(segment())))
        assertNotNull(StageAssetMerger.applyAudiobook(script(beat.copy(kind = BeatKind.THOUGHT)), listOf(segment())).beats.single().speaker)
    }

    @Test fun `导演与用户字段逐项受到保护`() {
        for (source in listOf(StageSource.DIRECTOR, StageSource.USER)) {
            val b = beat.copy(speaker = StageSpeaker("宁照雪", 1f, source), sources = mapOf("emotion" to source, "instruction" to source))
            assertEquals(b, StageAssetMerger.applyAudiobook(script(b), listOf(segment())).beats.single())
        }
    }

    @Test fun `无效情绪与空语气保留原值`() {
        val result = StageAssetMerger.applyAudiobook(script(), listOf(segment(emotion = "未知情绪", instruction = " "))).beats.single()
        assertEquals(StageEmotion.HAPPY, result.emotion)
        assertEquals("轻声", result.instruction)
        assertFalse(result.sources.containsKey("emotion"))
        assertFalse(result.sources.containsKey("instruction"))
    }
}
