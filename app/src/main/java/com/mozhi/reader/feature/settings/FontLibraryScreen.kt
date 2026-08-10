package com.mozhi.reader.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop

@Composable
fun FontLibraryScreen(
    onBack: () -> Unit,
    viewModel: FontLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<PendingReaderFont?>(null) }
    var importName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ReaderFontAsset?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ReaderFontAsset?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::prepareImport)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is FontLibraryEvent.ConfirmImport -> {
                    pendingImport = event.pending
                    importName = event.pending.detectedName
                }
                is FontLibraryEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

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
                        Text("字体库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${state.fonts.size} 个字体 · 正文与高亮规则可独立选择",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        onClick = { picker.launch("*/*") },
                        enabled = !state.isWorking
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("导入", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.fonts.isEmpty()) {
                        item {
                            FrostedSurface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                shadowElevation = 5.dp
                            ) {
                                Column(
                                    modifier = Modifier.padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Outlined.FontDownload, contentDescription = null, modifier = Modifier.size(36.dp))
                                    Text("字体库还是空的", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "支持 TTF、OTF 与 TTC，导入时会自动识别字体名称。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    items(state.fonts, key = ReaderFontAsset::id) { font ->
                        FontLibraryRow(
                            font = font,
                            selected = state.selectedBodyFontId == font.id,
                            onSelect = { viewModel.selectForBody(font.id) },
                            onRename = {
                                renameTarget = font
                                renameText = font.displayName
                            },
                            onDelete = { deleteTarget = font }
                        )
                    }
                }
            }
            if (state.isWorking) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelImport(pending)
                pendingImport = null
            },
            title = { Text("加入字体库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("已从 ${pending.originalFileName} 自动识别名称，可在保存前修改。")
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it.take(48) },
                        label = { Text("字体名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importName.isNotBlank(),
                    onClick = {
                        viewModel.confirmImport(pending, importName)
                        pendingImport = null
                    }
                ) { Text("加入并设为正文") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelImport(pending)
                    pendingImport = null
                }) { Text("取消") }
            }
        )
    }

    renameTarget?.let { font ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名字体") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(48) },
                    label = { Text("字体名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.rename(font.id, renameText)
                        renameTarget = null
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { font ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除字体？") },
            text = { Text("将删除“${font.displayName}”的本地文件；引用它的高亮规则会改为跟随正文。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(font)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun FontLibraryRow(
    font: ReaderFontAsset,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 5.dp
    ) {
        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        Icons.Outlined.FontDownload,
                        contentDescription = null,
                        modifier = Modifier.padding(10.dp).size(22.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(font.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        font.originalFileName.ifBlank { font.filePath.substringAfterLast('/') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = "当前正文字体", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onSelect, enabled = !selected) {
                    Text(if (selected) "正文使用中" else "设为正文")
                }
                TextButton(onClick = onRename) { Text("重命名") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除字体")
                }
            }
        }
    }
}
