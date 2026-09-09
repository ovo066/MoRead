package com.mozhi.reader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowLayoutTest {
    @Test
    fun windowClassUsesAvailableWidthBoundaries() {
        assertEquals(MoReadWindowWidth.COMPACT, MoReadLayoutPolicy.windowWidth(0f))
        assertEquals(MoReadWindowWidth.COMPACT, MoReadLayoutPolicy.windowWidth(599.9f))
        assertEquals(MoReadWindowWidth.MEDIUM, MoReadLayoutPolicy.windowWidth(600f))
        assertEquals(MoReadWindowWidth.MEDIUM, MoReadLayoutPolicy.windowWidth(839.9f))
        assertEquals(MoReadWindowWidth.EXPANDED, MoReadLayoutPolicy.windowWidth(840f))
        assertEquals(MoReadWindowWidth.EXPANDED, MoReadLayoutPolicy.windowWidth(1280f))
    }

    @Test
    fun compactAndMediumKeepDockSpace() {
        assertEquals(124f, MoReadLayoutPolicy.rootBottomPaddingDp(MoReadWindowWidth.COMPACT))
        assertEquals(124f, MoReadLayoutPolicy.rootBottomPaddingDp(MoReadWindowWidth.MEDIUM))
        assertEquals(32f, MoReadLayoutPolicy.rootBottomPaddingDp(MoReadWindowWidth.EXPANDED))
    }

    @Test
    fun sidePaneRequiresExpandedWindow() {
        assertFalse(MoReadLayoutPolicy.allowsCompanionPane(839.9f))
        assertTrue(MoReadLayoutPolicy.allowsCompanionPane(840f))
    }

    @Test
    fun dualPageRequiresBothWindowAndRemainingReaderWidth() {
        assertFalse(MoReadLayoutPolicy.allowsDualPage(839f, 800f))
        assertFalse(MoReadLayoutPolicy.allowsDualPage(840f, 719.9f))
        assertTrue(MoReadLayoutPolicy.allowsDualPage(840f, 720f))
        val companion = MoReadLayoutPolicy.CompanionPaneWidthDp
        assertTrue(MoReadLayoutPolicy.allowsDualPage(1280f, 1280f - companion))
        assertFalse(MoReadLayoutPolicy.allowsDualPage(1024f, 1024f - companion))
    }
}
