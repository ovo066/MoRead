package com.mozhi.reader.feature.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.ui.components.safeTopPadding

internal val LocalCompanionSidebarVisible = compositionLocalOf { false }

/** Keep the conversation in one composition slot while the window or keyboard changes size. */
@Composable
internal fun CompanionWorkspace(
    conversations: List<ConversationEntity>, activeId: Long?, title: String,
    enabled: Boolean, onBack: () -> Unit, onNew: () -> Unit, onOpen: (Long) -> Unit,
    onManage: () -> Unit, embedded: Boolean = false, content: @Composable () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expanded = !embedded && maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            // Always compose the content after this stable optional slot.
            Box(Modifier.width(if (expanded) 264.dp else 0.dp).fillMaxHeight()) {
                if (expanded) Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow).safeTopPadding().navigationBarsPadding().padding(20.dp)
                    .testTag("companion-history-sidebar")) {
                    TextButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, null, Modifier.size(18.dp)); Text("返回", Modifier.padding(start = 8.dp)) }
                    Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 24.dp))
                    FilledTonalButton(onClick = onNew, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Icon(Icons.Outlined.Add, null); Text("新会话", Modifier.padding(start = 8.dp))
                    }
                    Text("最近会话", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
                    LazyColumn(Modifier.weight(1f).testTag("companion-history-list"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(conversations, key = { it.id }) { conversation ->
                            Text(conversation.title.ifBlank { "未命名会话" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                                color = if (conversation.id == activeId) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .background(if (conversation.id == activeId) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .selectable(conversation.id == activeId, enabled = enabled, role = Role.Tab, onClick = { onOpen(conversation.id) })
                                    .padding(horizontal = 14.dp, vertical = 16.dp))
                        }
                        if (conversations.isEmpty()) item { Text("开始聊天后，会话会留在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    TextButton(onClick = onManage) { Icon(Icons.Outlined.History, null, Modifier.size(18.dp)); Text("管理会话", Modifier.padding(start = 8.dp)) }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                CompositionLocalProvider(LocalCompanionSidebarVisible provides expanded) {
                    // The chat page owns its backdrop and bounds only its foreground content.
                    // Painting here would cover a caller's wallpaper or constrain it to a column.
                    content()
                }
            }
        }
    }
}
