package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

class StageEffectBudgetTest {
    private fun beat(screen: ScreenEffect? = ScreenEffect.SHAKE, enter: BeatEnter? = null) = StageBeat(BeatKind.NARRATION, "风起。", SourceRange(0, 3), screen = screen, enter = enter)

    @Test fun `强屏幕按档限频且轻屏幕不限`() {
        val beats = List(14) { beat() }
        val standard = StageEffectBudget.apply(beats, StageIntensity.STANDARD)
        assertEquals(listOf(0, 6, 12), standard.indices.filter { standard[it].screen != null })
        val lavish = StageEffectBudget.apply(beats, StageIntensity.LAVISH)
        assertEquals(listOf(0, 3, 6, 9, 12), lavish.indices.filter { lavish[it].screen != null })
        assertTrue(StageEffectBudget.apply(beats, StageIntensity.RESTRAINED).all { it.screen == null && it.blockedScreen == ScreenEffect.SHAKE })
        for (screen in listOf(ScreenEffect.DIM, ScreenEffect.VIGNETTE, ScreenEffect.BLACKOUT)) assertTrue(StageEffectBudget.apply(List(3) { beat(screen) }, StageIntensity.RESTRAINED).all { it.screen == screen })
        assertTrue(StageEffectBudget.apply(listOf(beat(ScreenEffect.FLASH), beat(ScreenEffect.HEARTBEAT)), StageIntensity.STANDARD).last().blockedScreen == ScreenEffect.HEARTBEAT)
    }

    @Test fun `强入场优先且不消耗屏幕额度`() {
        val output = StageEffectBudget.apply(listOf(beat(enter = BeatEnter.SLAM), beat()), StageIntensity.STANDARD)
        assertEquals(BeatEnter.SLAM, output[0].enter)
        assertEquals(ScreenEffect.SHAKE, output[0].blockedScreen)
        assertNull(output[0].screen)
        assertEquals(ScreenEffect.SHAKE, output[1].screen)
    }

    @Test fun `行内强调只保留前两处且不计人名术语`() {
        val spans = listOf(SpanStyle.NAME, SpanStyle.STRESS, SpanStyle.TERM, SpanStyle.SLOW, SpanStyle.WAVE, SpanStyle.NAME, SpanStyle.GLOW).map { StageSpan(0, 1, setOf(it)) }
        val b = beat(null).copy(spans = spans)
        val result = StageEffectBudget.apply(listOf(b), StageIntensity.STANDARD).single()
        assertEquals(listOf(spans[0], spans[1], spans[2], spans[3], spans[5]), result.spans)
        assertEquals(7, b.spans.size)
    }

    @Test fun `克制与减少动态退化入场并拦下强屏幕`() {
        for (enter in listOf(BeatEnter.SLAM, BeatEnter.GLITCH, BeatEnter.RISE, BeatEnter.TYPE, BeatEnter.WHISPER)) {
            for ((intensity, reduce) in listOf(StageIntensity.RESTRAINED to false, StageIntensity.LAVISH to true)) {
                val p = StageEffectBudget.apply(listOf(beat(enter = enter)), intensity, reduce).single()
                assertEquals(BeatEnter.FADE, p.enter)
                assertNull(p.screen)
                assertEquals(ScreenEffect.SHAKE, p.blockedScreen)
            }
        }
        assertEquals(BeatEnter.INSTANT, StageEffectBudget.apply(listOf(beat(null, BeatEnter.INSTANT)), StageIntensity.RESTRAINED).single().enter)
    }

    @Test fun `表情与语气节奏及显式入场优先`() {
        val x = StageExpressions.of(StageEmotion.SAD, 2, "轻声 急促 缓慢 哽咽")
        assertTrue(x.softer && x.tremble && x.subdued)
        assertEquals(BeatEnter.WHISPER, x.enter)
        assertEquals(1.25f * .6f * 1.4f * 1.15f * 1.3f, x.paceMultiplier, .0001f)
        val angry = StageExpressions.of(StageEmotion.ANGRY, 3, "高声")
        assertTrue(angry.bolder && angry.louder)
        assertEquals(ScreenEffect.SHAKE, angry.screen)
        assertEquals(ScreenEffect.VIGNETTE, StageExpressions.of(StageEmotion.FEAR, 2, null).screen)
        assertTrue(StageExpressions.of(StageEmotion.SURPRISE, 0, null).pop)
        assertTrue(StageExpressions.of(StageEmotion.DISGUST, 0, null).subdued)
        assertTrue(StageExpressions.of(StageEmotion.HAPPY, 0, null).warm)
        val b = beat(null, BeatEnter.INSTANT).copy(emotion = StageEmotion.HAPPY, pace = BeatPace.SLOW)
        val p = StageEffectBudget.apply(listOf(b), StageIntensity.STANDARD).single()
        assertEquals(BeatEnter.INSTANT, p.enter)
        assertEquals(1.35f, p.paceMultiplier, .0001f)
        assertEquals(.7f, StageEffectBudget.apply(listOf(b.copy(pace = BeatPace.FAST)), StageIntensity.STANDARD).single().paceMultiplier, .0001f)
    }
}
