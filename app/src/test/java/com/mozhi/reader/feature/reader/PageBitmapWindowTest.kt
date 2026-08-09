package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.PageTurnAnimation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBitmapWindowTest {

    @Test
    fun `next turn reuses previous buffer for new next page`() {
        val rotated = rotatePageWindow("previous", "current", "next", PageTurnDirection.NEXT)

        assertEquals("current", rotated.previous)
        assertEquals("next", rotated.current)
        assertNull(rotated.next)
        assertEquals("previous", rotated.reusable)
    }

    @Test
    fun `previous turn reuses next buffer for new previous page`() {
        val rotated = rotatePageWindow("previous", "current", "next", PageTurnDirection.PREVIOUS)

        assertNull(rotated.previous)
        assertEquals("previous", rotated.current)
        assertEquals("current", rotated.next)
        assertEquals("next", rotated.reusable)
    }

    @Test
    fun `only simulation embeds background in page snapshots`() {
        assertTrue(PageTurnAnimation.SIMULATION.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.COVER.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.SLIDE.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.NONE.usesEmbeddedPageBackground())
    }
}
