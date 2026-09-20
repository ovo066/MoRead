package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag

@Composable
internal fun ReaderKeySettingsControl(settings: ReaderSettings, palette: ReaderPalette, actions: ReaderBehaviorActions) {
    var configuring by remember { mutableStateOf(false) }
    TypographySwitchRow("按键翻页", "音量键、翻页器和键盘均可自定义", settings.volumeKeysPageTurn, palette, actions.onVolumeKeysPageTurnChange)
    TypographyNavRow("自定义按键", "上一页 ${settings.physicalKeyBindings.count { it.action == ReaderKeyAction.PREVIOUS_PAGE }} 个 · 下一页 ${settings.physicalKeyBindings.count { it.action == ReaderKeyAction.NEXT_PAGE }} 个", palette) {
        configuring = true
    }
    if (configuring) ReaderPhysicalKeysSheet(settings, palette, { configuring = false }, actions.onVolumeKeysPageTurnChange, actions.onPhysicalKeyBindingsChange)
}

@Composable
internal fun ReaderPhysicalKeysSheet(settings: ReaderSettings, palette: ReaderPalette, onDismiss: () -> Unit,
    onEnabledChange: (Boolean) -> Unit, onBindingsChange: (List<ReaderKeyBinding>) -> Unit) {
    var recording by remember { mutableStateOf<ReaderKeyAction?>(null) }
    val scroll = rememberLazyListState()
    NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim) {
        Column(Modifier.fillMaxSize().testTag("physical-keys-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text("按键翻页", style = MaterialTheme.typography.titleLarge)
                    Text("按你的习惯，一键翻到上一页或下一页", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭按键设置") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("启用按键翻页", style = MaterialTheme.typography.titleSmall)
                    Text(if (settings.volumeKeysPageTurn) "仅在阅读正文时生效" else "配置会保存，开启后生效", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
                Switch(settings.volumeKeysPageTurn, onEnabledChange, Modifier.testTag("physical-keys-enabled"))
            }
            HorizontalDivider(color = palette.glassBorder)
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(scroll).testTag("physical-keys-list"),
                contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "volume-presets") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("音量键快捷设置", style = MaterialTheme.typography.labelLarge, color = palette.muted)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (reversed in listOf(false, true)) {
                                val expected = ReaderKeyBindings.volumePreset(emptyList(), reversed)
                                val selected = expected.all { it in settings.physicalKeyBindings }
                                FilterChip(selected, { onBindingsChange(ReaderKeyBindings.volumePreset(settings.physicalKeyBindings, reversed)) },
                                    label = { Text(if (reversed) "音量加 → 下一页" else "音量加 → 上一页", style = MaterialTheme.typography.labelMedium) },
                                    modifier = Modifier.weight(1f).testTag("volume-preset-$reversed"))
                            }
                        }
                        Text("音量减会对应切换到另一个方向，其他按键保留。", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                    }
                }
                for (action in ReaderKeyAction.entries) {
                    item(key = "header-$action") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(action.label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { recording = action }, modifier = Modifier.testTag("record-$action")) {
                                Icon(Icons.Outlined.Keyboard, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("录制按键")
                            }
                        }
                    }
                    val bindings = settings.physicalKeyBindings.filter { it.action == action }
                    if (bindings.isEmpty()) item(key = "empty-$action") {
                        Text("尚未绑定，录制一个按键即可使用", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                    }
                    items(bindings, key = { "key-${it.key.keyCode}-${it.key.modifiers}-${it.key.scanCode}" }) { binding ->
                        Surface(shape = RoundedCornerShape(16.dp), color = palette.glass, border = BorderStroke(.5.dp, palette.glassBorder)) {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Keyboard, null, tint = palette.muted, modifier = Modifier.size(22.dp))
                                Text(binding.key.displayName(), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyLarge)
                                IconButton(onClick = { onBindingsChange(settings.physicalKeyBindings.filterNot { it.key == binding.key }) }) {
                                    Icon(Icons.Outlined.Close, "移除 ${binding.key.displayName()}", tint = palette.muted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                item(key = "help") {
                    Text("每个方向可绑定多个按键。单次按下翻一页，长按不会连翻；移除绑定后恢复按键原功能。", modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall, color = palette.muted)
                }
            }
            HorizontalDivider(color = palette.glassBorder)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onBindingsChange(ReaderKeyBindings.DEFAULT) }) { Text("恢复默认") }
                FilledTonalButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
    recording?.let { action ->
        ReaderPhysicalKeyCaptureDialog(action, settings.physicalKeyBindings, { recording = null }) { key ->
            onBindingsChange(ReaderKeyBindings.assign(settings.physicalKeyBindings, key, action))
            recording = null
        }
    }
}

@Composable
internal fun ReaderPhysicalKeyCaptureDialog(action: ReaderKeyAction, bindings: List<ReaderKeyBinding>, onDismiss: () -> Unit,
    onSave: (ReaderPhysicalKey) -> Unit) {
    var candidate by remember { mutableStateOf<ReaderPhysicalKey?>(null) }
    var held by remember { mutableStateOf(false) }
    val capture = remember { ReaderKeyCaptureWindow() }
    val dismiss = { capture.close(); onDismiss() }
    val conflict = bindings.firstOrNull { it.key == candidate && it.action != action }
    AlertDialog(onDismissRequest = dismiss, icon = { Icon(Icons.Outlined.Keyboard, null) },
        title = { Text("录制${action.label}按键") },
        text = {
            CapturePhysicalKeyEvents(capture) { key, pressed -> candidate = key; held = pressed }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("按下音量键、翻页器按钮或键盘按键。")
                Surface(modifier = Modifier.fillMaxWidth().testTag("recorded-key"), shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(candidate?.displayName() ?: "等待按键…", style = MaterialTheme.typography.titleLarge)
                        Text(if (held) "松开按键后即可保存" else if (candidate != null) "已识别 · 再按其他键可更换" else "支持 Ctrl、Shift 等组合键",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (conflict != null) Text("此按键当前用于${conflict.action.label}，保存后改为${action.label}。", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("key-binding-conflict"))
                Text("电源、主页、返回等系统键不支持录制。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { candidate?.let { capture.close(); onSave(it) } }, enabled = candidate != null && !held,
            modifier = Modifier.testTag("save-recorded-key")) { Text(if (conflict != null) "改为${action.label}" else "保存") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable
private fun CapturePhysicalKeyEvents(capture: ReaderKeyCaptureWindow, captured: (ReaderPhysicalKey, Boolean) -> Unit) {
    val view = LocalView.current
    val latest by rememberUpdatedState(captured)
    DisposableEffect(view, capture) {
        // Dialogs own a separate window; Activity.dispatchKeyEvent cannot record their volume keys.
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) capture.attach(window) { key, pressed -> latest(key, pressed) }
        onDispose { capture.close() }
    }
}
