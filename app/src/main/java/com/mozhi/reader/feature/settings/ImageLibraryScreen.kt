package com.mozhi.reader.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.datastore.PendingReaderImage
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderImagePurpose
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.io.File

@Composable
fun ImageLibraryScreen(
    onBack: () -> Unit,
    viewModel: ImageLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pendingImport by remember { mutableStateOf<PendingReaderImage?>(null) }
    var importName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ReaderImageAsset?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<ReaderImageAsset?>(null) }
    var category by remember { mutableStateOf<ReaderImagePurpose?>(null) }
    var importPurpose by remember { mutableStateOf(ReaderImagePurpose.BACKGROUND) }
    var classifyTarget by remember { mutableStateOf<ReaderImageAsset?>(null) }
    val visibleImages = state.images.filter { category == null || it.purpose == category }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::prepareImport)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImageLibraryEvent.ConfirmImport -> {
                    pendingImport = event.pending
                    importName = event.pending.detectedName
                    importPurpose = category ?: ReaderImagePurpose.BACKGROUND
                }
                is ImageLibraryEvent.Message -> snackbar.showSnackbar(event.text)
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
                        Text("图片库", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        Text(
                            "${state.images.size} 张图片 · 分类管理，保留使用中的引用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { picker.launch("image/*") }, enabled = !state.isWorking) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("导入", modifier = Modifier.padding(start = 6.dp))
                    }
                }

                FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = category == null, onClick = { category = null }, label = { Text("全部") })
                    ReaderImagePurpose.entries.forEach { purpose ->
                        FilterChip(selected = category == purpose, onClick = { category = purpose }, label = {
                            Text("${purpose.label} ${state.images.count { it.purpose == purpose }}")
                        })
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (visibleImages.isEmpty()) {
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
                                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(36.dp))
                                    Text("此分类暂无图片", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "导入时选择用途；旧图片可在“未分类”中重新归类，不会改变正在使用的背景或封面。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    items(visibleImages, key = ReaderImageAsset::id) { image ->
                        ImageLibraryRow(
                            image = image,
                            selected = state.selectedBackgroundImageId == image.id,
                            onSelect = { viewModel.selectForBackground(image.id) },
                            onClassify = { classifyTarget = image },
                            onRename = {
                                renameTarget = image
                                renameText = image.displayName
                            },
                            onDelete = { deleteTarget = image }
                        )
                    }
                    if (state.selectedBackgroundImageId != null) {
                        item {
                            TextButton(onClick = viewModel::clearBackground) {
                                Text("阅读背景恢复为主题底色")
                            }
                        }
                    }
                }
            }
            if (state.isWorking) CircularProgressIndicator(Modifier.align(Alignment.Center))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    pendingImport?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                viewModel.cancelImport(pending)
                pendingImport = null
            },
            title = { Text("加入图片库") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${pending.originalFileName} · ${pending.width} × ${pending.height}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReaderImagePurpose.entries.forEach { purpose ->
                            FilterChip(selected = importPurpose == purpose, onClick = { importPurpose = purpose }, label = { Text(purpose.label) })
                        }
                    }
                    OutlinedTextField(
                        value = importName,
                        onValueChange = { importName = it.take(48) },
                        label = { Text("图片名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importName.isNotBlank(),
                    onClick = {
                        viewModel.confirmImport(pending, importName, importPurpose)
                        pendingImport = null
                    }
                ) { Text("加入图片库") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelImport(pending)
                    pendingImport = null
                }) { Text("取消") }
            }
        )
    }

    classifyTarget?.let { image ->
        AlertDialog(
            onDismissRequest = { classifyTarget = null },
            title = { Text("图片用途") },
            text = {
                Column {
                    Text("分类不改变当前背景和封面的引用，也不会复制图片文件。")
                    ReaderImagePurpose.entries.forEach { purpose ->
                        TextButton(onClick = { viewModel.classify(image.id, purpose); classifyTarget = null }) {
                            Text(purpose.label + if (purpose == image.purpose) " · 当前" else "")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { classifyTarget = null }) { Text("取消") } }
        )
    }

    renameTarget?.let { image ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名图片") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(48) },
                    label = { Text("图片名称") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        viewModel.rename(image.id, renameText)
                        renameTarget = null
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } }
        )
    }

    deleteTarget?.let { image ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除图片？") },
            text = { Text("将删除“${image.displayName}”的本地文件。正在作为背景或封面的图片需先更换。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(image)
                    deleteTarget = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun ImageLibraryRow(
    image: ReaderImageAsset,
    selected: Boolean,
    onSelect: () -> Unit,
    onClassify: () -> Unit,
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
                Surface(shape = RoundedCornerShape(12.dp)) {
                    AsyncImage(
                        model = File(image.filePath),
                        contentDescription = image.displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(72.dp).height(84.dp)
                    )
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(image.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(
                        image.originalFileName.ifBlank { image.filePath.substringAfterLast('/') },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (image.width > 0 && image.height > 0) {
                        Text(
                            "${image.width} × ${image.height}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (selected) {
                    Icon(Icons.Outlined.Check, contentDescription = "当前阅读背景", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (image.purpose == ReaderImagePurpose.COVER) {
                Text(if (selected) "封面素材 · 当前也用于阅读背景" else "可在书籍详情的“更换封面”中选用", style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onClassify) { Text(image.purpose.label) }
                if (image.purpose != ReaderImagePurpose.COVER) OutlinedButton(onClick = onSelect, enabled = !selected) {
                    Text(if (selected) "背景使用中" else "设为背景")
                }
                TextButton(onClick = onRename) { Text("重命名") }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除图片")
                }
            }
        }
    }
}
