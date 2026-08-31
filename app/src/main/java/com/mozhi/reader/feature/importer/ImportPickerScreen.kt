package com.mozhi.reader.feature.importer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.importer.ScannedBookFile
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop

/**
 * 文件夹扫描结果的勾选页。分章规则在批量导入里自动取最佳值，页脚如实告诉用户
 * 事后可以在阅读页「重新分章」里改，免得他们以为一键导入等于放弃控制权。
 */
@Composable
fun ImportPickerScreen(
    onBack: () -> Unit,
    onImported: (count: Int) -> Unit,
    viewModel: ImportPickerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedFiles = state.files.filter { it.uri in state.selected }
    val visibleFiles = state.visibleFiles
    val visibleSelectedCount = visibleFiles.count { it.uri in state.selected }
    val allVisibleSelected = visibleFiles.isNotEmpty() && visibleSelectedCount == visibleFiles.size

    MoReadBackdrop {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "选择要导入的书",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            when {
                                state.scanning -> "正在扫描文件夹…"
                                state.files.isEmpty() -> state.error ?: "没有找到书籍文件"
                                state.searchQuery.isNotBlank() ->
                                    "找到 ${state.files.size} 本 · 显示 ${visibleFiles.size} 本 · 已选 ${state.selected.size} 本"
                                else -> "找到 ${state.files.size} 本 · 已选 ${state.selected.size} 本"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (visibleFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.selectAll(!allVisibleSelected) }) {
                            Text(
                                when {
                                    allVisibleSelected && state.searchQuery.isNotBlank() -> "取消结果"
                                    allVisibleSelected -> "全不选"
                                    state.searchQuery.isNotBlank() -> "全选结果"
                                    else -> "全选"
                                }
                            )
                        }
                    }
                }

                if (state.files.isNotEmpty()) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp),
                        placeholder = { Text("搜索书名或文件夹") },
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        trailingIcon = if (state.searchQuery.isNotEmpty()) {
                            {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "清除搜索")
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp)
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.truncated) {
                        item {
                            Text(
                                "文件很多，只列出前 ${state.files.size} 本；导完这批再扫一次即可继续。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (state.searchQuery.isNotBlank() && visibleFiles.isEmpty()) {
                        item {
                            Text(
                                "没有匹配“${state.searchQuery.trim()}”的书籍",
                                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    state.groups.forEach { (directory, files) ->
                        item(key = "group-$directory") {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = directory.ifEmpty { "文件夹根目录" },
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { viewModel.toggleGroup(directory) }) {
                                    Text("整组切换", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        items(files, key = { it.uri.toString() }) { file ->
                            ScannedFileRow(
                                file = file,
                                checked = file.uri in state.selected,
                                alreadyImported = file.uri in state.alreadyImported,
                                onToggle = { viewModel.toggle(file.uri) }
                            )
                        }
                    }
                }

                if (state.files.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 3.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setCreateGroupsFromFolders(
                                            !state.createGroupsFromFolders
                                        )
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = state.createGroupsFromFolders,
                                    onCheckedChange = viewModel::setCreateGroupsFromFolders
                                )
                                Column(Modifier.padding(start = 6.dp)) {
                                    Text("按文件夹建立分组")
                                    Text(
                                        "最多保留两级目录，根目录文件保持未分组。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                "TXT 会自动套用识别度最高的分章规则；导入后可在阅读页的「重新分章」里调整。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onImported(viewModel.importSelected()) },
                                enabled = selectedFiles.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    if (selectedFiles.isEmpty()) {
                                        "请先选择书籍"
                                    } else {
                                        "导入 ${selectedFiles.size} 本到书架"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (state.scanning) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@Composable
private fun ScannedFileRow(
    file: ScannedBookFile,
    checked: Boolean,
    alreadyImported: Boolean,
    onToggle: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            Icon(
                Icons.Outlined.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    buildString {
                        append(file.extension.uppercase())
                        if (file.sizeBytes > 0) append(" · ").append(formatSize(file.sizeBytes))
                        if (alreadyImported) append(" · 书架里已有同名书")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 ->
        String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(java.util.Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
