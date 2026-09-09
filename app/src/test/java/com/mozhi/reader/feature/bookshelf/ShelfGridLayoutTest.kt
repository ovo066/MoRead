package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelfGridLayoutTest {
    @Test
    fun phonesKeepThreeColumnsAndWideContentScalesToSix() {
        // Input excludes the existing 20dp gutters on either side.
        assertEquals(3, shelfGridColumnCount(320f))
        assertEquals(3, shelfGridColumnCount(559f))
        assertEquals(4, shelfGridColumnCount(560f))
        assertEquals(5, shelfGridColumnCount(744f))
        assertEquals(6, shelfGridColumnCount(1000f))
        assertEquals(6, shelfGridColumnCount(2000f))
    }

    @Test
    fun actualCellSizesFillAvailableSpaceIncludingRoundingAndDensity() {
        listOf(1f, 1.5f, 2f, 3f).forEach { density ->
            val available = (744f * density).toInt()
            val spacing = (14f * density).toInt()
            val cells = with(ShelfGridCells) {
                with(Density(density)) { calculateCrossAxisCellSizes(available, spacing) }
            }
            assertEquals(5, cells.size)
            assertEquals(available, cells.sum() + spacing * (cells.size - 1))
            assertTrue(cells.max() - cells.min() <= 1)
        }
    }
}
