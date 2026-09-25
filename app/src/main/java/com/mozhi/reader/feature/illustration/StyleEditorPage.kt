package com.mozhi.reader.feature.illustration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.MoReadSpacing

internal fun LazyListScope.styleEditorPage(state: StudioState, actions: StudioActions) {
    val draft = state.styleEditorDraft
    item("basics") { MoReadSection {
        Column(Modifier.padding(MoReadSpacing.l), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.l)) {
            Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.xs)) {
                MoReadTextField(state.styleEditorName, actions.styleEditorName, Modifier.testTag("style-editor-name"),
                    label = stringResource(R.string.image_studio_template_name), placeholder = stringResource(R.string.image_studio_template_name_example),
                    isError = state.duplicateStyleName)
                if (state.duplicateStyleName) Text(stringResource(R.string.image_studio_template_duplicate), color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            MoReadTextField(draft.natural, { actions.styleEditor(draft.copy(natural = it)) },
                Modifier.testTag("style-editor-description"), label = stringResource(R.string.image_studio_style_description),
                placeholder = stringResource(R.string.image_studio_style_example), singleLine = false, minLines = 4)
        }
    } }
    item("references") { Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
        StudioSectionLabel(stringResource(R.string.image_studio_style_references)) { Hint("${draft.referenceIds.size} / 3") }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            items(draft.referenceIds, key = { it }) { id ->
                ReferenceImage(id, state, primary = false, width = 104.dp, onPrimary = null,
                    onRemove = { actions.styleEditor(draft.copy(referenceIds = draft.referenceIds - id)) })
            }
            if (draft.referenceIds.size < 3) item("add") {
                AddTile(stringResource(R.string.image_studio_add_reference), Modifier.width(104.dp).aspectRatio(.75f), enabled = !state.busy) { actions.upload(true) }
            }
        }
        Hint(stringResource(R.string.image_studio_optional_style_refs), Modifier.padding(horizontal = MoReadSpacing.xs))
    } }
    item("advanced") {
        var expanded by rememberSaveable { mutableStateOf(false) }
        MoReadSection(footer = stringResource(R.string.image_studio_template_snapshot)) {
            MoReadDisclosureRow(stringResource(R.string.image_studio_advanced), stringResource(R.string.image_studio_style_advanced_hint),
                expanded, { expanded = !expanded }) {
                MoReadTextField(draft.tags, { actions.styleEditor(draft.copy(tags = it)) },
                    label = stringResource(R.string.image_studio_artist_tags), singleLine = false, minLines = 3)
                MoReadTextField(draft.negative, { actions.styleEditor(draft.copy(negative = it)) },
                    label = stringResource(R.string.image_studio_negative), singleLine = false)
                MoReadSwitchRow(stringResource(R.string.image_studio_seed_toggle), draft.seed != null,
                    { actions.styleEditor(draft.copy(seed = if (it) 42 else null)) }, subtitle = stringResource(R.string.image_studio_seed_hint))
                if (draft.seed != null) MoReadTextField(draft.seed.toString(), { value ->
                    value.toLongOrNull()?.takeIf { it in 0..0xffffffffL }?.let { actions.styleEditor(draft.copy(seed = it)) }
                }, label = stringResource(R.string.image_studio_seed))
                StudioSlider(R.string.image_studio_strength, draft.referenceStrength) { actions.styleEditor(draft.copy(referenceStrength = it)) }
                StudioSlider(R.string.image_studio_extract, draft.informationExtracted) { actions.styleEditor(draft.copy(informationExtracted = it)) }
            }
        }
    }
}
