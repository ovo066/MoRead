package com.mozhi.reader.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class MoReadMetricsTest {
    @Test fun standardKeepsExistingSilhouettesAndExpressiveIncreasesTheirSize() {
        listOf(10, 12, 14, 16, 18, 20, 22, 24, 26, 28, 32).forEach { radius ->
            assertEquals(radius.dp, MoReadMetrics.Standard.radiusFor(radius))
            assertTrue(MoReadMetrics.Expressive.radiusFor(radius) >= radius.dp)
        }
        assertTrue(MoReadMetrics.Expressive.radiusField < MoReadMetrics.Expressive.radiusRow)
        assertTrue(MoReadMetrics.Expressive.radiusRow < MoReadMetrics.Expressive.radiusCard)
        assertTrue(MoReadMetrics.Expressive.radiusCard < MoReadMetrics.Expressive.radiusSheet)
    }

    @Test fun largerControlsRetainTouchTargetsAndRoomForTheirIcons() {
        ShapeStyle.entries.forEach { style ->
            val metrics = MoReadMetrics.of(style)
            assertTrue(metrics.touchTarget >= 44.dp)
            assertTrue(metrics.buttonHeight >= metrics.touchTarget)
            assertTrue(metrics.rowMinHeight > metrics.iconTile)
            assertTrue(metrics.iconTile > metrics.iconGlyph)
            assertTrue(metrics.topBarHeight >= metrics.touchTarget)
        }
        assertTrue(MoReadMetrics.Expressive.sliderHeight > MoReadMetrics.Standard.sliderHeight)
    }
}
