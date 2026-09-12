package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.storage.BookStorageUsage
import com.mozhi.reader.core.storage.StorageCleanup
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.RemoveBookDialog

@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSpeechCache: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenFonts: () -> Unit,
    onOpenBook: (Long) -> Unit,
    viewModel: DataSettingsViewModel = hiltViewModel()
) {
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val working by viewModel.working.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var cleanup by remember { mutableStateOf<StorageCleanup?>(null) }
    var speechTarget by remember { mutableStateOf<BookStorageUsage?>(null) }
    var indexTarget by remember { mutableStateOf<BookEntity?>(null) }
    var removeTarget by remember { mutableStateOf<BookEntity?>(null) }
    var eraseTarget by remember { mutableStateOf<BookEntity?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    Box(Modifier.fillMaxSize()) {
        MoReadSecondaryPage(title = "存储与数据", onBack = onBack) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("本地数据总量", style = MaterialTheme.typography.labelLarge)
                            Text(usage?.let { formatBytes(it.totalBytes) } ?: "统计中…", style = MaterialTheme.typography.headlineLarge)
                        }
                        TextButton(onClick = viewModel::refresh, enabled = !working) { Text("刷新") }
                    }
                    Text("不含应用安装体积。原书、解析数据、付费生成资源和个人记录分别计量，共享文件只计一次。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val safe = usage?.let { (it.cleanupBytes[StorageCleanup.TEMPORARY] ?: 0) + (it.cleanupBytes[StorageCleanup.COVERS] ?: 0) }
                    Text("可安全清理：${safe?.let(::formatBytes) ?: "统计中…"}", color = MaterialTheme.colorScheme.primary)
                }
            }
            item {
                MoReadSection(title = "空间分布", icon = Icons.Outlined.Storage) {
                    usage?.categories?.filter { it.bytes > 0 }?.forEachIndexed { index, row ->
                        if (index > 0) MoReadRowDivider()
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row {
                                Text(row.category.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                Text(formatBytes(row.bytes), style = MaterialTheme.typography.labelLarge)
                            }
                            Text(row.category.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(progress = { (row.bytes.toDouble() / (usage?.totalBytes ?: 1L).coerceAtLeast(1L)).toFloat() }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                MoReadSection(title = "选择清理项目", icon = Icons.Outlined.Storage) {
                    StorageCleanup.entries.forEachIndexed { index, kind ->
                        if (index > 0) MoReadRowDivider()
                        val bytes = usage?.cleanupBytes?.get(kind) ?: 0L
                        MoReadRow(icon = Icons.Outlined.Storage, title = kind.title,
                            subtitle = if (kind == StorageCleanup.ORPHANS) "${formatBytes(bytes)} · 深度清理，可能包含旧附件与生成图片"
                                else "${formatBytes(bytes)} · 不删除个人记录",
                            trailing = { TextButton(onClick = { cleanup = kind }, enabled = !working && bytes > 0) { Text("清理") } })
                    }
                }
            }
            item {
                MoReadSection(title = "素材与备份", icon = Icons.Outlined.CloudSync) {
                    MoReadRow(icon = Icons.Outlined.Headphones, title = "语音缓存预算与同步", subtitle = "容量上限、同步及全部清理", onClick = onOpenSpeechCache)
                    MoReadRowDivider()
                    MoReadRow(icon = Icons.Outlined.PhotoLibrary, title = "图片库", subtitle = "背景图与封面图分类管理", onClick = onOpenImages)
                    MoReadRowDivider()
                    MoReadRow(icon = Icons.Outlined.FontDownload, title = "字体库", subtitle = "应用界面与阅读字体独立设置", onClick = onOpenFonts)
                    MoReadRowDivider()
                    MoReadRow(icon = Icons.Outlined.CloudSync, title = "数据备份", subtitle = "删除个人数据前建议备份", onClick = onOpenBackup)
                }
            }
            val active = usage?.books.orEmpty().filter { it.book.removedAt == 0L }
            val removed = usage?.books.orEmpty().filter { it.book.removedAt > 0L }.sortedByDescending { it.book.removedAt }
            item {
                Text("按书籍管理 · ${active.size} 本", style = MaterialTheme.typography.titleMedium)
                Text("按归属文件大小排序；共享封面、字体与数据库只在上方计量。", style = MaterialTheme.typography.bodySmall)
            }
            items(active, key = { "book-${it.book.id}" }) { row ->
                BookStorageCard(row, working, { onOpenBook(row.book.id) },
                    { speechTarget = row }, { indexTarget = row.book }, { removeTarget = row.book })
            }
            if (removed.isNotEmpty()) {
                item {
                    Text("保留的阅读记录 · ${removed.size} 本", style = MaterialTheme.typography.titleMedium)
                    Text("正文已移除；统计仍保留，可查看笔记、书签与插图，或选择彻底删除。", style = MaterialTheme.typography.bodySmall)
                }
                items(removed, key = { "removed-${it.book.id}" }) { row ->
                    BookStorageCard(row, working, { onOpenBook(row.book.id) },
                        { speechTarget = row }, {}, { eraseTarget = row.book })
                    if (row.originals + row.text + row.media + row.layout > 0) {
                        TextButton(onClick = { removeTarget = row.book }, enabled = !working) { Text("重新清理残留正文，保留记录") }
                    }
                }
            }
        }
        if (working) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    cleanup?.let { kind ->
        StorageConfirmation(kind.title, "预计 ${formatBytes(usage?.cleanupBytes?.get(kind) ?: 0)}。${kind.explanation}",
            onDismiss = { cleanup = null }, onConfirm = { cleanup = null; viewModel.clean(kind) })
    }
    speechTarget?.let { row ->
        StorageConfirmation("清理《${row.book.title}》的语音？", "预计 ${formatBytes(row.speech)}。原文、进度和个人记录保留；再次朗读可能需要重新付费合成。",
            { speechTarget = null }, { speechTarget = null; viewModel.clearSpeech(row.book.id) })
    }
    indexTarget?.let { book ->
        StorageConfirmation("停用《${book.title}》的 AI 索引？", "取消后台索引并删除现有向量，之后使用本地关键词检索。重新启用需重新生成向量，可能产生费用；数据库文件不一定立即缩小。",
            { indexTarget = null }, { indexTarget = null; viewModel.disableIndex(book.id) })
    }
    removeTarget?.let { book ->
        RemoveBookDialog("移除《${book.title}》？", { removeTarget = null }, { deleteRecords ->
            removeTarget = null; viewModel.remove(book, deleteRecords)
        })
    }
    eraseTarget?.let { book ->
        StorageConfirmation("彻底删除《${book.title}》的记录？", "阅读统计、进度、书签、笔记、批注、书内对话、附件及 AI 插图都将永久删除。书库伴读的跨书话题独立保留，请在话题历史中单独删除。建议先备份或导出，无法撤销。",
            { eraseTarget = null }, { eraseTarget = null; viewModel.remove(book, true) })
    }
}

@Composable
private fun BookStorageCard(row: BookStorageUsage, working: Boolean, onOpen: () -> Unit,
    onSpeech: () -> Unit, onIndex: () -> Unit, onRemove: () -> Unit) {
    MoReadSection(title = row.book.title, icon = Icons.Outlined.Storage) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(formatBytes(row.total), style = MaterialTheme.typography.titleLarge)
            Text("原书 ${formatBytes(row.originals)} · 正文 ${formatBytes(row.text)} · 插图 ${formatBytes(row.media)} · 精排 ${formatBytes(row.layout)}",
                style = MaterialTheme.typography.bodySmall)
            Text("语音 ${formatBytes(row.speech)} · AI 插图 ${formatBytes(row.illustrations)} · 附件 ${formatBytes(row.attachments)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpen, enabled = !working) { Text("查看记录与插图") }
                if (row.speech > 0) TextButton(onClick = onSpeech, enabled = !working) { Text("清理语音") }
                if (row.indexEnabled && row.book.removedAt == 0L) TextButton(onClick = onIndex, enabled = !working) { Text("停用 AI 索引") }
                TextButton(onClick = onRemove, enabled = !working) {
                    Text(if (row.book.removedAt == 0L) "移除书籍" else "彻底删除记录", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun StorageConfirmation(title: String, detail: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认清理") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
