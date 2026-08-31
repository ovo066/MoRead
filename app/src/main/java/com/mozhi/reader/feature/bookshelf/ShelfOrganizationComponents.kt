package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.IndeterminateCheckBox
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu

@Composable
internal fun ShelfGroupDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    groups: List<ShelfGroupEntity>,
    groupCounts: Map<Long?, Int>,
    selectedGroupId: Long?,
    ungroupedOnly: Boolean,
    onSelect: (Long?, Boolean) -> Unit,
    onCreate: () -> Unit,
    onManage: () -> Unit
) {
    MoReadStableDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        width = 244.dp
    ) {
        MoReadMenuItem(
            text = "全部",
            selected = selectedGroupId == null && !ungroupedOnly,
            onClick = { onSelect(null, false); onDismiss() }
        )
        MoReadMenuItem(
            text = "未分组",
            selected = ungroupedOnly,
            onClick = { onSelect(null, true); onDismiss() }
        )
        groups.filter { it.parentId == null }.forEach { parent ->
            MoReadMenuItem(
                text = parent.name,
                selected = selectedGroupId == parent.id,
                onClick = { onSelect(parent.id, false); onDismiss() }
            )
            groups.filter { it.parentId == parent.id }.forEach { child ->
                MoReadMenuItem(
                    text = child.name,
                    indent = 22.dp,
                    selected = selectedGroupId == child.id,
                    onClick = { onSelect(child.id, false); onDismiss() }
                )
            }
        }
        MoReadMenuDivider()
        MoReadMenuItem(text = "新建分组", icon = Icons.Outlined.Add, onClick = onCreate)
        MoReadMenuItem(text = "管理分组（改名、删除）", icon = Icons.Outlined.Tune, onClick = onManage)
    }
}

@Composable
internal fun ShelfQuickFilters(
    tags: List<BookTagEntity>,
    selectedTagIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onClear: () -> Unit
) {
    if (tags.isEmpty()) return
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(tags, key = BookTagEntity::id) { tag ->
            val selected = tag.id in selectedTagIds
            Surface(
                onClick = { onToggle(tag.id) },
                shape = CircleShape,
                color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .then(Modifier)
                    ) {
                        Surface(
                            modifier = Modifier.size(7.dp),
                            shape = CircleShape,
                            color = tagColor(tag.colorTag)
                        ) {}
                    }
                    Text(tag.name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (selectedTagIds.isNotEmpty()) {
            item {
                TextButton(onClick = onClear) { Text("清除") }
            }
        }
    }
}

@Composable
internal fun ShelfSelectionBar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onGroup: () -> Unit,
    onTags: () -> Unit,
    onDelete: () -> Unit,
    onSetReadState: (BookReadState?) -> Unit,
    onSetPinned: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var moreExpanded by remember { mutableStateOf(false) }
    val enabled = selectedCount > 0
    FrostedSurface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "退出多选")
                }
                Text(
                    text = if (enabled) "已选 $selectedCount 本" else "选择书籍",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSelectAll) {
                    Text(if (allVisibleSelected) "取消全选" else "全选")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SelectionAction(Icons.Outlined.Folder, "分组", enabled, onGroup)
                SelectionAction(Icons.Outlined.Sell, "标签", enabled, onTags)
                SelectionAction(Icons.Outlined.Delete, "移除", enabled, onDelete)
                Box {
                    SelectionAction(Icons.Outlined.MoreHoriz, "更多", enabled) {
                        moreExpanded = true
                    }
                    MoReadStableDropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                        width = 200.dp
                    ) {
                        MoReadMenuItem(
                            text = "置顶",
                            icon = Icons.Outlined.PushPin,
                            onClick = { moreExpanded = false; onSetPinned(true) }
                        )
                        MoReadMenuItem(
                            text = "取消置顶",
                            icon = Icons.Outlined.PushPin,
                            onClick = { moreExpanded = false; onSetPinned(false) }
                        )
                        MoReadMenuDivider()
                        MoReadMenuItem(
                            text = "标为在读",
                            icon = Icons.Outlined.AutoStories,
                            onClick = { moreExpanded = false; onSetReadState(BookReadState.READING) }
                        )
                        MoReadMenuItem(
                            text = "标为已读完",
                            icon = Icons.Outlined.TaskAlt,
                            onClick = { moreExpanded = false; onSetReadState(BookReadState.FINISHED) }
                        )
                        MoReadMenuItem(
                            text = "标为搁置",
                            icon = Icons.Outlined.Bookmark,
                            onClick = { moreExpanded = false; onSetReadState(BookReadState.SHELVED) }
                        )
                        MoReadMenuItem(
                            text = "标为未读",
                            icon = Icons.Outlined.RadioButtonUnchecked,
                            onClick = { moreExpanded = false; onSetReadState(BookReadState.UNREAD) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagPickerSheet(
    selectedBookIds: Set<Long>,
    tags: List<BookTagEntity>,
    refs: List<BookTagRefEntity>,
    onDismiss: () -> Unit,
    onToggleTag: (Long, Boolean) -> Unit,
    onCreateTag: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()
        ) {
            Text("给 ${selectedBookIds.size} 本书设置标签", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                placeholder = { Text("搜索或新建标签…") }
            )
            val visible = tags.filter { it.name.contains(query.trim(), ignoreCase = true) }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(360.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                val normalized = query.trim()
                if (normalized.isNotEmpty() && tags.none { it.name.equals(normalized, true) }) {
                    item {
                        MoReadMenuItem(
                            text = "新建“$normalized”",
                            icon = Icons.Outlined.Add,
                            onClick = { onCreateTag(normalized); query = "" }
                        )
                    }
                }
                visible.groupBy { it.groupName.ifBlank { "未分组" } }.forEach { (group, items) ->
                    item { Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(12.dp)) }
                    items(items, key = BookTagEntity::id) { tag ->
                        val selected = refs.count { it.tagId == tag.id && it.bookId in selectedBookIds }
                        val icon = when {
                            selected == 0 -> Icons.Outlined.CheckBoxOutlineBlank
                            selected == selectedBookIds.size -> Icons.Outlined.CheckBox
                            else -> Icons.Outlined.IndeterminateCheckBox
                        }
                        MoReadMenuItem(
                            text = tag.name,
                            icon = icon,
                            onClick = { onToggleTag(tag.id, selected != selectedBookIds.size) }
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShelfGroupPickerSheet(
    groups: List<ShelfGroupEntity>,
    onDismiss: () -> Unit,
    onSelect: (Long?) -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("移动到分组", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            MoReadMenuItem(text = "未分组", onClick = { onSelect(null); onDismiss() })
            groups.filter { it.parentId == null }.forEach { parent ->
                MoReadMenuItem(text = parent.name, onClick = { onSelect(parent.id); onDismiss() })
                groups.filter { it.parentId == parent.id }.forEach { child ->
                    MoReadMenuItem(
                        text = child.name,
                        indent = 22.dp,
                        onClick = { onSelect(child.id); onDismiss() }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private fun tagColor(tag: String): Color = when (tag) {
    "琥珀" -> Color(0xFFD59B2D)
    "青竹" -> Color(0xFF4E8B62)
    "黛蓝" -> Color(0xFF4A6785)
    "绯红" -> Color(0xFFA84D55)
    else -> runCatching { Color(android.graphics.Color.parseColor(tag)) }.getOrDefault(Color.Gray)
}
