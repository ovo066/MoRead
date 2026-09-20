package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth

internal enum class NavigationSheetEdge { BOTTOM, START, END }
internal enum class CompactNavigationPresentation { BOTTOM_SHEET, PAGE, IMMERSIVE_PAGE }

/**
 * Navigation pages own their scrolling. Fix the CONTENT viewport height and disable sheet gestures
 * so list overscroll/fling cannot move the page's coordinate system. Never constrain the modal's
 * modifier height: Material uses those constraints as the full window when calculating anchors,
 * so a fractional outer height leaves an equally sized gap below the sheet.
 * Children fill this stable viewport, keep independent lazy states and use blockSheetDrag(state).
 * Always provide an explicit close button; back and scrim dismissal remain available.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NavigationSheet(
    onDismissRequest: () -> Unit, containerColor: Color, contentColor: Color, scrimColor: Color,
    contentHeightFraction: Float = 0.94f,
    expandedEdge: NavigationSheetEdge = NavigationSheetEdge.END,
    compactPresentation: CompactNavigationPresentation = CompactNavigationPresentation.BOTTOM_SHEET,
    content: @Composable () -> Unit
) {
    val sidePanel = expandedEdge != NavigationSheetEdge.BOTTOM && rememberMoReadWindowWidth() == MoReadWindowWidth.EXPANDED
    if (sidePanel || compactPresentation != CompactNavigationPresentation.BOTTOM_SHEET) {
        Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false
        )) {
            ConfigureMoReadDialogWindow(showSystemBars = sidePanel || compactPresentation != CompactNavigationPresentation.IMMERSIVE_PAGE)
            val view = LocalView.current
            SideEffect { (view.parent as? DialogWindowProvider)?.window?.setDimAmount(0f) }
            BoxWithConstraints(Modifier.fillMaxSize().imePadding().testTag("navigation-overlay")) {
                if (sidePanel) Box(Modifier.fillMaxSize().background(scrimColor)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClickLabel = "关闭面板", onClick = onDismissRequest).testTag("navigation-scrim"))
                Surface(
                    modifier = Modifier.align(if (sidePanel && expandedEdge == NavigationSheetEdge.START) Alignment.CenterStart else Alignment.CenterEnd)
                        .width(if (sidePanel) minOf(440.dp, maxWidth * .48f) else maxWidth).fillMaxHeight()
                        .testTag(if (sidePanel) "navigation-sheet" else "navigation-page"),
                    color = containerColor, contentColor = contentColor, shadowElevation = 12.dp
                ) {
                    Box(Modifier.fillMaxSize().testTag("navigation-viewport")
                        .windowInsetsPadding(if (sidePanel) WindowInsets.safeDrawing.union(com.mozhi.reader.ui.stableNavigationInsets())
                            else WindowInsets(0, 0, 0, 0))) { content() }
                }
            }
        }
        return
    }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("navigation-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = containerColor,
        contentColor = contentColor,
        scrimColor = scrimColor
    ) { Box(Modifier.fillMaxWidth().fillMaxHeight(contentHeightFraction).testTag("navigation-viewport")) { content() } }
}
