package com.mozhi.reader.feature.reader

import com.mozhi.reader.ui.components.MoReadBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.NavigationSheetEdge

/** Keep the page at its current size while changing typography, so the preview is the final layout. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderSettingsSheet(palette: ReaderPalette, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val color = palette.glassStrong.compositeOver(palette.background)
    if (rememberMoReadWindowWidth() == MoReadWindowWidth.EXPANDED) {
        NavigationSheet(onDismiss, color, palette.onBackground, Color.Transparent, expandedEdge = NavigationSheetEdge.END) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp).height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("阅读设置", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭阅读设置") }
                }
                Box(Modifier.weight(1f).navigationBarsPadding()) { content() }
            }
        }
    } else {
        MoReadBottomSheet(onDismissRequest = onDismiss, containerColor = color, contentColor = palette.onBackground,
            scrimColor = Color.Transparent, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { content() }
    }
}
