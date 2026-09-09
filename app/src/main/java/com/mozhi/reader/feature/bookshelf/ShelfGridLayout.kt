package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.unit.Density

/** Grid width excludes the page gutters. Phones retain their existing three covers. */
internal fun shelfGridColumnCount(availableWidthDp: Float, spacingDp: Float = 14f): Int {
    if (availableWidthDp < 560f) return 3
    return ((availableWidthDp + spacingDp) / (128f + spacingDp)).toInt().coerceIn(3, 6)
}

/** Uses the grid's actual constraints, including bounded pages and modal sheet widths. */
internal object ShelfGridCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val columns = shelfGridColumnCount(availableSize / density, spacing / density)
        val contentSize = (availableSize - spacing * (columns - 1)).coerceAtLeast(0)
        val cellSize = contentSize / columns
        val remainder = contentSize % columns
        return List(columns) { cellSize + if (it < remainder) 1 else 0 }
    }
}
