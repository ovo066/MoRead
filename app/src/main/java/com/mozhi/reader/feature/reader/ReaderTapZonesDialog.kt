package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderTapAction
import com.mozhi.reader.core.datastore.ReaderTapZones

@Composable
internal fun ReaderTapZonesDialog(
    saved: ReaderTapZones?, onDismiss: () -> Unit, onSave: (ReaderTapZones) -> Unit
) {
    var encoded by rememberSaveable { mutableStateOf((saved ?: ReaderTapZones()).encode()) }
    // A draft may temporarily have no menu; only saving requires a reachable menu.
    val draft = ReaderTapZones(encoded.split(',').map(ReaderTapAction::valueOf))
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    ReaderFullscreenDialog(onDismiss = onDismiss) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text("操作区域", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { encoded = ReaderTapZones().encode() }) { Text("重置") }
                }
                Text("点击区域分配动作 · 横竖屏均按比例划分", style = MaterialTheme.typography.bodySmall)
                @Composable fun Cell(index: Int, label: String, modifier: Modifier) {
                    FilledTonalButton(onClick = { editing = index }, modifier = modifier.testTag("tap-zone-$index"),
                        shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(4.dp)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, style = MaterialTheme.typography.labelMedium)
                            Text(draft.actions[index].label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val gridHeight = maxOf(maxHeight, 660.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale)
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("tap-zone-grid")) {
                        Column(Modifier.fillMaxWidth().height(gridHeight), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(Modifier.fillMaxWidth().weight(ReaderTapZones.EDGE_FRACTION), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Cell(9, "页眉左侧", Modifier.weight(1f).fillMaxHeight())
                                Cell(10, "页眉右侧", Modifier.weight(1f).fillMaxHeight())
                            }
                            repeat(3) { row ->
                                Row(Modifier.fillMaxWidth().weight((1f - 2 * ReaderTapZones.EDGE_FRACTION) / 3), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    repeat(3) { col ->
                                        val index = row * 3 + col
                                        Cell(index, "区域 ${index + 1}", Modifier.weight(1f).fillMaxHeight())
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().weight(ReaderTapZones.EDGE_FRACTION), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Cell(11, "页脚左侧", Modifier.weight(1f).fillMaxHeight())
                                Cell(12, "页脚右侧", Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (!draft.hasMenu) Text("至少保留一个菜单区域", color = MaterialTheme.colorScheme.error)
                Button(onClick = { onSave(draft) }, enabled = draft.hasMenu, modifier = Modifier.fillMaxWidth()) { Text("完成配置") }
            }
        }
        editing?.let { index ->
            AlertDialog(onDismissRequest = { editing = null }, title = { Text("选择操作") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReaderTapAction.entries.forEach { action ->
                        TextButton(onClick = { encoded = draft.withAction(index, action).encode(); editing = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(action.label, Modifier.weight(1f))
                            RadioButton(selected = draft.actions[index] == action, onClick = null)
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { editing = null }) { Text("取消") } })
        }
    }
}
