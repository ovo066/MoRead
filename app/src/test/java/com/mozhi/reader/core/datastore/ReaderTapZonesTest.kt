package com.mozhi.reader.core.datastore

import org.junit.Assert.*
import org.junit.Test

class ReaderTapZonesTest {
    @Test fun allTargetsUseSameProportionsInPortraitAndLandscape() {
        listOf(400f to 900f, 900f to 400f).forEach { (w, h) ->
            for (row in 0..2) for (col in 0..2) {
                val y = (0.08f + (row + 0.5f) * 0.84f / 3) * h
                assertEquals(row * 3 + col, ReaderTapZones.indexAt((col + 0.5f) * w / 3, y, w, h))
            }
            assertEquals(9, ReaderTapZones.indexAt(0f, 0f, w, h))
            assertEquals(10, ReaderTapZones.indexAt(w, 0f, w, h))
            assertEquals(11, ReaderTapZones.indexAt(0f, h, w, h))
            assertEquals(12, ReaderTapZones.indexAt(w, h, w, h))
        }
    }
    @Test fun invalidOrMenuLessConfigurationsFallBackAndValidOnesRoundTrip() {
        val changed = ReaderTapZones().withAction(0, ReaderTapAction.BOOKMARKS)
        assertEquals(changed, ReaderTapZones.decode(changed.encode()))
        assertNull(ReaderTapZones.decode(null))
        assertNull(ReaderTapZones.decode("future-action"))
        assertNull(ReaderTapZones.decode(List(13) { "NONE" }.joinToString(",")))
    }
}
