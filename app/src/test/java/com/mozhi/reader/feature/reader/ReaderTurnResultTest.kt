package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.render.SpreadGeometry
import org.junit.Assert.*
import org.junit.Test

class ReaderTurnResultTest {
    @Test
    fun `overdrag clamps settlement to physical leaf endpoints without a stationary tail`() {
        assertEquals(180f, clampHingeTouchX(PageTurnDirection.NEXT, -200f, 600f, 420f), 0f)
        assertEquals(600f, clampHingeTouchX(PageTurnDirection.NEXT, 700f, 600f, 420f), 0f)
        assertEquals(1020f, clampHingeTouchX(PageTurnDirection.PREVIOUS, 1400f, 600f, 420f), 0f)
        assertEquals(600f, clampHingeTouchX(PageTurnDirection.PREVIOUS, 100f, 600f, 420f), 0f)
    }

    @Test
    fun `obsolete TTS target is cancelled on navigation or source replacement even after commit`() {
        assertEquals(ReaderTurnResult.CANCELLED, readerFollowTurnResult(false, 2, 3, 1, 1))
        assertEquals(ReaderTurnResult.CANCELLED, readerFollowTurnResult(true, 2, 3, 1, 1))
        assertEquals(ReaderTurnResult.CANCELLED, readerFollowTurnResult(false, 2, 2, 1, 2))
        assertEquals(ReaderTurnResult.RETRYABLE, readerFollowTurnResult(false, 2, 2, 1, 1))
        assertEquals(ReaderTurnResult.COMMITTED, readerFollowTurnResult(true, 2, 2, 1, 1))
    }

    @Test
    fun `hinge settles at exactly one leaf and cancel returns to original face`() {
        val geometry = SpreadGeometry(880f, 800f, 40f)
        for (direction in PageTurnDirection.entries) {
            val start = 620f
            val end = flatPageTurnTargetX(direction, true, start, geometry.leafWidth)
            assertEquals(180f, SpreadLeafGeometry.fromTouch(direction, end, start, geometry).angle, 0f)
            assertEquals(geometry.leafWidth, kotlin.math.abs(end - start), 0f)
            val cancel = flatPageTurnTargetX(direction, false, start, geometry.leafWidth)
            assertEquals(start, cancel, 0f)
            assertEquals(0f, SpreadLeafGeometry.fromTouch(direction, cancel, start, geometry).angle, 0f)
        }
    }

    @Test
    fun `pulling past the initial point never flips a leaf in the opposite direction`() {
        val geometry = SpreadGeometry(880f, 800f, 40f)
        assertEquals(0f, SpreadLeafGeometry.fromTouch(PageTurnDirection.NEXT, 700f, 600f, geometry).angle, 0f)
        assertEquals(0f, SpreadLeafGeometry.fromTouch(PageTurnDirection.PREVIOUS, 500f, 600f, geometry).angle, 0f)
    }

    @Test
    fun `cover and slide keep their original full viewport travel`() {
        assertEquals(-280f, flatPageTurnTargetX(PageTurnDirection.NEXT, true, 600f, 880f), 0f)
        assertEquals(1480f, flatPageTurnTargetX(PageTurnDirection.PREVIOUS, true, 600f, 880f), 0f)
    }
}
