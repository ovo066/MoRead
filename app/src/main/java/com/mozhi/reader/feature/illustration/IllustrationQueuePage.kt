package com.mozhi.reader.feature.illustration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.entity.IllustrationQueueEntity
import com.mozhi.reader.ui.components.*

internal fun LazyListScope.queuePage(state: StudioState, actions: StudioActions) {
    item("range") {
        var first by rememberSaveable { mutableStateOf("1") }
        var last by rememberSaveable { mutableStateOf(minOf(20, state.progress + 1).toString()) }
        MoReadSection(footer = stringResource(R.string.image_studio_batch_hint)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MoReadTextField(first, { first = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = stringResource(R.string.image_studio_first_chapter))
                    MoReadTextField(last, { last = it.filter(Char::isDigit).take(6) }, Modifier.weight(1f), label = stringResource(R.string.image_studio_last_chapter))
                }
                MoReadButton(stringResource(R.string.image_studio_plan_batch), { actions.planQueue((first.toIntOrNull() ?: 1) - 1, (last.toIntOrNull() ?: 1) - 1) },
                    Modifier.fillMaxWidth(), style = MoReadButtonStyle.Tonal, enabled = !state.busy && !state.queueRunning && state.configured, icon = Icons.Outlined.AutoAwesome)
            }
            if (!state.configured) {
                MoReadRowDivider(inset = 16.dp)
                MoReadNavRow(stringResource(R.string.image_studio_no_service), actions.settings, subtitle = stringResource(R.string.image_studio_settings), icon = Icons.Outlined.Settings)
            }
        }
    }
    items(state.queuePreview, key = { "preview-${it.id}" }) { row -> QueuePreviewCard(row, state, actions) }
    if (state.queuePreview.isNotEmpty()) item("confirm") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val scheduled = state.queuePreview + state.queue.filter { it.status == "pending" }
            val refs = scheduled.mapNotNull { ImageRecipeCodec.decode(it.recipeJson) }
            val cost = refs.sumOf { it.references.count { ref -> ref.kind == ReferenceKind.CHARACTER } * state.capabilities.characterReferenceCost }
            if (cost > 0) Hint(stringResource(R.string.image_studio_extra_cost, cost))
            if (state.capabilities.vibe && refs.any { it.references.any { r -> r.kind == ReferenceKind.STYLE } }) Hint(stringResource(R.string.image_studio_vibe_cost))
            MoReadButton(stringResource(R.string.image_studio_start_batch, scheduled.size), actions.startQueue, Modifier.fillMaxWidth(), enabled = !state.busy && !state.queueRunning)
        }
    }
    if (state.queue.isNotEmpty()) item("status") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.image_studio_queue_progress, state.queue.count { it.status == "done" }, state.queue.size), style = MaterialTheme.typography.titleMedium)
            if (state.queueRunning) MoReadButton(stringResource(R.string.image_studio_pause), actions.pauseQueue, style = MoReadButtonStyle.Tonal)
            else if (state.queue.any { it.status == "pending" }) MoReadButton(stringResource(R.string.image_studio_resume), actions.startQueue, enabled = !state.busy)
        }
    }
    items(state.queue, key = { it.id }) { row -> MoReadSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_studio_chapter, row.chapterIndex + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                val interrupted = row.status == "running" && !state.queueRunning
                MoReadPill(stringResource(when (row.status) { "done" -> R.string.image_studio_queue_done; "failed" -> R.string.image_studio_queue_failed
                    "running" -> if (interrupted) R.string.image_studio_queue_failed else R.string.image_studio_queue_running
                    else -> R.string.image_studio_queue_pending }))
            }
            if (row.status == "running" && !state.queueRunning) Hint(stringResource(R.string.image_studio_queue_interrupted))
            if (row.error.isNotBlank()) Text(row.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!state.queueRunning && row.status in listOf("failed", "running")) MoReadButton(stringResource(R.string.image_studio_retry), { actions.retry(row) }, style = MoReadButtonStyle.Tonal, enabled = !state.busy)
            val image = state.gallery.firstOrNull { it.id == row.illustrationId }
            if (image != null) MoReadNavRow(stringResource(R.string.image_studio_gallery), { actions.openResult(image) }, icon = Icons.Outlined.Image)
        }
    } }
}

@Composable
private fun QueuePreviewCard(row: IllustrationQueueEntity, state: StudioState, actions: StudioActions) {
    val recipe = remember(row.recipeJson) { ImageRecipeCodec.decode(row.recipeJson) } ?: return
    var editing by remember { mutableStateOf(false) }
    MoReadSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.image_studio_chapter, row.chapterIndex + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = { actions.removeQueue(row.id) }) { Icon(Icons.Outlined.Close, stringResource(R.string.image_studio_remove)) }
            }
            Text(recipe.shot.text(), style = MaterialTheme.typography.bodyMedium)
            Hint(recipe.cast.joinToString(" · ") { it.name })
            ReferenceNotices(planReferences(state.capabilities, recipe.cast, recipe.style), state.capabilities, 1)
            MoReadButton(stringResource(R.string.image_studio_adjust), { editing = true }, style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.Edit)
        }
    }
    if (editing) {
        var action by remember { mutableStateOf(recipe.shot.action) }
        var selected by remember { mutableStateOf(recipe.cast.map { it.characterKey }) }
        AlertDialog(onDismissRequest = { editing = false }, title = { Text(stringResource(R.string.image_studio_adjust)) }, text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                item { MoReadTextField(action, { action = it }, label = stringResource(R.string.image_studio_scene), singleLine = false, minLines = 3) }
                items(state.people.filter { person -> person.manualDescription != null ||
                    person.evidence.any { it.chapterIndex <= row.chapterIndex } || person.attributes.any { it.evidence.chapterIndex <= row.chapterIndex } }, key = { it.identity }) { person -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(person.identity in selected, { selected = if (person.identity in selected) selected - person.identity else selected + person.identity })
                    Text(person.name)
                } }
            }
        }, confirmButton = { TextButton(onClick = {
            val cast = selected.mapNotNull { key -> visibleLook(state.looks, key, row.chapterIndex) ?: recipe.cast.firstOrNull { it.characterKey == key }
                ?: state.people.firstOrNull { it.identity == key }?.let { LookSpec("text-$key", key, it.name) } }
            actions.queueRecipe(row.id, recipe.copy(cast = cast, shot = recipe.shot.copy(cast = selected, action = action), references = planReferences(state.capabilities, cast, recipe.style).references))
            editing = false
        }) { Text(stringResource(R.string.image_studio_save)) } }, dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.image_studio_close)) } })
    }
}
