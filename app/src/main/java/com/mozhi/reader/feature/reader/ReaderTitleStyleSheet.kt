package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.blockSheetDrag
import java.util.UUID

/** Shared named styles are reusable; the active reading theme holds the selected stable id. */
@Composable
internal fun ReaderTitleStyleSheet(settings: ReaderSettings, palette: ReaderPalette, onDismiss: () -> Unit, actions: ReaderLayoutActions) {
    var editing by remember { mutableStateOf<ReaderTitleStylePreset?>(null) }
    var deleting by remember { mutableStateOf<ReaderTitleStylePreset?>(null) }
    var renaming by remember { mutableStateOf<ReaderTitleStylePreset?>(null) }
    val scroll = rememberLazyListState()
    fun create(name: String = "自定义样式", style: ReaderTitleStyle = settings.titleStyle) {
        editing = ReaderTitleStylePreset(UUID.randomUUID().toString(), name, style.copy(presetId = null))
    }
    NavigationSheet(onDismiss, palette.glassStrong, palette.onBackground, palette.scrim) {
        Column(Modifier.fillMaxSize().testTag("title-style-sheet")) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("标题样式", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { create() }) { Icon(Icons.Outlined.Add, "新建标题样式") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭标题样式") }
            }
            Text("点选即应用，阅读主题会记住你的选择。", Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = palette.muted)
            LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(scroll).testTag("title-style-list"),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (settings.titleStyle.presetId == null) item(key = "unsaved") {
                    Surface(shape = RoundedCornerShape(18.dp), color = palette.accent.copy(alpha = .08f)) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("当前样式", style = MaterialTheme.typography.titleSmall)
                                Text("保存后可在不同主题间重复使用", style = MaterialTheme.typography.bodySmall, color = palette.muted)
                            }
                            TextButton(onClick = { create("我的标题样式") }) { Text("另存为") }
                        }
                    }
                }
                items(settings.titleStylePresets, key = { it.id }) { preset ->
                    val selected = settings.titleStyle.presetId == preset.id
                    var menu by remember(preset.id) { mutableStateOf(false) }
                    Surface(onClick = { actions.onTitleStyleChange(preset.style.copy(presetId = preset.id)) },
                        modifier = Modifier.fillMaxWidth().testTag("title-preset-${preset.id}"), shape = RoundedCornerShape(20.dp),
                        color = if (selected) palette.accent.copy(alpha = .08f) else palette.glass,
                        border = BorderStroke(if (selected) 1.5.dp else .5.dp, if (selected) palette.accent else palette.glassBorder)) {
                        Column {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(preset.name, style = MaterialTheme.typography.titleSmall)
                                    Text(if (selected) "使用中" else preset.style.resolved().font.shortLabel(),
                                        style = MaterialTheme.typography.labelSmall, color = if (selected) palette.accent else palette.muted)
                                }
                                if (selected) Icon(Icons.Outlined.CheckCircle, "已选择 ${preset.name}", tint = palette.accent, modifier = Modifier.size(20.dp))
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "管理 ${preset.name}", tint = palette.muted) }
                                    DropdownMenu(menu, { menu = false }) {
                                        DropdownMenuItem(text = { Text("编辑样式") }, onClick = { menu = false; editing = preset })
                                        DropdownMenuItem(text = { Text("复制为新样式") }, onClick = { menu = false; create("${preset.name} 副本", preset.style) })
                                        DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = preset })
                                        DropdownMenuItem(text = { Text("删除", color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; deleting = preset })
                                    }
                                }
                            }
                            TitleStylePreview(settings.copy(titleStyle = preset.style), palette, height = 104.dp)
                        }
                    }
                }
                item { Text("用于 TXT 和普通文本章首；EPUB 的 CSS、图片标题沿用原书精排。", style = MaterialTheme.typography.bodySmall, color = palette.muted) }
            }
        }
    }
    editing?.let { preset ->
        ReaderTitleStyleEditor(settings.copy(titleStyle = preset.style), palette, { editing = null }, actions,
            initialName = preset.name, onSaveNamed = { name, style -> actions.onSaveTitleStylePreset(preset.copy(name = name, style = style)) })
    }
    renaming?.let { preset ->
        var name by remember(preset.id) { mutableStateOf(preset.name) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("重命名样式") },
            text = { OutlinedTextField(name, { name = it.take(30) }, label = { Text("样式名称") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { actions.onSaveTitleStylePreset(preset.copy(name = name.trim())); renaming = null }, enabled = name.isNotBlank()) { Text("保存") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } })
    }
    deleting?.let { preset ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除“${preset.name}”？") },
            text = { Text("从样式库移除。正在使用它的阅读主题会保留当前外观，可稍后选择其他样式。") },
            confirmButton = { TextButton(onClick = { actions.onDeleteTitleStylePreset(preset.id); deleting = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
