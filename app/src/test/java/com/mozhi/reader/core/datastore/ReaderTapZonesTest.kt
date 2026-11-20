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

    @Test fun presetsKeepCenterMenuRoundTripAndAreRecognized() {
        for (preset in ReaderTapPreset.entries) {
            val zones = ReaderTapZones.of(preset)
            assertEquals(13, zones.actions.size)
            assertEquals(ReaderTapAction.MENU, zones.actions[ReaderTapZones.CENTER])
            assertEquals(zones, ReaderTapZones.decode(zones.encode()))
            assertEquals(preset, zones.preset)
        }
        assertEquals(ReaderTapPreset.STANDARD, ReaderTapZones().preset)
        assertNull(ReaderTapZones().withAction(0, ReaderTapAction.BOOKMARKS).preset)
    }

    @Test fun fullScreenNextTurnsForwardEverywhereButTheCenter() {
        val zones = ReaderTapZones.of(ReaderTapPreset.FULL_SCREEN_NEXT)
        val (w, h) = 400f to 900f
        for (x in listOf(5f, 200f, 395f)) for (y in listOf(5f, 150f, 450f, 750f, 895f)) {
            val expected = if (ReaderTapZones.indexAt(x, y, w, h) == ReaderTapZones.CENTER) ReaderTapAction.MENU else ReaderTapAction.NEXT_PAGE
            assertEquals(expected, zones.actionAt(x, y, w, h))
        }
    }

    @Test fun leftHandMirrorsTheStandardSides() {
        val zones = ReaderTapZones.of(ReaderTapPreset.LEFT_HAND)
        val (w, h) = 400f to 900f
        assertEquals(ReaderTapAction.NEXT_PAGE, zones.actionAt(10f, 450f, w, h))
        assertEquals(ReaderTapAction.PREVIOUS_PAGE, zones.actionAt(390f, 450f, w, h))
    }
}
