package com.mozhi.reader.core.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageVoiceTest {
    private fun beat(start: Int, end: Int, before: Pair<Int, Int>? = null) = StageBeat(
        BeatKind.DIALOGUE, "x", SourceRange(start, end),
        asideBefore = before?.let { StageAside("提示语", SourceRange(it.first, it.second)) }
    )
    private fun clip(start: Int, end: Int, millis: Int = 1000) = StageVoiceClip(start, end, "/voice/$start.wav", millis)

    @Test fun eachClipPlaysOnceAtTheBeatWhereItStarts() {
        // 一段旁白横跨两拍：只在它开始的那一拍放。
        val clips = listOf(clip(0, 30), clip(30, 40), clip(40, 50))
        assertEquals(listOf(0), StageVoice.clipsFor(beat(0, 20), clips).map { it.start })
        assertEquals(listOf(30), StageVoice.clipsFor(beat(20, 40), clips).map { it.start })
        assertEquals(listOf(40), StageVoice.clipsFor(beat(40, 60), clips).map { it.start })
        val beats = listOf(beat(0, 20), beat(20, 40), beat(40, 60))
        assertEquals(clips, beats.flatMap { StageVoice.clipsFor(it, clips) })
    }

    @Test fun speechTagAndLineInOneBeatPlayInTextOrder() {
        // 「林晚低声道：」是旁白配音，引号里是角色配音，同属一拍。
        val clips = listOf(clip(16, 30, 2200), clip(10, 16, 800))
        val shown = StageVoice.clipsFor(beat(16, 30, before = 10 to 16), clips)
        assertEquals(listOf(10, 16), shown.map { it.start })
        assertEquals(3000L, StageVoice.totalMillis(shown))
    }

    @Test fun beatsWithoutSourceTextHaveNoVoice() {
        val clips = listOf(clip(0, 10))
        assertTrue(StageVoice.clipsFor(StageBeat(BeatKind.TITLE, "章", null), clips).isEmpty())
        // 排进来的插图拍是长度为零的一个点。
        assertTrue(StageVoice.clipsFor(StageBeat(BeatKind.IMAGE, "", SourceRange(0, 0)), clips).isEmpty())
        assertEquals(0L, StageVoice.totalMillis(listOf(clip(0, 1, millis = -5))))
    }
}
