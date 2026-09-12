package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag

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
    content: @Composable () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.testTag("navigation-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        dragHandle = null,
        containerColor = containerColor,
        contentColor = contentColor,
        scrimColor = scrimColor
    ) { Box(Modifier.fillMaxWidth().fillMaxHeight(0.94f).testTag("navigation-viewport")) { content() } }
}
