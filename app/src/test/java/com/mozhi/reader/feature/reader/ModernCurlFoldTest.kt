package com.mozhi.reader.feature.reader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class ModernCurlFoldTest {
    private val width = 1080f
    private val height = 2400f

    @Test fun aLevelPullLaysTheGrabEdgeUnderTheFinger() {
        val fold = modernCurlFold(600f, 1200f, 1200f, width, height, 1f)
        assertEquals(1f, fold.normalX, 1e-6f)
        assertEquals(0f, fold.normalY, 1e-6f)
        val finger = 600f / height
        assertEquals(width / height - finger, fold.reach, 1e-5f)
        // Reflecting the edge across the crease (half a turn of cylinder in between) reaches the finger.
        assertEquals(finger + (fold.reach - PI.toFloat() * fold.radius) / 2f, fold.fold, 1e-5f)
    }

    @Test fun cornerGrabsLeanTheCreaseSymmetricallyTowardTheLiftedCorner() {
        val bottom = modernCurlFold(600f, 2200f, 2300f, width, height, 1f)
        val top = modernCurlFold(600f, 200f, 100f, width, height, 1f)
        assertTrue(bottom.normalY > .05f)
        assertEquals(-bottom.normalY, top.normalY, 1e-5f)
        assertEquals(bottom.fold - top.fold, bottom.normalY * 1f, 1e-4f)
        assertEquals(1f, hypot(bottom.normalX, bottom.normalY), 1e-5f)
    }

    @Test fun aSteepPullCannotSwingTheSheetLooseFromTheBinding() {
        // Unclamped, folding the mid-edge onto this finger would lean the crease past 60 degrees.
        val fold = modernCurlFold(500f, 2300f, 1200f, width, height, 1f)
        assertTrue(abs(fold.normalY) < .3f)
    }

    @Test fun theSheetStraightensAndTheShadowIsGoneAsTheTurnCompletes() {
        val flat = modernCurlFold(width, 1200f, 1200f, width, height, 1f)
        assertEquals(0f, flat.progress, 0f)
        assertEquals(0f, flat.shadow, 0f)
        val lifted = modernCurlFold(300f, 1500f, 1200f, width, height, 1f)
        assertEquals(MODERN_CURL_SHADOW, lifted.shadow, 1e-6f)
        assertTrue(lifted.castShadowAt(0f) > lifted.castShadowAt(lifted.shadowSpread))
        assertTrue(lifted.castShadowAt(lifted.shadowSpread * 6f) < lifted.shadow * .01f)
        val landing = modernCurlFold(-width * .97f, 1500f, 1200f, width, height, 1f)
        assertEquals(0f, landing.shadow, 0f)
        assertEquals(0f, landing.normalY, 1e-3f)
        assertEquals(1f, modernCurlFold(-width, 1200f, 1200f, width, height, 1f).progress, 0f)
    }

    @Test fun curlRadiusSettingIsClampedAndScalesTheCylinder() {
        val normal = modernCurlFold(500f, 1200f, 1200f, width, height, 1f)
        val wide = modernCurlFold(500f, 1200f, 1200f, width, height, 5f)
        assertEquals(normal.radius * 1.8f, wide.radius, 1e-6f)
        assertEquals(normal.radius * .6f, modernCurlFold(500f, 1200f, 1200f, width, height, 0f).radius, 1e-6f)
    }

    @Test fun theSheetCountsAsGoneOnlyPastTheLastFrameThatStillShowsIt() {
        // Last visible sweep progress measured by rendering the shader with the smallest radius.
        for ((size, lastVisible) in listOf(Pair(1080f to 2400f, .93f), Pair(2400f to 1080f, .98f), Pair(1600f to 2560f, .94f))) {
            val (w, h) = size
            fun hiddenAt(p: Float) = modernCurlSheetHidden(w - 2f * w * p, h / 2f, h / 2f, w, h)
            assertFalse(hiddenAt(lastVisible))
            assertTrue(hiddenAt(1f))
            assertFalse(hiddenAt(.5f))
        }
    }
}
