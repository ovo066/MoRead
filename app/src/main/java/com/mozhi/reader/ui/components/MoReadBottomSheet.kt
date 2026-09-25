package com.mozhi.reader.ui.components

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * All bottom sheets share this scroll boundary. Lists keep their ordinary scrolling and fling;
 * excess motion stops at the edge. Dragging the handle/header still moves and dismisses the sheet.
 * Size constraints belong to content, never the outer sheet (Material uses that as window height).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoReadBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetMaxWidth: Dp = BottomSheetDefaults.SheetMaxWidth,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit
) {
    MoReadOverlayTheme {
    ModalBottomSheet(onDismissRequest = onDismissRequest, modifier = modifier, sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), tonalElevation = 0.dp,
        sheetMaxWidth = sheetMaxWidth, containerColor = containerColor, contentColor = contentColor,
        scrimColor = scrimColor, dragHandle = dragHandle, sheetGesturesEnabled = true) {
        CompositionLocalProvider(LocalOverscrollFactory provides null) {
            // Existing pages add 16–24 dp; this shared gutter also keeps full-width controls off the edge.
            Column(Modifier.fillMaxWidth().containSheetScroll().padding(horizontal = 8.dp), content = content)
        }
    }
    }
}
