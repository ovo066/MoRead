package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.knowledge.*

/** Explicit fields stay attached to their evidence, including different ages at different times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CharacterProfileDetails(person: BookCharacter, expanded: Boolean, palette: ReaderPalette,
    onEvidence: (CharacterEvidence) -> Unit) {
    if (person.attributes.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            person.attributes.let { if (expanded) it else it.take(6) }.forEach { attribute ->
                Surface(onClick = { onEvidence(attribute.evidence) }, shape = RoundedCornerShape(8.dp), color = palette.accentContainer) {
                    Text("${attribute.kind.label()} · ${attribute.value}", color = palette.accent,
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
        }
    }
    val relations = if (expanded) person.relationships else person.relationships.take(2)
    if (relations.isNotEmpty()) {
        Text("人物关系", style = MaterialTheme.typography.labelMedium, color = palette.muted)
        relations.forEach { relationship ->
            Surface(onClick = { onEvidence(relationship.evidence) }, color = palette.background.copy(alpha = .5f),
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().testTag("relation-${person.identity}-${relationship.target}")) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(person.name, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = palette.onBackground, style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.weight(1.2f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(relationship.relation, color = palette.accent, style = MaterialTheme.typography.labelMedium)
                        Text("──────→", color = palette.accent, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(relationship.target, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = palette.onBackground, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (expanded) Text("第 ${relationship.evidence.chapterIndex + 1} 章 · ${relationship.evidence.fact.quote}",
                color = palette.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
