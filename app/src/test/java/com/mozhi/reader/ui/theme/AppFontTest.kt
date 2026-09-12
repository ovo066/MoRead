package com.mozhi.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.*
import org.junit.Test

class AppFontTest {
    @Test fun applyingUiFontPreservesSizesAndWeightsAndDefaultIsUnchanged() {
        assertSame(MoReadTypography, MoReadTypography.withAppFont(null))
        val custom = MoReadTypography.withAppFont(FontFamily.Monospace)
        assertEquals(FontFamily.Monospace, custom.bodyLarge.fontFamily)
        assertEquals(FontFamily.Monospace, custom.displaySmall.fontFamily)
        assertEquals(FontFamily.Monospace, custom.labelSmall.fontFamily)
        assertEquals(MoReadTypography.headlineLarge.fontSize, custom.headlineLarge.fontSize)
        assertEquals(MoReadTypography.titleMedium.fontWeight, custom.titleMedium.fontWeight)
        assertEquals(MoReadTypography.bodyLarge.lineHeight, custom.bodyLarge.lineHeight)
    }
}
