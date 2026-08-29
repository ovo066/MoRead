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
    fun `committed turn refresh stays synchronous while animation is finishing`() {
        assertTrue(
            shouldRefreshPageWindowImmediately(
                turnRunning = true,
                relativePosition = 0,
                hasPreparedTurn = true
            )
        )
    }

    @Test
    fun `ordinary refresh waits until active turn finishes`() {
        assertFalse(
            shouldRefreshPageWindowImmediately(
                turnRunning = true,
                relativePosition = 0,
                hasPreparedTurn = false
            )
        )
        assertFalse(
            shouldRefreshPageWindowImmediately(
                turnRunning = true,
                relativePosition = 1,
                hasPreparedTurn = true
            )
        )
    }

    @Test
    fun `refresh is immediate when no turn is running`() {
        assertTrue(
            shouldRefreshPageWindowImmediately(
                turnRunning = false,
                relativePosition = 1,
                hasPreparedTurn = false
            )
        )
    }

    @Test
    fun `only simulation embeds background in page snapshots`() {
        assertTrue(PageTurnAnimation.SIMULATION.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.COVER.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.SLIDE.usesEmbeddedPageBackground())
        assertFalse(PageTurnAnimation.NONE.usesEmbeddedPageBackground())
    }
}
