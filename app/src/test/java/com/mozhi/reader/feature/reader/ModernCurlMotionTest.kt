package com.mozhi.reader.feature.reader

import org.junit.Assert.*
import org.junit.Test

class ModernCurlMotionTest {
    @Test fun recentFitIgnoresOnePixelReversalAndExpiresAfterAHold() {
        val velocity = ModernCurlVelocity()
        velocity.reset(0, 300f)
        velocity.add(20, 270f)
        velocity.add(40, 240f)
        velocity.add(60, 210f)
        velocity.add(61, 211f)
        assertTrue(velocity.velocity(62) < -1300f)
        assertTrue(modernCurlShouldCommit(30f, -velocity.velocity(62), 600f, 1f))
        assertEquals(0f, velocity.velocity(150), 0f)
        assertFalse(modernCurlShouldCommit(30f, -velocity.velocity(150), 600f, 1f))
    }

    @Test fun duplicateTimestampsStayFiniteAndARecentReverseFlickOverridesDistance() {
        val velocity = ModernCurlVelocity()
        velocity.reset(0, 300f)
        velocity.add(0, 280f)
        assertEquals(0f, velocity.velocity(0), 0f)
        velocity.add(200, 30f)
        velocity.add(230, 120f)
        assertEquals(3000f, velocity.velocity(231), .01f)
        assertFalse(modernCurlShouldCommit(240f, -velocity.velocity(231), 600f, 1f))
    }

    @Test fun anyForwardFlickOrAQuarterOfTheWidthCommitsAtEveryDensity() {
        for (density in listOf(1f, 2f, 3.5f)) {
            val width = 400f * density
            assertTrue(modernCurlShouldCommit(10f * density, 160f * density, width, density))
            assertFalse(modernCurlShouldCommit(width * .2f, 100f * density, width, density))
            assertFalse(modernCurlShouldCommit(width * .24f, 0f, width, density))
            assertTrue(modernCurlShouldCommit(width * .26f, 0f, width, density))
            // A small slip back while lifting off keeps a long pull; a deliberate flick back does not.
            assertTrue(modernCurlShouldCommit(width * .8f, -150f * density, width, density))
            assertFalse(modernCurlShouldCommit(width * .8f, -450f * density, width, density))
        }
        assertEquals(140, modernCurlSettleDuration(100f, 5000f, 600f))
        assertEquals(420, modernCurlSettleDuration(590f, 0f, 600f))
    }

    @Test fun followerConvergesWithoutOvershootAtAnyFrameRate() {
        var at60 = 0f
        repeat(6) { at60 = modernCurlFollow(at60, 100f, 1000f / 60) }
        var at120 = 0f
        repeat(12) { at120 = modernCurlFollow(at120, 100f, 1000f / 120) }
        assertEquals(at60, at120, .01f)
        assertEquals(100f * (1f - kotlin.math.exp(-100f / MODERN_CURL_FOLLOW_MS)), at60, .01f)
        var x = 0f
        repeat(60) {
            x = modernCurlFollow(x, 100f, 16f)
            assertTrue(x <= 100f)
        }
        assertEquals(100f, x, .01f)
    }

    @Test fun easeLeavesAtTheRequestedSpeedAndArrivesAtRestWithoutOvershoot() {
        for (launch in listOf(0f, 1f, 2.5f, 3f)) {
            assertEquals(0f, modernCurlEase(0f, launch), 0f)
            assertEquals(1f, modernCurlEase(1f, launch), 1e-6f)
            var previous = 0f
            for (i in 1..100) {
                val value = modernCurlEase(i / 100f, launch)
                assertTrue(value >= previous - 1e-6f && value <= 1f + 1e-6f)
                previous = value
            }
            assertEquals(launch, modernCurlEase(.001f, launch) / .001f, .01f)
            assertEquals(0f, (1f - modernCurlEase(.999f, launch)) / .001f, .01f)
        }
    }

    @Test fun sweepTakesAThirdOfASecondAtMostAndLeavesAtTheFingersSpeed() {
        assertEquals(330f, ModernCurlSettle.create(300f, 200f, -300f, 200f, 300f, 0f).durationMillis, .01f)
        assertEquals(120f, ModernCurlSettle.create(280f, 200f, 300f, 200f, 300f, 0f).durationMillis, .01f)
        val fast = ModernCurlSettle.create(150f, 260f, -300f, 200f, 300f, 4000f)
        assertTrue(fast.durationMillis < 300f)
        fast.advance(8f)
        assertEquals(150f - 32f, fast.x(), 3f)
        assertTrue(fast.y() in 200f..260f)
        fast.advance(1000f)
        assertTrue(fast.finished)
        assertEquals(-300f, fast.x(), 0f)
        assertEquals(200f, fast.y(), 0f)
    }

    @Test fun grabAnchorsAreSymmetricContinuousAndKeepTheMiddleUnpinned() {
        for (y in listOf(0f, 20f, 199.9f, 200f, 300f, 400f, 580f, 600f)) {
            val anchor = modernCurlAnchorY(y, 600f)
            assertEquals(600f - anchor, modernCurlAnchorY(600f - y, 600f), .001f)
            assertTrue(anchor in 0f..600f)
        }
        assertEquals(300f, modernCurlAnchorY(300f, 600f), 0f)
        assertEquals(200f, modernCurlAnchorY(200f, 600f), .001f)
        assertTrue(modernCurlAnchorY(20f, 600f) < 2f)
    }
}
