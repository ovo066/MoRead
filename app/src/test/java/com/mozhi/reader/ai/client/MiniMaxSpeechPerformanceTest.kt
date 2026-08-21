package com.mozhi.reader.ai.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMaxSpeechPerformanceTest {
    @Test
    fun sadWhisperLowersSpeedVolumeAndPitch() {
        val performance = MiniMaxSpeechPerformanceMapper.map("悲伤", "轻声，缓慢")

        assertTrue(performance.speedMultiplier < 0.85f)
        assertTrue(performance.volumeMultiplier < 0.80f)
        assertEquals(-2, performance.pitchOffset)
    }

    @Test
    fun angryShoutRaisesVolume() {
        val performance = MiniMaxSpeechPerformanceMapper.map("愤怒", "高声，急促")

        assertTrue(performance.speedMultiplier > 1.15f)
        assertTrue(performance.volumeMultiplier > 1.20f)
        assertTrue(performance.pitchOffset > 0)
    }

    @Test
    fun shortPauseMarkerIsInsertedBeforeClosingQuote() {
        val performance = MiniMaxSpeechPerformanceMapper.map("中性", "轻声，句末短停")

        assertEquals("“别怕。<#0.28#>”", performance.applyToText("“别怕。”"))
    }
}
