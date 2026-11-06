package com.mozhi.reader.feature.reader.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StageGlyphMotionTest {

    @Test fun `reveal fades in from below and settles exactly on the baseline`() {
        val start = StageGlyphMotion.reveal(0.05f, pop = false)
        val end = StageGlyphMotion.reveal(1f, pop = false)
        assertTrue(start.alpha < 0.3f)
        assertTrue(start.dyEm > 0.15f)
        assertEquals(GlyphPose(), end)
        assertEquals(GlyphPose(), StageGlyphMotion.reveal(3f, pop = false))
    }

    @Test fun `pop punctuation overshoots then lands at normal size`() {
        assertTrue(StageGlyphMotion.reveal(0.1f, pop = true).scale > 1.5f)
        assertTrue(StageGlyphMotion.reveal(0.7f, pop = true).scale < 1f)
        assertEquals(1f, StageGlyphMotion.reveal(1f, pop = true).scale)
    }

    @Test fun `sustained effects are deterministic and still under reduced motion`() {
        val flags = StageGlyphMotion.SHAKE or StageGlyphMotion.WAVE or StageGlyphMotion.GLITCH
        val seed = StageGlyphMotion.seed(3, 17)
        assertEquals(
            StageGlyphMotion.sustain(flags, 12_345, 3, seed, reduceMotion = false),
            StageGlyphMotion.sustain(flags, 12_345, 3, seed, reduceMotion = false)
        )
        assertEquals(GlyphPose(), StageGlyphMotion.sustain(flags, 12_345, 3, seed, reduceMotion = true))
        assertEquals(GlyphPose(), StageGlyphMotion.sustain(0, 12_345, 3, seed, reduceMotion = false))
    }

    @Test fun `tremble stays smaller than shake`() {
        var shake = 0f
        var tremble = 0f
        for (t in 0L until 2_000L step 37) {
            shake = maxOf(shake, kotlin.math.abs(StageGlyphMotion.sustain(StageGlyphMotion.SHAKE, t, 0, 0.5f, false).dxEm))
            tremble = maxOf(tremble, kotlin.math.abs(StageGlyphMotion.sustain(StageGlyphMotion.TREMBLE, t, 0, 0.5f, false).dxEm))
        }
        assertTrue(shake > tremble)
        assertTrue(shake <= 0.06f)
    }

    @Test fun `glitch only bursts in a short window of each cycle`() {
        val seed = 0f
        val quiet = (0L until 2_000L step 50).count { t ->
            StageGlyphMotion.sustain(StageGlyphMotion.GLITCH, t, 0, seed, false) == GlyphPose()
        }
        assertTrue(quiet > 30)
    }

    @Test fun `seeds stay within zero and one`() {
        for (i in 0 until 200) {
            val seed = StageGlyphMotion.seed(i, 99)
            assertTrue(seed in 0f..1f)
        }
    }
}
