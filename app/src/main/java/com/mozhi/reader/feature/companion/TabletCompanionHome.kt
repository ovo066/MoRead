package com.mozhi.reader.feature.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.PersonaAvatarImage

@Composable
internal fun TabletCompanionHome(
    state: CompanionUiState, padding: PaddingValues,
    onActivate: (Long) -> Unit, onEdit: (Long) -> Unit, onCreate: () -> Unit,
    onChat: () -> Unit, onStats: () -> Unit
) {
    val active = state.personas.firstOrNull { it.id == state.activePersonaId } ?: state.orderedPersonas.firstOrNull()
    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 40.dp, vertical = 28.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("伴读", style = MaterialTheme.typography.headlineLarge)
                Text("读到的，想到的，都可以聊聊。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
            IconButton(onClick = onStats) { Icon(Icons.Outlined.CalendarMonth, "陪伴足迹") }
            IconButton(onClick = onCreate) { Icon(Icons.Outlined.Add, "新建角色") }
        }
        Spacer(Modifier.height(32.dp))
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 760.dp
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(if (wide) 48.dp else 24.dp)) {
                LazyColumn(Modifier.width(if (wide) 260.dp else 196.dp).fillMaxHeight().testTag("tablet-personas"),
                    verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item { Text("我的伴读 · ${state.personas.size}", style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                    items(state.orderedPersonas, key = { it.id }) { persona ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (persona.id == active?.id) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .selectable(persona.id == active?.id, role = Role.Tab, onClick = { onActivate(persona.id) })
                            .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            PersonaAvatarImage(persona.name, persona.avatarPath, Modifier.size(42.dp))
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(persona.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(persona.subtitle.ifBlank { if (persona.isRoleplay) "角色伴读" else "阅读助手" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    item { TextButton(onClick = onCreate) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Text("创建伴读", Modifier.padding(start = 8.dp)) } }
                }
                LazyColumn(Modifier.weight(1f).fillMaxHeight().testTag("tablet-persona-detail"),
                    contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PersonaAvatarImage(active?.name.orEmpty(), active?.avatarPath, Modifier.size(if (wide) 88.dp else 64.dp))
                            Spacer(Modifier.weight(1f))
                            if (active != null) IconButton(onClick = { onEdit(active.id) }) { Icon(Icons.Outlined.Edit, "编辑角色") }
                        }
                    }
                    item {
                        Text(active?.name ?: "你的阅读伙伴", style = MaterialTheme.typography.headlineLarge)
                        Text(active?.subtitle?.ifBlank { "陪你读书，也听你分享" } ?: "从一个话题开始", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                    }
                    item { Text(active?.greeting?.ifBlank { active.personality } ?: "创建一位伴读，或直接开始聊天。", style = MaterialTheme.typography.bodyLarge) }
                    item {
                        Button(onClick = onChat, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp))
                            Text("开始聊天", Modifier.padding(start = 10.dp))
                        }
                    }
                    if (active != null) item {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        Text("关于这位伴读", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 28.dp, bottom = 12.dp))
                        Text(active.personality, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (!state.longTermMemoryEnabled || !active.memoryEnabled) "长期记忆已暂停" else "已留下 ${state.memoryCounts[active.id] ?: 0} 段记忆",
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                    }
                }
            }
        }
    }
}
