package com.mozhi.reader.feature.bookdetail

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.ui.components.NavigationSheet

internal enum class AnnotationIndexSource(val label: String) { ALL("全部"), USER("我的"), AI("AI") }

internal fun filterAnnotationIndex(
    annotations: List<AnnotationEntity>, source: AnnotationIndexSource, personaId: Long? = null
): List<AnnotationEntity> = annotations.filter {
    when (source) {
        AnnotationIndexSource.ALL -> true
        AnnotationIndexSource.USER -> it.personaId == null
        AnnotationIndexSource.AI -> it.personaId != null && (personaId == null || it.personaId == personaId)
    }
}

internal fun annotationAuthorLabel(annotation: AnnotationEntity, names: Map<Long, String>): String =
    annotation.personaId?.let { "AI · ${names[it] ?: "已删除角色 #$it"}" } ?: "我的划线"

@Composable
internal fun AnnotationIndexSheet(
    annotations: List<AnnotationEntity>, personaNames: Map<Long, String>, hiddenCount: Int,
    onDelete: (Long) -> Unit, onDismiss: () -> Unit, onLocate: ((AnnotationEntity) -> Unit)?
) {
    NavigationSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("划线与批注", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭划线与批注") }
            }
            Text(if (onLocate != null) "点击段评跳到原文，短暂高亮所选段落。" else "正文已移除，保留的划线与批注仍可查看。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Background generation changes the count, never the list's viewport or anchor.
            Text(if (hiddenCount > 0) "还有 $hiddenCount 条读到后会出现" else "在原文点击划线可参与讨论",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.height(32.dp).padding(top = 6.dp))
            AnnotationIndex(annotations, personaNames, onDelete, onLocate, Modifier.weight(1f))
        }
    }
}

/** Input is already visibility-filtered by BookDetailViewModel; counts never include unread text. */
@Composable
internal fun AnnotationIndex(
    annotations: List<AnnotationEntity>,
    personaNames: Map<Long, String>,
    onDelete: (Long) -> Unit,
    onLocate: ((AnnotationEntity) -> Unit)?,
    modifier: Modifier = Modifier
) {
    var source by rememberSaveable { mutableStateOf(AnnotationIndexSource.ALL) }
    var personaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteTarget by remember { mutableStateOf<AnnotationEntity?>(null) }
    val counts = remember(annotations) {
        val mine = annotations.count { it.personaId == null }
        mapOf(AnnotationIndexSource.ALL to annotations.size, AnnotationIndexSource.USER to mine,
            AnnotationIndexSource.AI to annotations.size - mine)
    }
    val authors = remember(annotations) { annotations.mapNotNull { it.personaId }.distinct() }
    val filtered = remember(annotations, source, personaId) { filterAnnotationIndex(annotations, source, personaId) }
    val groups = remember(filtered) {
        filtered.groupBy { "${it.bookId}:${it.chapterIndex}:${it.startCharOffset}:${it.endCharOffset}" }
            .entries.sortedByDescending { it.value.maxOf(AnnotationEntity::createdAt) }
    }
    val filterStates = rememberSaveableStateHolder()
    val tabsScroll = rememberScrollState()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().testTag("annotation-tabs").horizontalScroll(tabsScroll).blockSheetDrag(tabsScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnnotationIndexSource.entries.forEach { candidate ->
                FilterChip(selected = source == candidate,
                    onClick = { source = candidate },
                    label = { Text("${candidate.label} ${counts[candidate] ?: 0}") })
            }
        }
        if (source == AnnotationIndexSource.AI && authors.isNotEmpty()) {
            val authorScroll = rememberScrollState()
            Row(Modifier.fillMaxWidth().horizontalScroll(authorScroll).blockSheetDrag(authorScroll), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = personaId == null, onClick = { personaId = null }, label = { Text("全部 AI 角色") })
                authors.forEach { id ->
                    FilterChip(selected = personaId == id, onClick = { personaId = id },
                        label = { Text(personaNames[id] ?: "已删除角色 #$id", maxLines = 1) })
                }
            }
        }
        Text("${groups.size} 处原文 · ${filtered.size} 条划线/批注", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        if (filtered.isEmpty()) {
            Text(when (source) {
                AnnotationIndexSource.USER -> "还没有你的划线。阅读时长按原文即可添加。"
                AnnotationIndexSource.AI -> "当前筛选下还没有 AI 划线。AI 批注不会计入“我的”。"
                AnnotationIndexSource.ALL -> "还没有划线或批注。阅读时长按原文即可添加。"
            }, style = MaterialTheme.typography.bodyMedium)
        } else {
            filterStates.SaveableStateProvider("${source.name}:${if (source == AnnotationIndexSource.AI) personaId else null}") {
                val list = rememberLazyListState()
                LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("annotation-list").blockSheetDrag(list),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(groups, key = { it.key }) { group ->
                        AnnotationReviewCard(group.value, personaNames, onLocate = onLocate, onDelete = { id ->
                            deleteTarget = group.value.firstOrNull { it.id == id }
                        })
                    }
                }
            }
        }
    }
    deleteTarget?.let { annotation ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text(if (annotation.personaId == null) "删除我的划线？" else "删除 AI 划线？") },
            text = { Text("删除这条划线、批注及关联讨论，不会修改书籍原文。") },
            confirmButton = { TextButton(onClick = { onDelete(annotation.id); deleteTarget = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } })
    }
}
