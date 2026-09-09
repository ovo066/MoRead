package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity

internal data class ReaderSafeInsets(val topPx: Float, val bottomPx: Float)

/**
 * Immersive reading hides system chrome, not the page's safe area. Keep stable system-bar
 * insets and physical cutouts in both reading modes so returning from chat cannot collapse
 * the top margin or repeatedly reflow text during a visibility animation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun readerSafeInsets(): ReaderSafeInsets {
    val density = LocalDensity.current
    val statusBars = WindowInsets.statusBarsIgnoringVisibility
    val navigationBars = WindowInsets.navigationBarsIgnoringVisibility
    val cutout = WindowInsets.displayCutout
    return ReaderSafeInsets(
        topPx = maxOf(statusBars.getTop(density), cutout.getTop(density)).toFloat(),
        bottomPx = maxOf(navigationBars.getBottom(density), cutout.getBottom(density)).toFloat()
    )
}
