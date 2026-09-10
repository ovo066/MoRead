package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PullBookmarkGestureTest {
    @Test
    fun downwardPullArmsOnlyAfterThreshold() {
        val gesture = PullBookmarkGesture(8f, 88f)
        gesture.move(1f, 4f)
        assertFalse(gesture.ownsGesture)
        gesture.move(3f, 60f)
        assertTrue(gesture.ownsGesture)
        assertFalse(gesture.shouldAddOnRelease())
        gesture.move(5f, 88f)
        assertEquals(1f, gesture.progress)
        assertTrue(gesture.shouldAddOnRelease())
    }

    @Test
    fun reversingBeforeReleaseDisarmsWithoutTurningIntoATap() {
        val gesture = PullBookmarkGesture(8f, 88f)
        gesture.move(0f, 100f)
        gesture.move(0f, 50f)
        assertTrue(gesture.ownsGesture)
        assertFalse(gesture.shouldAddOnRelease())
        gesture.move(0f, -10f)
        assertEquals(0f, gesture.progress)
    }

    @Test
    fun horizontalAndUpwardGesturesRemainWithThePageTurnDriver() {
        listOf(20f to 2f, -20f to 2f, 0f to -20f, 20f to 20f).forEach { (x, y) ->
            val gesture = PullBookmarkGesture(8f, 88f)
            gesture.move(x, y)
            gesture.move(x, 150f)
            assertFalse(gesture.ownsGesture)
            assertFalse(gesture.shouldAddOnRelease())
        }
    }

    @Test
    fun largeHorizontalDriftCancelsAPullInsteadOfTurningAPage() {
        val gesture = PullBookmarkGesture(8f, 88f)
        gesture.move(0f, 40f)
        gesture.move(160f, 90f)
        gesture.move(0f, 100f)
        assertTrue(gesture.ownsGesture)
        assertEquals(0f, gesture.progress)
        assertFalse(gesture.shouldAddOnRelease())
    }

    @Test
    fun cancellationNeverAddsEvenAfterTheThreshold() {
        val gesture = PullBookmarkGesture(8f, 88f)
        gesture.move(0f, 100f)
        gesture.cancel()
        assertFalse(gesture.shouldAddOnRelease())
        assertEquals(0f, gesture.progress)
    }
}
