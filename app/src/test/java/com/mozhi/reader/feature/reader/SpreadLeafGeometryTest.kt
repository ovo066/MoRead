package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.render.SpreadGeometry
import org.junit.Assert.*
import org.junit.Test

class SpreadLeafGeometryTest {
    private val spread = SpreadGeometry(880f, 800f, 40f)
    private fun pose(progress: Float, direction: PageTurnDirection = PageTurnDirection.NEXT): SpreadLeafGeometry =
        SpreadLeafGeometry.fromTouch(direction,
            (if (direction == PageTurnDirection.NEXT) -1f else 1f) * spread.leafWidth * progress,
            0f, spread)

    @Test
    fun `angle switches from front to back at ninety degrees`() {
        assertEquals(0f, pose(0f).angle, 0f)
        assertFalse(pose(0.49f).showBack)
        assertTrue(pose(0.5f).showBack)
        assertEquals(180f, pose(1f).angle, 0f)
        assertEquals(180f, pose(4f).angle, 0f)
    }

    @Test
    fun `forward front starts right and back finishes exactly on target left`() {
        assertArrayEquals(floatArrayOf(460f, 0f, 880f, 0f, 880f, 800f, 460f, 800f), pose(0f).dstQuad, 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 420f, 0f, 420f, 800f, 0f, 800f), pose(1f).dstQuad, 0.001f)
        assertTrue(pose(0.25f).dstQuad[0] > spread.spineX)
        assertTrue(pose(0.75f).dstQuad[2] < spread.spineX)
        assertTrue(pose(0.25f).shadowAlpha > 0f)
        assertEquals(0f, pose(1f).shadowAlpha, 0.001f)
    }

    @Test
    fun `backward leaf geometry mirrors forward around the spine`() {
        for (progress in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val next = pose(progress)
            val previous = pose(progress, PageTurnDirection.PREVIOUS)
            assertEquals(spread.paneWidth - next.dstQuad[2], previous.dstQuad[0], 0.001f)
            assertEquals(spread.paneWidth - next.dstQuad[0], previous.dstQuad[2], 0.001f)
            assertEquals(next.showBack, previous.showBack)
        }
    }
}
