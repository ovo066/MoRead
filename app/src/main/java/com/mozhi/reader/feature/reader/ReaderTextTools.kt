package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.validationError

@Composable
fun ReidentifyChaptersSheet(
    palette: ReaderPalette,
    onApply: (String) -> Unit
) {
    var customRegex by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("重新识别章节", style = MaterialTheme.typography.titleLarge)
        Text(
            "根据当前本地正文重新拆分 TXT 章节。留空会使用内置规则自动识别；填写正则后按自定义规则拆分。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted
        )
        OutlinedTextField(
            value = customRegex,
            onValueChange = { customRegex = it.take(1_000) },
            label = { Text("自定义章节标题正则（可选）") },
            placeholder = { Text("例如：^第[零一二三四五六七八九十百千万\\d]+[章节卷].*$") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "重新拆分后会回到第一章；原 EPUB 文件不会改写，阅读页使用新的本地章节结构。",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted
        )
        Button(
            onClick = { onApply(customRegex) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (customRegex.isBlank()) "自动重新识别" else "按自定义规则识别")
        }
    }
}

@Composable
fun TextReplacementRulesSheet(
    rules: List<ReaderTextReplacementRule>,
    palette: ReaderPalette,
    onAdd: () -> Unit,
    onEdit: (ReaderTextReplacementRule) -> Unit,
    onDelete: (ReaderTextReplacementRule) -> Unit,
    onToggle: (ReaderTextReplacementRule, Boolean) -> Unit,
    onRequestAi: () -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("文本清洗规则", style = MaterialTheme.typography.titleLarge)
        Text(
            "规则按顺序对本书每一章应用。可用正则删除 TXT 群聊广告、推广段落等杂讯；保存规则不会立即改写正文。",
            style = MaterialTheme.typography.bodySmall,
            color = palette.muted
        )
        if (rules.isEmpty()) {
            Surface(
                color = palette.glass,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "还没有清洗规则。可手动新增，或让 AI 根据需求分析当前书籍后生成草案。",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            rules.forEach { rule ->
                Surface(
                    color = palette.glass,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, palette.glassBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, top = 9.dp, end = 4.dp, bottom = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rule.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${if (rule.enabled) "启用" else "停用"} · ${rule.pattern}",
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { enabled -> onToggle(rule, enabled) }
                        )
                        IconButton(onClick = { onEdit(rule) }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "编辑规则", modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = { onDelete(rule) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除规则", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onAdd, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("新增规则", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onRequestAi, modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("AI 生成", modifier = Modifier.padding(start = 4.dp))
            }
        }
        Button(
            onClick = onApply,
            enabled = rules.any(ReaderTextReplacementRule::enabled),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("应用启用规则到本书")
        }
    }
}

@Composable
fun TextReplacementRuleEditorDialog(
    initial: ReaderTextReplacementRule,
    onDismiss: () -> Unit,
    onSave: (ReaderTextReplacementRule) -> Unit
) {
    var name by remember(initial) { mutableStateOf(initial.name) }
    var pattern by remember(initial) { mutableStateOf(initial.pattern) }
    var replacement by remember(initial) { mutableStateOf(initial.replacement) }
    var ignoreCase by remember(initial) { mutableStateOf(initial.ignoreCase) }
    val candidate = initial.copy(
        name = name,
        pattern = pattern,
        replacement = replacement,
        ignoreCase = ignoreCase
    )
    val validationError = candidate.validationError()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "新增清洗规则" else "编辑清洗规则") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it.take(1_000) },
                    label = { Text("匹配正则") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it.take(4_000) },
                    label = { Text("替换为（留空即删除）") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("忽略大小写", style = MaterialTheme.typography.bodyMedium)
                        Text("所有规则默认启用多行模式", style = MaterialTheme.typography.labelSmall)
                    }
                    Switch(checked = ignoreCase, onCheckedChange = { ignoreCase = it })
                }
                validationError?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = validationError == null, onClick = { onSave(candidate) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun AiTextReplacementRuleDialog(
    onDismiss: () -> Unit,
    onGenerate: (String) -> Unit
) {
    var requirement by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 生成清洗规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "描述想删除或替换的内容。AI 会跨章节取样分析当前书籍，并返回一条可编辑的正则规则草案。",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = requirement,
                    onValueChange = { requirement = it.take(2_000) },
                    label = { Text("需求") },
                    placeholder = { Text("例如：删除带 QQ 群号和加群提示的广告段落") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = requirement.isNotBlank(),
                onClick = { onGenerate(requirement) }
            ) { Text("分析并生成") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
