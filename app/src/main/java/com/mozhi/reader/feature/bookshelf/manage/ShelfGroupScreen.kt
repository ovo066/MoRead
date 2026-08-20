package com.mozhi.reader.feature.bookshelf.manage

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop

@Composable
fun ShelfGroupScreen(
    onBack: () -> Unit,
    viewModel: ShelfManageViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<ShelfGroupEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ShelfGroupEntity?>(null) }

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
                ManageTopBar(title = "管理分组", onBack = onBack)
                if (state.groups.isEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(42.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("还没有分组", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "新建分组后，可在书架多选书籍批量移动。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 8.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.groups.filter { it.parentId == null }.forEach { parent ->
                            item(key = parent.id) {
                                GroupRow(
                                    group = parent,
                                    count = state.groupCounts[parent.id] ?: 0,
                                    isChild = false,
                                    onEdit = { editing = parent },
                                    onDelete = { deleting = parent },
                                    onMoveUp = { viewModel.moveGroup(parent, -1) },
                                    onMoveDown = { viewModel.moveGroup(parent, 1) }
                                )
                            }
                            items(
                                items = state.groups.filter { it.parentId == parent.id },
                                key = ShelfGroupEntity::id
                            ) { child ->
                                GroupRow(
                                    group = child,
                                    count = state.groupCounts[child.id] ?: 0,
                                    isChild = true,
                                    onEdit = { editing = child },
                                    onDelete = { deleting = child },
                                    onMoveUp = { viewModel.moveGroup(child, -1) },
                                    onMoveDown = { viewModel.moveGroup(child, 1) }
                                )
                            }
                        }
                    }
                }
            }

            FloatingActionButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
                shape = CircleShape
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "新建分组")
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (creating) {
        GroupEditorDialog(
            title = "新建分组",
            groups = state.groups,
            group = null,
            onDismiss = { creating = false },
            onSave = { name, parentId ->
                creating = false
                viewModel.saveGroup(name, parentId)
            }
        )
    }
    editing?.let { group ->
        GroupEditorDialog(
            title = "编辑分组",
            groups = state.groups,
            group = group,
            onDismiss = { editing = null },
            onSave = { name, parentId ->
                editing = null
                viewModel.saveGroup(name, parentId, group)
            }
        )
    }
    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("删除“${group.name}”？") },
            text = {
                Text(
                    if (group.parentId == null) {
                        "组内书籍将移到未分组；它的二级分组会提升为顶层。"
                    } else {
                        "请选择把组内书籍移到父分组，或移到未分组。"
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    viewModel.deleteGroup(group, moveBooksToParent = group.parentId != null)
                }) { Text(if (group.parentId == null) "删除" else "移到父分组") }
            },
            dismissButton = {
                Row {
                    if (group.parentId != null) {
                        TextButton(onClick = {
                            deleting = null
                            viewModel.deleteGroup(group, moveBooksToParent = false)
                        }) { Text("移到未分组") }
                    }
                    TextButton(onClick = { deleting = null }) { Text("取消") }
                }
            }
        )
    }
}

@Composable
private fun ManageTopBar(title: String, onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GroupRow(
    group: ShelfGroupEntity,
    count: Int,
    isChild: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().padding(start = if (isChild) 22.dp else 0.dp),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (isChild) Icons.Outlined.SubdirectoryArrowRight else Icons.Outlined.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "$count 本",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onMoveUp) {
                Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "上移")
            }
            IconButton(onClick = onMoveDown) {
                Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "下移")
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun GroupEditorDialog(
    title: String,
    groups: List<ShelfGroupEntity>,
    group: ShelfGroupEntity?,
    onDismiss: () -> Unit,
    onSave: (String, Long?) -> Unit
) {
    var name by remember(group?.id) { mutableStateOf(group?.name.orEmpty()) }
    var parentId by remember(group?.id) { mutableStateOf(group?.parentId) }
    var parentMenu by remember { mutableStateOf(false) }
    val hasChildren = group != null && groups.any { it.parentId == group.id }
    val parentCandidates = groups.filter { it.parentId == null && it.id != group?.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("分组名") },
                    singleLine = true
                )
                Box {
                    OutlinedButton(
                        onClick = { parentMenu = true },
                        enabled = !hasChildren,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(groups.firstOrNull { it.id == parentId }?.name ?: "顶层分组")
                    }
                    DropdownMenu(expanded = parentMenu, onDismissRequest = { parentMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("顶层分组") },
                            onClick = { parentId = null; parentMenu = false }
                        )
                        parentCandidates.forEach { parent ->
                            DropdownMenuItem(
                                text = { Text(parent.name) },
                                onClick = { parentId = parent.id; parentMenu = false }
                            )
                        }
                    }
                }
                if (hasChildren) {
                    Text(
                        "该分组包含二级分组，因此只能保持在顶层。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, parentId) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
