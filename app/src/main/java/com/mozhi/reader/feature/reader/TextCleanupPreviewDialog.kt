package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.library.TextCleanupPreview

@Composable
internal fun TextCleanupPreviewDialog(preview: TextCleanupPreview, onDismiss: () -> Unit, onApply: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("净化效果预览") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("匹配 ${preview.matches} 处 · 改变 ${preview.changedChapters} 章")
            Text("确认后修改本书的本地阅读正文，原始导入文件保留。以下展示最多 12 章的首处变化片段。", style = MaterialTheme.typography.bodySmall)
            preview.examples.forEach { change ->
                HorizontalDivider()
                Text(change.title, style = MaterialTheme.typography.titleSmall)
                Text("原文", style = MaterialTheme.typography.labelMedium)
                Text(change.before, style = MaterialTheme.typography.bodySmall)
                Text("净化后", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(change.after.ifBlank { "（已移除）" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(enabled = preview.changedChapters > 0, onClick = onApply) { Text("确认应用到本书") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("返回规则") } })
}
