package com.mozhi.reader.feature.review

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage

/** Roles are people to choose by avatar; invitation is a separate, contained action. */
@Composable
internal fun ReviewCompanionPanel(personas: List<PersonaEntity>, selectedId: Long?, onSelect: (Long) -> Unit,
    invited: Boolean = true, onInviteChange: ((Boolean) -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().testTag("review-companion-panel")) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("伴读", style = MaterialTheme.typography.titleSmall)
                }
                onInviteChange?.let { toggle ->
                    FilledTonalButton(onClick = { toggle(!invited) }, enabled = personas.isNotEmpty(), shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        modifier = Modifier.heightIn(min = 40.dp).testTag("review-invite-ai"),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) {
                        Text(if (invited) "取消邀请" else "邀请 AI", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (personas.isEmpty()) Text("还没有伴读角色，可以先到伴读页创建。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (invited) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                personas.forEach { persona ->
                    val chosen = selectedId == persona.id
                    Column(Modifier.width(64.dp).clip(MaterialTheme.shapes.small).clickable { onSelect(persona.id) }
                        .semantics { contentDescription = "选择伴读 ${persona.name}"; selected = chosen }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(54.dp).border(if (chosen) 2.dp else 0.dp,
                            if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape).padding(5.dp)) {
                            PersonaAvatarImage(persona.name, persona.avatarPath, Modifier.fillMaxSize(), MaterialTheme.typography.titleMedium)
                        }
                        Text(persona.name, style = MaterialTheme.typography.labelMedium,
                            color = if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
