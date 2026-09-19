package com.mozhi.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxMatchMode
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.ui.components.NoteStyleColorPalette

@Composable
internal fun SyntaxHighlightEditor(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: (ReaderSyntaxRule) -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("语法高亮", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "匹配引号、书名号等成对符号，并美化其中内容",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            Switch(
                checked = settings.syntaxHighlightEnabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent)
            )
        }
        settings.syntaxHighlightRules.forEach { rule ->
            Surface(
                onClick = { onEdit(rule) },
                color = palette.glass.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color(rule.colorArgb), CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(rule.name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            buildString {
                                if (rule.matchMode == ReaderSyntaxMatchMode.REGEX) {
                                    append("正则：")
                                    append(rule.pattern)
                                } else {
                                    append("${rule.startDelimiter} 内容 ${rule.endDelimiter}")
                                }
                                if (rule.font != ReaderSyntaxFont.INHERIT) {
                                    append(" · ")
                                    append(
                                        if (rule.font == ReaderSyntaxFont.CUSTOM) {
                                            settings.fontLibrary
                                                .firstOrNull { it.id == rule.fontAssetId }
                                                ?.displayName
                                                ?: "已删除字体"
                                        } else {
                                            rule.font.shortLabel()
                                        }
                                    )
                                }
                                if (rule.bold) append(" · 粗体")
                                if (rule.italic) append(" · 斜体")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(if (rule.enabled) "启用" else "停用", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("添加规则")
        }
    }
}

@Composable
internal fun SyntaxRuleEditorDialog(
    initial: ReaderSyntaxRule,
    fontLibrary: List<com.mozhi.reader.core.datastore.ReaderFontAsset>,
    onDismiss: () -> Unit,
    onSave: (ReaderSyntaxRule) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var start by remember { mutableStateOf(initial.startDelimiter) }
    var end by remember { mutableStateOf(initial.endDelimiter) }
    var color by remember { mutableStateOf(Color(initial.colorArgb)) }
    var includeDelimiters by remember { mutableStateOf(initial.includeDelimiters) }
    var underline by remember { mutableStateOf(initial.underline) }
    var matchMode by remember { mutableStateOf(initial.matchMode) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var ignoreCase by remember { mutableStateOf(initial.ignoreCase) }
    var backgroundEnabled by remember { mutableStateOf(initial.backgroundArgb != null) }
    var backgroundColor by remember {
        mutableStateOf(Color(initial.backgroundArgb ?: 0x33D06B42))
    }
    var syntaxFont by remember { mutableStateOf(initial.font) }
    var syntaxFontAssetId by remember { mutableStateOf(initial.fontAssetId) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var strikethrough by remember { mutableStateOf(initial.strikethrough) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    val valid = when (matchMode) {
        ReaderSyntaxMatchMode.DELIMITED -> start.isNotEmpty() && end.isNotEmpty()
        ReaderSyntaxMatchMode.REGEX -> pattern.isNotBlank() && pattern.length <= 256 &&
            runCatching { Regex(pattern) }.isSuccess
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "添加高亮规则" else "编辑高亮规则") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderSyntaxMatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = matchMode == mode,
                            onClick = { matchMode = mode },
                            label = { Text(if (mode == ReaderSyntaxMatchMode.DELIMITED) "成对符号" else "正则") }
                        )
                    }
                }
                if (matchMode == ReaderSyntaxMatchMode.DELIMITED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it.take(8) },
                            label = { Text("开始符号") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it.take(8) },
                            label = { Text("结束符号") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(256) },
                        label = { Text("正则表达式") },
                        supportingText = { Text("多行模式，匹配到的完整文本会应用样式") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SyntaxRuleSwitch("忽略大小写", ignoreCase) { ignoreCase = it }
                }
                Text("文字颜色", style = MaterialTheme.typography.labelMedium)
                NoteStyleColorPalette(
                    color = color,
                    onColorChange = { color = it }
                )
                SyntaxRuleSwitch("背景色", backgroundEnabled) { backgroundEnabled = it }
                if (backgroundEnabled) {
                    NoteStyleColorPalette(
                        color = backgroundColor,
                        onColorChange = { backgroundColor = it.copy(alpha = 0.24f) }
                    )
                }
                Text("字体", style = MaterialTheme.typography.labelMedium)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    items(ReaderSyntaxFont.entries.filter { it != ReaderSyntaxFont.CUSTOM }, key = { "builtin-${it.name}" }) { candidate ->
                        FilterChip(
                            selected = syntaxFont == candidate,
                            onClick = {
                                syntaxFont = candidate
                                syntaxFontAssetId = null
                            },
                            label = { Text(candidate.shortLabel()) }
                        )
                    }
                    items(fontLibrary, key = { it.id }) { font ->
                        com.mozhi.reader.ui.components.FontPreviewChoice(
                            font = font,
                            selected = syntaxFont == ReaderSyntaxFont.CUSTOM &&
                                syntaxFontAssetId == font.id,
                            onClick = {
                                syntaxFont = ReaderSyntaxFont.CUSTOM
                                syntaxFontAssetId = font.id
                            }
                        )
                    }
                }
                if (matchMode == ReaderSyntaxMatchMode.DELIMITED) {
                    SyntaxRuleSwitch("符号本身也着色", includeDelimiters) { includeDelimiters = it }
                }
                SyntaxRuleSwitch("粗体", bold) { bold = it }
                SyntaxRuleSwitch("斜体", italic) { italic = it }
                SyntaxRuleSwitch("添加下划线", underline) { underline = it }
                SyntaxRuleSwitch("添加删除线", strikethrough) { strikethrough = it }
                SyntaxRuleSwitch("启用这条规则", enabled) { enabled = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "高亮规则" },
                            startDelimiter = start,
                            endDelimiter = end,
                            colorArgb = color.toArgb(),
                            includeDelimiters = includeDelimiters,
                            underline = underline,
                            matchMode = matchMode,
                            pattern = pattern.trim(),
                            ignoreCase = ignoreCase,
                            backgroundArgb = backgroundColor.toArgb().takeIf { backgroundEnabled },
                            font = syntaxFont,
                            fontAssetId = syntaxFontAssetId,
                            bold = bold,
                            italic = italic,
                            strikethrough = strikethrough,
                            enabled = enabled
                        )
                    )
                }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
internal fun SyntaxRuleSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun ReaderSyntaxFont.shortLabel(): String = when (this) {
    ReaderSyntaxFont.INHERIT -> "跟随正文"
    ReaderSyntaxFont.SYSTEM -> "系统"
    ReaderSyntaxFont.SERIF -> "宋体"
    ReaderSyntaxFont.SANS_SERIF -> "黑体"
    ReaderSyntaxFont.MONOSPACE -> "等宽"
    ReaderSyntaxFont.CUSTOM -> "导入字体"
}
