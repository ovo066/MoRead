package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.render.SpreadGeometry
import org.junit.Assert.*
import org.junit.Test

class ReaderTapDirectionTest {
    @Test
    fun `chrome target scales with density while painted leaves remain unchanged`() {
        val spread = SpreadGeometry(1760f, 1600f, 80f)
        assertEquals(PageTurnDirection.PREVIOUS, readerTapDirection(831.9f, 1760f, spread, 96f))
        assertNull(readerTapDirection(832f, 1760f, spread, 96f))
        assertNull(readerTapDirection(927.9f, 1760f, spread, 96f))
        assertEquals(PageTurnDirection.NEXT, readerTapDirection(928f, 1760f, spread, 96f))
        assertEquals(840f, spread.leafWidth, 0f)
        assertEquals(920f, spread.rightOriginX, 0f)
    }

    @Test
    fun `spread leaves turn outside the48dp chrome target around40dp gutter`() {
        val spread = SpreadGeometry(880f, 800f, 40f)
        assertEquals(PageTurnDirection.PREVIOUS, readerTapDirection(308f, 880f, spread))
        assertEquals(PageTurnDirection.NEXT, readerTapDirection(572f, 880f, spread))
        assertNull(readerTapDirection(440f, 880f, spread))
        assertEquals(PageTurnDirection.PREVIOUS, readerTapDirection(415.9f, 880f, spread))
        assertNull(readerTapDirection(416f, 880f, spread))
        assertNull(readerTapDirection(419f, 880f, spread))
        assertNull(readerTapDirection(460f, 880f, spread))
        assertNull(readerTapDirection(463.9f, 880f, spread))
        assertEquals(PageTurnDirection.NEXT, readerTapDirection(464f, 880f, spread))
        assertEquals(40f, spread.gutterPx, 0f)
    }

    @Test
    fun `single page keeps its original wide center chrome zone`() {
        assertNull(readerTapDirection(308f, 880f, null))
        assertNull(readerTapDirection(572f, 880f, null))
        assertEquals(PageTurnDirection.PREVIOUS, readerTapDirection(50f, 400f, null))
        assertEquals(PageTurnDirection.NEXT, readerTapDirection(350f, 400f, null))
        assertNull(readerTapDirection(200f, 400f, null))
    }
}
