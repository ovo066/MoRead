package com.mozhi.reader.feature.companion

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.core.database.entity.worldBook
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.theme.MoReadTokens

/**
 * 伴读页（design/ui-adaptation-plan.md §5）：角色卡列表，接 personas 表。
 * 点卡片 = 设为当前伴读角色（持久化），编辑按钮进角色编辑二级页。
 */
@Composable
fun CompanionScreen(
    contentPadding: PaddingValues,
    onEditPersona: (Long) -> Unit,
    onCreatePersona: () -> Unit,
    viewModel: CompanionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("伴读", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        text = "${state.personas.size} 位角色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp, bottom = 6.dp)
                    )
                }
            }
            items(state.personas, key = PersonaEntity::id) { persona ->
                PersonaCard(
                    persona = persona,
                    active = persona.id == state.activePersonaId,
                    memoryCount = state.memoryCounts[persona.id] ?: 0L,
                    onClick = { viewModel.activate(persona.id) },
                    onEdit = { onEditPersona(persona.id) }
                )
            }
            item {
                DashedAddRow(label = "新建角色", onClick = onCreatePersona)
            }
            if (state.loaded && state.personas.isEmpty()) {
                item {
                    Text(
                        text = "还没有角色。新建一个，或在编辑页导入 SillyTavern 角色卡。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonaCard(
    persona: PersonaEntity,
    active: Boolean,
    memoryCount: Long,
    onClick: () -> Unit,
    onEdit: () -> Unit
) {
    val worldBookCount = remember(persona.worldBookJson) { persona.worldBook().size }
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .let { base ->
                if (active) {
                    base.border(
                        BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
                        RoundedCornerShape(24.dp)
                    )
                } else {
                    base
                }
            }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PersonaAvatarImage(
                    name = persona.name,
                    avatarPath = persona.avatarPath,
                    modifier = Modifier.size(50.dp)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(persona.name, style = MaterialTheme.typography.titleMedium)
                        if (active) {
                            Surface(
                                shape = MoReadTokens.CapsuleShape,
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "伴读中",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    val subtitle = persona.subtitle.ifBlank {
                        if (persona.isRoleplay) "角色扮演" else "工具助手"
                    }
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                FrostedSurface(shape = CircleShape, shadowElevation = 2.dp) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Outlined.Edit,
                            contentDescription = "编辑角色",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Text(
                text = persona.personality,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 11.dp)
            )
            Row(
                modifier = Modifier.padding(top = 11.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricPill(if (persona.isRoleplay) "扮演型" else "工具型")
                MetricPill("世界书 $worldBookCount 条")
                MetricPill("记忆 $memoryCount 段")
            }
        }
    }
}

@Composable
private fun MetricPill(text: String) {
    Surface(
        shape = MoReadTokens.CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}
