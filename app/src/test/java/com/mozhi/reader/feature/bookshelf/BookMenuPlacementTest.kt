package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMenuPlacementTest {
    @Test
    fun bottomBookOpensUpwardWithoutEnteringTheDock() {
        val safe = Rect(16f, 40f, 384f, 676f)
        val result = placeBookMenu(Rect(80f, 550f, 180f, 700f), safe, Size(100f, 170f), Size(218f, 360f), 12f)
        assertContained(safe, result.menu)
        assertTrue(result.menu.bottom <= result.preview.top)
    }

    @Test
    fun topBookOpensBelow() {
        val safe = Rect(16f, 40f, 384f, 776f)
        val result = placeBookMenu(Rect(80f, 50f, 180f, 200f), safe, Size(100f, 170f), Size(218f, 360f), 12f)
        assertEquals(result.preview.bottom + 12f, result.menu.top)
        assertContained(safe, result.menu)
    }

    @Test
    fun landscapeUsesTheAvailableSideAndRespectsRailRelativeCoordinates() {
        val safe = Rect(16f, 24f, 760f, 376f)
        listOf(Rect(50f, 50f, 150f, 250f), Rect(600f, 50f, 700f, 250f)).forEach { anchor ->
            val result = placeBookMenu(anchor, safe, Size(120f, 220f), Size(218f, 320f), 12f)
            assertFalse(result.menu.overlaps(result.preview))
            assertContained(safe, result.menu)
        }
    }

    @Test
    fun smallWindowAllowsCoverOverlapButNeverHidesMenuActionsOutsideSafeBounds() {
        val safe = Rect(16f, 40f, 304f, 430f)
        val result = placeBookMenu(Rect(120f, 200f, 220f, 350f), safe, Size(120f, 220f), Size(218f, 380f), 12f)
        assertTrue(result.menu.overlaps(result.preview))
        assertContained(safe, result.menu)
        assertContained(safe, result.preview)
    }

    @Test
    fun oversizedMenuIsBoundedForScrollableLargeFontContent() {
        val safe = Rect(16f, 40f, 204f, 240f)
        val result = placeBookMenu(Rect(-100f, 800f, 0f, 1000f), safe, Size(142f, 400f), Size(218f, 900f), 12f)
        assertEquals(safe, result.menu)
        assertContained(safe, result.preview)
    }

    private fun assertContained(outer: Rect, inner: Rect) {
        assertTrue("$inner must fit in $outer", inner.left >= outer.left && inner.top >= outer.top &&
            inner.right <= outer.right && inner.bottom <= outer.bottom)
    }
}
