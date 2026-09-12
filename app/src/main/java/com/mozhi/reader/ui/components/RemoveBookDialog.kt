package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
fun RemoveBookDialog(title: String, onDismiss: () -> Unit, onConfirm: (deleteRecords: Boolean) -> Unit) {
    var deleteRecords by remember(title) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("移除本机书籍正文、解析资源、AI 索引和听书缓存。原始导入来源不会被删除。")
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)
                    .toggleable(deleteRecords, role = Role.Checkbox, onValueChange = { deleteRecords = it }),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteRecords, onCheckedChange = null)
                    Text("同时删除个人记录", modifier = Modifier.weight(1f))
                }
                Text(
                    if (deleteRecords) "统计、进度、书签、笔记、批注、对话、附件和 AI 插图也将永久删除，无法撤销。建议先备份或导出。"
                    else "默认保留统计、进度、书签、笔记、批注、对话及 AI 插图。可在“存储与数据 → 保留的阅读记录”中查看或彻底删除；重新导入不会自动合并记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (deleteRecords) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("书库伴读的跨书对话独立保留，请到书库伴读的历史中单独删除。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(deleteRecords) }) {
            Text(if (deleteRecords) "永久删除全部" else "移除正文，保留记录")
        } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
