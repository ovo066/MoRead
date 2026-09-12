package com.mozhi.reader.feature.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.companion.LibraryOrganizationMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryOrganizationSheet(
    plans: List<LibraryOrganizationMessage>, busy: Boolean, onDismiss: () -> Unit,
    onConfirm: (Long, Boolean) -> Unit
) {
    var selectedId by remember { mutableStateOf(plans.lastOrNull { it.plan.status == "PENDING" }?.messageId ?: plans.lastOrNull()?.messageId) }
    val selected = plans.firstOrNull { it.messageId == selectedId } ?: plans.lastOrNull()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("整理书架", style = MaterialTheme.typography.titleLarge)
            if (plans.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(plans, key = { it.messageId }) { plan ->
                    FilterChip(selected?.messageId == plan.messageId, { selectedId = plan.messageId }, label = { Text("方案 " + (plans.indexOf(plan) + 1)) })
                }
            }
            selected?.let { proposal ->
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(proposal.plan.changes, key = { it.bookId }) { change ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(change.title, style = MaterialTheme.typography.titleSmall)
                                if (change.addTags.isNotEmpty()) Text("＋ " + change.addTags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                if (change.removeTags.isNotEmpty()) Text("－ " + change.removeTags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                change.groupName?.let { Text(change.beforeGroupName.ifBlank { "未分组" } + " → " + it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (proposal.plan.status == "PENDING") {
                        TextButton(enabled = !busy, onClick = { onConfirm(proposal.messageId, false) }) { Text("取消方案") }
                        Button(enabled = !busy, onClick = { onConfirm(proposal.messageId, true) }) { Text("确认整理") }
                    } else Text(if (proposal.plan.status == "APPLIED") "已整理" else "已取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
