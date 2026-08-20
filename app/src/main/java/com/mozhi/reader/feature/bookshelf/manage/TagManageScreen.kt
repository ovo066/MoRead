package com.mozhi.reader.feature.bookshelf.manage

import android.graphics.Color.parseColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import kotlinx.coroutines.launch

@Composable
fun TagManageScreen(
    onBack: () -> Unit,
    viewModel: ShelfManageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BookTagEntity?>(null) }
    var editingGroup by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var choosingMergeTarget by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                    writer.write(viewModel.exportTagsJson())
                } ?: error("无法写入文件")
            }.onSuccess {
                scope.launch { snackbar.showSnackbar("标签已导出") }
            }.onFailure {
                scope.launch { snackbar.showSnackbar("导出失败") }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("无法读取文件")
            }.onSuccess(viewModel::importTagsJson)
                .onFailure { scope.launch { snackbar.showSnackbar("导入失败") } }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ShelfManageEvent.Message -> snackbar.showSnackbar(event.text)
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
                        Text("管理标签", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                        Text(
                            if (state.selectedTagIds.isEmpty()) "${state.tags.size} 个标签" else "已选 ${state.selectedTagIds.size} 个",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) {
                        Icon(Icons.Outlined.FileUpload, contentDescription = "导入标签")
                    }
                    IconButton(onClick = { exportLauncher.launch("书架标签.json") }) {
                        Icon(Icons.Outlined.FileDownload, contentDescription = "导出标签")
                    }
                }

                if (state.selectedTagIds.isNotEmpty()) {
                    FrostedSurface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(20.dp),
                        shadowElevation = 3.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            TextButton(onClick = { editingGroup = true }) { Text("设置组") }
                            TextButton(
                                onClick = { choosingMergeTarget = true },
                                enabled = state.selectedTagIds.size > 1
                            ) {
                                Icon(Icons.Outlined.Merge, contentDescription = null)
                                Text("合并")
                            }
                            TextButton(onClick = { confirmingDelete = true }) {
                                Icon(Icons.Outlined.Delete, contentDescription = null)
                                Text("删除")
                            }
                            TextButton(onClick = viewModel::clearTagSelection) { Text("取消") }
                        }
                    }
                }

                if (state.tags.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有标签", style = MaterialTheme.typography.titleMedium)
                        Text("可一次输入多个标签，用逗号分隔。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.tags, key = BookTagEntity::id) { tag ->
                            TagManageRow(
                                tag = tag,
                                count = state.tagCounts[tag.id] ?: 0,
                                selected = tag.id in state.selectedTagIds,
                                onToggle = { viewModel.toggleTagSelection(tag.id) },
                                onEdit = { editing = tag },
                                onMoveUp = { viewModel.moveTag(tag, -1) },
                                onMoveDown = { viewModel.moveTag(tag, 1) }
                            )
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                shape = CircleShape
            ) { Icon(Icons.Outlined.Add, contentDescription = "新建标签") }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (creating) {
        NewTagsDialog(
            onDismiss = { creating = false },
            onSave = { names, group ->
                creating = false
                viewModel.createTags(names, group)
            }
        )
    }
    editing?.let { tag ->
        EditTagDialog(
            tag = tag,
            onDismiss = { editing = null },
            onSave = { name, group, colorTag ->
                editing = null
                viewModel.saveTag(tag, name, group, colorTag)
            }
        )
    }
    if (editingGroup) {
        TextInputDialog(
            title = "设置标签组",
            label = "组名（留空为未分组）",
            initialValue = "",
            onDismiss = { editingGroup = false },
            onConfirm = { group ->
                editingGroup = false
                viewModel.setSelectedTagGroup(group)
            }
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除 ${state.selectedTagIds.size} 个标签？") },
            text = { Text("只删除标签及关联，不会删除书籍。") },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; viewModel.deleteSelectedTags() }) {
                    Text("删除")
                }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
        )
    }
    if (choosingMergeTarget) {
        AlertDialog(
            onDismissRequest = { choosingMergeTarget = false },
            title = { Text("保留哪个标签？") },
            text = {
                Column {
                    state.tags.filter { it.id in state.selectedTagIds }.forEach { tag ->
                        TextButton(
                            onClick = {
                                choosingMergeTarget = false
                                viewModel.mergeSelectedTags(tag.id)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(tag.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosingMergeTarget = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun TagManageRow(
    tag: BookTagEntity,
    count: Int,
    selected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (selected) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank,
                contentDescription = if (selected) "取消选择" else "选择",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Surface(
                modifier = Modifier.padding(start = 10.dp).size(10.dp),
                shape = CircleShape,
                color = tagColor(tag.colorTag)
            ) {}
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(tag.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${tag.groupName.ifBlank { "未分组" }} · $count 本",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoveUp) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移") }
            IconButton(onClick = onMoveDown) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移") }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "编辑") }
        }
    }
}

@Composable
private fun NewTagsDialog(
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var names by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = names,
                    onValueChange = { names = it },
                    label = { Text("标签名") },
                    supportingText = { Text("多个标签可用逗号分隔") }
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text("标签组（可选）") },
                    singleLine = true
                )
            }
        },
        confirmButton = { Button(onClick = { onSave(names, group) }) { Text("新建") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun EditTagDialog(
    tag: BookTagEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    var group by remember(tag.id) { mutableStateOf(tag.groupName) }
    var colorTag by remember(tag.id) { mutableStateOf(tag.colorTag) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑标签") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("标签名") })
                OutlinedTextField(value = group, onValueChange = { group = it }, label = { Text("标签组") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TAG_COLORS.forEach { option ->
                        Surface(
                            shape = CircleShape,
                            color = tagColor(option),
                            border = BorderStroke(2.dp, if (option == colorTag) MaterialTheme.colorScheme.onSurface else Color.Transparent),
                            modifier = Modifier.size(24.dp).clickable { colorTag = option }
                        ) {}
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, group, colorTag) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }) },
        confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

private fun tagColor(tag: String): Color = when (tag) {
    "琥珀" -> Color(0xFFD59B2D)
    "青竹" -> Color(0xFF4E8B62)
    "黛蓝" -> Color(0xFF4A6785)
    "绯红" -> Color(0xFFA84D55)
    else -> runCatching { Color(parseColor(tag)) }.getOrDefault(Color.Gray)
}

private val TAG_COLORS = listOf("琥珀", "青竹", "黛蓝", "绯红")
