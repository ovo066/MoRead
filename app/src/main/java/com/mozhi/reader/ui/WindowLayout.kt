package com.mozhi.reader.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp

/** Window constraints, not physical device type: split-screen follows the same rules. */
enum class MoReadWindowWidth { COMPACT, MEDIUM, EXPANDED }

object MoReadLayoutPolicy {
    const val MediumWidthDp = 600f
    const val ExpandedWidthDp = 840f
    const val LibraryMaxWidthDp = 1040f
    const val FormMaxWidthDp = 720f
    const val SheetMaxWidthDp = 640f
    const val NavigationRailWidthDp = 96f
    const val CompanionPaneWidthDp = 400f
    const val MinReaderPaneWidthDp = 720f

    fun windowWidth(widthDp: Float): MoReadWindowWidth = when {
        widthDp >= ExpandedWidthDp -> MoReadWindowWidth.EXPANDED
        widthDp >= MediumWidthDp -> MoReadWindowWidth.MEDIUM
        else -> MoReadWindowWidth.COMPACT
    }

    fun allowsCompanionPane(windowWidthDp: Float): Boolean =
        windowWidth(windowWidthDp) == MoReadWindowWidth.EXPANDED

    /** Width eligibility only; the reader additionally checks page mode and user preference. */
    fun allowsDualPage(windowWidthDp: Float, readerPaneWidthDp: Float): Boolean =
        allowsCompanionPane(windowWidthDp) && readerPaneWidthDp >= MinReaderPaneWidthDp

    fun rootBottomPaddingDp(width: MoReadWindowWidth): Float =
        if (width == MoReadWindowWidth.EXPANDED) 32f else 124f
}

/** Supplied once by the app root, before rail or reader side-pane space is removed. */
internal val LocalMoReadWindowWidthDp = compositionLocalOf<Dp?> { null }

@Composable
fun rememberMoReadWindowWidth(): MoReadWindowWidth {
    val widthDp = LocalMoReadWindowWidthDp.current?.value
        ?: LocalConfiguration.current.screenWidthDp.toFloat()
    return MoReadLayoutPolicy.windowWidth(widthDp)
}

/** Measure before navigation takes space; descendants must not mistake pane width for window width. */
@Composable
internal fun MoReadWindowLayout(content: @Composable BoxScope.(MoReadWindowWidth) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalMoReadWindowWidthDp provides maxWidth) {
            content(MoReadLayoutPolicy.windowWidth(maxWidth.value))
        }
    }
}
