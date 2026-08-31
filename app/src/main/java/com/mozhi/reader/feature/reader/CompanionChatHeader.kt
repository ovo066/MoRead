package com.mozhi.reader.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage

@Composable
internal fun CompanionChatHeader(
    persona: PersonaEntity?,
    personas: List<PersonaEntity>,
    bookTitle: String,
    personaMenuExpanded: Boolean,
    isStreaming: Boolean,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOpenPersonaMenu: () -> Unit,
    onDismissPersonaMenu: () -> Unit,
    onSelectPersona: (Long) -> Unit,
    onNewConversation: () -> Unit,
    onShowConversations: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 全 App 的顶栏都是 8~12dp 起步；这里原来是 4dp，切进聊天页时返回箭头会往左跳一下。
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = palette.onBackground
            )
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp)
                .clickable(onClick = onOpenPersonaMenu),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PersonaAvatarImage(
                name = persona?.name.orEmpty(),
                avatarPath = persona?.avatarPath,
                modifier = Modifier.size(36.dp)
            )
            Column(modifier = Modifier.padding(start = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = persona?.name ?: "伴读",
                        style = MaterialTheme.typography.titleMedium,
                        color = palette.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(
                        Icons.Outlined.ExpandMore,
                        contentDescription = "切换角色",
                        tint = palette.muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = bookTitle.ifBlank { "伴读中" },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            DropdownMenu(
                expanded = personaMenuExpanded,
                onDismissRequest = onDismissPersonaMenu
            ) {
                personas.forEach { candidate ->
                    DropdownMenuItem(
                        text = { Text(candidate.name) },
                        leadingIcon = {
                            PersonaAvatarImage(
                                name = candidate.name,
                                avatarPath = candidate.avatarPath,
                                modifier = Modifier.size(26.dp)
                            )
                        },
                        onClick = { onSelectPersona(candidate.id) }
                    )
                }
            }
        }
        IconButton(
            onClick = onNewConversation,
            enabled = !isStreaming,
            modifier = Modifier.size(44.dp)
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "新会话", tint = palette.accent)
        }
        IconButton(onClick = onShowConversations, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Outlined.History,
                contentDescription = "会话历史",
                tint = palette.onBackground
            )
        }
    }
}
