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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.ReaderTapAction
import com.mozhi.reader.core.datastore.ReaderTapPreset
import com.mozhi.reader.core.datastore.ReaderTapZones
import com.mozhi.reader.ui.components.MoReadSegmented

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
                val presetLabels = mapOf(
                    ReaderTapPreset.STANDARD to stringResource(R.string.tap_preset_standard),
                    ReaderTapPreset.FULL_SCREEN_NEXT to stringResource(R.string.tap_preset_full_next),
                    ReaderTapPreset.LEFT_HAND to stringResource(R.string.tap_preset_left_hand),
                    ReaderTapPreset.TOP_BOTTOM to stringResource(R.string.tap_preset_top_bottom)
                )
                MoReadSegmented<ReaderTapPreset?>(
                    options = ReaderTapPreset.entries,
                    selected = draft.preset,
                    onSelect = { preset -> if (preset != null) encoded = ReaderTapZones.of(preset).encode() },
                    modifier = Modifier.testTag("tap-zone-presets")
                ) { presetLabels[it].orEmpty() }
                Text(
                    stringResource(when (draft.preset) {
                        ReaderTapPreset.STANDARD -> R.string.tap_preset_standard_hint
                        ReaderTapPreset.FULL_SCREEN_NEXT -> R.string.tap_preset_full_next_hint
                        ReaderTapPreset.LEFT_HAND -> R.string.tap_preset_left_hand_hint
                        ReaderTapPreset.TOP_BOTTOM -> R.string.tap_preset_top_bottom_hint
                        null -> R.string.tap_preset_custom_hint
                    }),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
