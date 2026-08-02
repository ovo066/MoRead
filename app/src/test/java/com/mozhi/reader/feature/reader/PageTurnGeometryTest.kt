package com.mozhi.reader.feature.reader

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnGeometryTest {

    @Test
    fun `fold edge follows the finger one to one`() {
        val width = 1080f
        listOf(0f, 0.15f, 0.33f, 0.5f, 0.78f, 1f).forEach { travel ->
            val edge = simulationFoldEdgeX(width, simulationTouchX(width, travel))
            assertEquals(
                "travel=$travel should place the fold edge at the finger",
                width * (1f - travel),
                edge,
                0.5f
            )
        }
    }

    @Test
    fun `completed travel pulls the touch point clear of the viewport`() {
        val width = 1080f
        val touchX = simulationTouchX(width, 1f)
        assertTrue(
            "a fully committed turn must leave no fold on screen, was $touchX",
            touchX <= -width / 3f + 0.5f
        )
    }

    @Test
    fun `edge speed matches drag speed`() {
        val width = 1440f
        val first = simulationFoldEdgeX(width, simulationTouchX(width, 0.2f))
        val second = simulationFoldEdgeX(width, simulationTouchX(width, 0.4f))
        assertEquals(width * 0.2f, abs(first - second), 0.5f)
    }

    /**
     * The regression that flat page turns kept coming back through: grabbing near a page corner left
     * the lifted corner with almost no vertical offset, so the fold collapsed to a sliding edge.
     */
    @Test
    fun `every grab point produces a visible fold`() {
        val geometry = PageFoldGeometry()
        forEachGrab { cornerAtTop, touchYFraction, travel ->
            geometry.update(WIDTH, HEIGHT, travel, touchYFraction, cornerAtTop)
            val area = geometry.foldAreaFraction(WIDTH, HEIGHT)
            assertTrue(
                "cornerAtTop=$cornerAtTop touchY=$touchYFraction travel=$travel " +
                    "collapsed the fold to ${"%.2f".format(area * 100)}% of the page",
                area >= MIN_VISIBLE_FOLD
            )
        }
    }

    @Test
    fun `fold geometry stays finite for every grab point`() {
        val geometry = PageFoldGeometry()
        forEachGrab { cornerAtTop, touchYFraction, travel ->
            geometry.update(WIDTH, HEIGHT, travel, touchYFraction, cornerAtTop)
            assertTrue(
                "cornerAtTop=$cornerAtTop touchY=$touchYFraction travel=$travel went non-finite",
                geometry.isFinite()
            )
        }
    }

    @Test
    fun `fold grows as the page is pulled further`() {
        val geometry = PageFoldGeometry()
        listOf(true, false).forEach { cornerAtTop ->
            listOf(0.05f, 0.5f, 0.95f).forEach { touchYFraction ->
                var previous = -1f
                listOf(0.1f, 0.2f, 0.35f, 0.5f).forEach { travel ->
                    geometry.update(WIDTH, HEIGHT, travel, touchYFraction, cornerAtTop)
                    val area = geometry.foldAreaFraction(WIDTH, HEIGHT)
                    assertTrue(
                        "fold shrank at travel=$travel for touchY=$touchYFraction",
                        area > previous
                    )
                    previous = area
                }
            }
        }
    }

    @Test
    fun `fold depth is deepest when dragging away from the corner`() {
        val nearCorner = foldDepthFactor(HEIGHT, touchYFraction = 0.02f, cornerY = 0f)
        val farCorner = foldDepthFactor(HEIGHT, touchYFraction = 0.95f, cornerY = 0f)
        assertTrue("dragging away from the corner must curl deeper", farCorner > nearCorner)
        assertTrue("a corner grab must still curl", nearCorner >= 0.75f)
        assertEquals("the far side uses the full depth", 1f, farCorner, 0.001f)
    }

    private fun forEachGrab(block: (Boolean, Float, Float) -> Unit) {
        listOf(true, false).forEach { cornerAtTop ->
            listOf(0.02f, 0.1f, 0.3f, 0.5f, 0.7f, 0.9f, 0.98f).forEach { touchYFraction ->
                listOf(0.05f, 0.1f, 0.25f, 0.5f, 0.75f, 0.95f).forEach { travel ->
                    block(cornerAtTop, touchYFraction, travel)
                }
            }
        }
    }

    private companion object {
        const val WIDTH = 1080f
        const val HEIGHT = 2400f
        const val MIN_VISIBLE_FOLD = 0.02f
    }
}
