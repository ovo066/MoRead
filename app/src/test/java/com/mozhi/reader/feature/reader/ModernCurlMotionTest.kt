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
        assertTrue(modernCurlShouldComplete(.1f, -velocity.velocity(62), 600f, 1f))
        assertEquals(0f, velocity.velocity(150), 0f)
        assertFalse(modernCurlShouldComplete(.1f, -velocity.velocity(150), 600f, 1f))
    }

    @Test fun duplicateTimestampsStayFiniteAndARecentReverseFlickOverridesDistance() {
        val velocity = ModernCurlVelocity()
        velocity.reset(0, 300f)
        velocity.add(0, 280f)
        assertEquals(0f, velocity.velocity(0), 0f)
        velocity.add(200, 30f)
        velocity.add(230, 120f)
        assertEquals(3000f, velocity.velocity(231), .01f)
        assertFalse(modernCurlShouldComplete(.4f, -velocity.velocity(231), 600f, 1f))
    }

    @Test fun releaseDecisionScalesWithDensityAndDurationHasUsefulBounds() {
        for (density in listOf(1f, 2f, 3.5f)) {
            assertTrue(modernCurlShouldComplete(.05f, 900f * density, 600f * density, density))
            assertFalse(modernCurlShouldComplete(.05f, 400f * density, 600f * density, density))
            assertTrue(modernCurlShouldComplete(.3f, 0f, 600f * density, density))
            assertFalse(modernCurlShouldComplete(.7f, -900f * density, 600f * density, density))
        }
        assertEquals(140, modernCurlSettleDuration(100f, 5000f, 600f))
        assertEquals(420, modernCurlSettleDuration(590f, 0f, 600f))
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
