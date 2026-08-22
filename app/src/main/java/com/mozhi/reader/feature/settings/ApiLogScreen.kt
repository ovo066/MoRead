package com.mozhi.reader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.diag.ApiCallLogEntry
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「API 调用日志」二级页：开关 + 最近请求列表。用于排查连接问题 ——
 * 每条记录地址、状态、耗时与截断后的请求/响应预览；不含 API Key，仅存本机。
 */
@Composable
fun ApiLogScreen(
    onBack: () -> Unit,
    viewModel: ApiLogViewModel = hiltViewModel()
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    MoReadBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "API 调用日志",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                if (entries.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = "清空日志")
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    FrostedSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("记录 API 调用", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "出问题时用来排查；只存在本机，不含 API Key",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = enabled, onCheckedChange = viewModel::setEnabled)
                        }
                    }
                }

                if (entries.isEmpty()) {
                    item {
                        Text(
                            text = if (enabled) {
                                "还没有记录。用过 AI 之后会出现在这里。"
                            } else {
                                "开启开关后，新的 AI 请求会记录在这里。"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)
                        )
                    }
                } else {
                    item {
                        Text(
                            "最近 ${entries.size} 条 · 新的在前 · 点击展开详情",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                    val reversed = entries.asReversed()
                    itemsIndexed(
                        reversed,
                        key = { index, entry -> "${entry.timestamp}-${reversed.size - index}" }
                    ) { _, entry ->
                        ApiLogEntryCard(entry)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空日志") },
            text = { Text("将删除全部 ${entries.size} 条记录，且不可恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ApiLogEntryCard(entry: ApiCallLogEntry) {
    var expanded by rememberSaveable(entry.timestamp) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val statusColor = when {
        entry.succeeded -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }

    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Text(
                    text = entry.method,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Text(
                    text = entry.displayPath(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 6.dp)
                )
            }
            Row(
                modifier = Modifier.padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = entry.statusLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    fontWeight = FontWeight.Medium
                )
                if (entry.streaming) {
                    Text(
                        "流式",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${entry.durationMs} ms" + if (entry.streaming) "（至响应头）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (entry.requestBytes > 0) {
                    Text(
                        text = "↑ ${formatByteCount(entry.requestBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = timeFormat.format(Date(entry.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 9.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                DetailBlock(label = "地址", value = entry.url)
                entry.error?.let { DetailBlock(label = "错误", value = it, emphasized = true) }
                entry.responseType?.let { DetailBlock(label = "响应类型", value = it) }
                entry.requestPreview?.let { DetailBlock(label = "请求预览", value = it, mono = true) }
                entry.responsePreview?.let { DetailBlock(label = "响应预览", value = it, mono = true) }
                if (entry.streaming) {
                    Text(
                        "流式回复不记录正文。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(entry.dump()))
                    }) {
                        Icon(
                            Icons.Outlined.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Text("复制本条", modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailBlock(
    label: String,
    value: String,
    mono: Boolean = false,
    emphasized: Boolean = false
) {
    Row(modifier = Modifier.padding(bottom = 6.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontSize = if (mono) 11.sp else MaterialTheme.typography.bodySmall.fontSize,
            fontFamily = if (mono) FontFamily.Monospace else null,
            color = if (emphasized) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
    }
}

private fun ApiCallLogEntry.displayPath(): String {
    val withoutScheme = url.substringAfter("://")
    return withoutScheme.ifBlank { url }
}

private fun ApiCallLogEntry.statusLabel(): String = when {
    error != null -> "网络失败"
    status in 200..299 -> "$status 成功"
    status > 0 -> "$status 失败"
    else -> "未知"
}

private fun ApiCallLogEntry.dump(): String = buildString {
    appendLine("${timeFormat.format(Date(timestamp))} $method $url")
    appendLine(statusLabel() + " · ${durationMs}ms" + if (streaming) " · 流式" else "")
    error?.let { appendLine("错误：$it") }
    requestPreview?.let {
        appendLine("--- 请求 ---")
        appendLine(it)
    }
    responsePreview?.let {
        appendLine("--- 响应 ---")
        appendLine(it)
    }
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}

private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT)
