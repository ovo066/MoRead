package com.mozhi.reader.feature.stats

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.StatsWidget
import com.mozhi.reader.core.datastore.StatsWidgets
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag

@Composable
internal fun StatsWidgetsSheet(
    settings: StatsWidgets, onVisible: (StatsWidget, Boolean) -> Unit, onMove: (StatsWidget, Int) -> Unit,
    onReset: () -> Unit, onDismiss: () -> Unit
) {
    NavigationSheet(onDismiss, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.scrim.copy(alpha = .45f)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("调整统计组件", style = MaterialTheme.typography.titleLarge)
                    Text("选择显示内容，用箭头调整顺序", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭组件设置") }
            }
            val list = rememberLazyListState()
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(list).testTag("stats-widgets-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(settings.order, key = { _, widget -> widget.name }) { index, widget ->
                    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(widget.title, style = MaterialTheme.typography.titleSmall)
                                    Text(widget.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(widget !in settings.hidden, onCheckedChange = { onVisible(widget, it) }, modifier = Modifier.testTag("widget-toggle-${widget.name}"))
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                IconButton(onClick = { onMove(widget, -1) }, enabled = index > 0) {
                                    Icon(Icons.Outlined.ArrowUpward, "上移${widget.title}", Modifier.size(18.dp))
                                }
                                IconButton(onClick = { onMove(widget, 1) }, enabled = index < settings.order.lastIndex) {
                                    Icon(Icons.Outlined.ArrowDownward, "下移${widget.title}", Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                item { TextButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) { Text("恢复默认组件与顺序") } }
            }
        }
    }
}
