package com.mozhi.reader.feature.illustration

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.R
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.fieldContainerColor
import com.mozhi.reader.ui.theme.moReadMetrics
import java.io.File

/**
 * 统一生成面板（Image Playground 式「预填 + 确认」）：选段、出场、画风都已填好，用户只做确认。
 * 设置区是一张卡里的三行「标签 · 值」，生成后原地换成候选横滑 + 三个动作。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImageGenerationPanel(state: StudioState, actions: StudioActions, modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()) {
    var castPicker by rememberSaveable { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editingSource by rememberSaveable { mutableStateOf(false) }
    var lookPicker by remember { mutableStateOf(false) }
    val result = state.result
    val showResults = result != null || state.busy
    Column(modifier.testTag("image-generation-panel")) {
        Row(Modifier.fillMaxWidth().padding(start = MoReadSpacing.xl, end = MoReadSpacing.s).height(56.dp).testTag("image-panel-header"),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(if (state.portrait) R.string.image_studio_portrait else R.string.image_studio_generate), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            // 结果的次要动作放在顶栏（与系统图片查看器一致），正文只留「重来 / 调整」两件主动作。
            if (result != null && !state.busy) ResultMenus(state, result, actions) { lookPicker = true }
            IconButton(onClick = actions.close) { Icon(Icons.Outlined.Close, stringResource(R.string.image_studio_close)) }
        }
        // 进度条占固定高度，空闲、进行中、部分结果之间切换时内容不跳。
        Box(Modifier.height(20.dp).fillMaxWidth().padding(horizontal = MoReadSpacing.xl).testTag("image-panel-progress")) {
            if (state.busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Hint(stringResource(R.string.image_studio_working, state.generatedCount, state.count))
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("image-panel-list"), state = listState,
            contentPadding = PaddingValues(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.s),
            verticalArrangement = Arrangement.spacedBy(MoReadSpacing.l)) {
            state.error?.let { error -> item("error") { MoReadSection(footer = stringResource(R.string.image_studio_error_hint)) {
                Row(Modifier.padding(MoReadSpacing.l), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                    Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                    Text(error.asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            } } }
            if (!state.configured && !state.loading) item("service") { MoReadSection {
                MoReadNavRow(stringResource(R.string.image_studio_no_service), actions.settings, subtitle = stringResource(R.string.image_studio_settings), icon = Icons.Outlined.Settings)
            } }
            if (showResults) resultItems(state, actions)
            else {
                item("source") { SceneCard(state, editingSource, { editingSource = !editingSource }, actions) }
                item("settings") { MoReadSection {
                    PanelRow(stringResource(R.string.image_studio_cast)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                            state.selectedCast.forEach { key ->
                                val person = state.people.firstOrNull { it.identity == key }
                                CastChip(person?.name ?: key, visibleLook(state.looks, key, state.chapterIndex)?.referenceIds?.firstOrNull(), state) { actions.cast(key) }
                            }
                            Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { castPicker = true }, contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Add, stringResource(R.string.image_studio_add_cast), Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (state.selectedCast.isEmpty()) Hint(stringResource(R.string.image_studio_cast_empty), Modifier.padding(top = MoReadSpacing.xs))
                    }
                    MoReadRowDivider(inset = MoReadSpacing.l)
                    val style = state.recipe?.style ?: state.style
                    PanelRow(stringResource(R.string.image_studio_style_short), onClick = actions.openStyle) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                            StyleThumbnail(style, state, Modifier.size(36.dp).clip(moReadMetrics().fieldShape))
                            Text(selectedStyleName(state, style), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    MoReadRowDivider(inset = MoReadSpacing.l)
                    PanelRow(stringResource(R.string.image_studio_format)) {
                        val labels = listOf(stringResource(R.string.image_studio_portrait_size), stringResource(R.string.image_studio_square_size),
                            stringResource(R.string.image_studio_landscape_size))
                        MoReadSegmented(listOf(0, 1, 2), state.sizeIndex, actions.size) { labels[it] }
                    }
                    MoReadRowDivider(inset = MoReadSpacing.l)
                    val notices = referenceNoticeTexts(state.referencePlan, state.capabilities, state.count) +
                        listOfNotNull(stringResource(R.string.image_studio_no_look).takeIf { state.currentLooks.any { it.referenceIds.isEmpty() } })
                    MoReadSwitchRow(stringResource(R.string.image_studio_use_references), state.useReferences, actions.references,
                        subtitle = notices.joinToString("\n").ifBlank { null })
                } }
                item("advanced") { MoReadSection {
                    MoReadDisclosureRow(stringResource(R.string.image_studio_advanced),
                        stringResource(R.string.image_studio_panel_advanced_summary, state.count), advanced, { advanced = !advanced }) {
                        Text(stringResource(R.string.image_studio_count), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        MoReadSegmented(listOf(1, 2, 4), state.count, actions.count) { it.toString() }
                        val assembled = state.recipe?.let { RecipeAssembler.assemble(it, state.capabilities).prompt }.orEmpty()
                        MoReadTextField(state.promptOverride.ifBlank { assembled }, actions.prompt, label = stringResource(R.string.image_studio_prompt),
                            singleLine = false, minLines = 4)
                        MoReadButton(stringResource(R.string.image_studio_prepare), actions.prepare, style = MoReadButtonStyle.Tonal,
                            enabled = !state.busy && state.source.isNotBlank() && state.configured)
                    }
                } }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.m).testTag("image-panel-footer"),
            verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            when {
                state.busy -> MoReadButton(stringResource(R.string.image_studio_cancel), actions.cancel, Modifier.fillMaxWidth(), style = MoReadButtonStyle.Tonal)
                result != null -> MoReadButton(stringResource(if (state.portrait) R.string.image_studio_set_look else if (result.id == 0L) R.string.image_studio_keep else R.string.image_studio_kept),
                    actions.keep, Modifier.fillMaxWidth(), icon = if (state.portrait) Icons.Outlined.Face else Icons.Outlined.Collections,
                    enabled = state.portrait || result.id == 0L)
                else -> MoReadButton(stringResource(R.string.image_studio_generate_count, state.count), { actions.generate(false) }, Modifier.fillMaxWidth(),
                    icon = Icons.Outlined.AutoAwesome, enabled = !state.loading && state.configured && state.source.isNotBlank())
            }
        }
    }
    if (castPicker || lookPicker) AlertDialog(onDismissRequest = { castPicker = false; lookPicker = false },
        title = { Text(stringResource(if (lookPicker) R.string.image_studio_set_look else R.string.image_studio_add_cast)) },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) {
            if (state.people.isEmpty()) item { Hint(stringResource(R.string.image_studio_empty_people)) }
            items(state.people, key = { it.identity }) { person ->
                Row(Modifier.fillMaxWidth().clip(moReadMetrics().rowShape)
                    .clickable { if (lookPicker) { actions.asLook(person.identity); lookPicker = false } else actions.cast(person.identity) }
                    .padding(vertical = MoReadSpacing.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                    CharacterAvatar(person.name, visibleLook(state.looks, person.identity, state.chapterIndex)?.referenceIds?.firstOrNull(), state, size = 40.dp)
                    Text(person.name, Modifier.weight(1f))
                    if (!lookPicker) Checkbox(person.identity in state.selectedCast, onCheckedChange = { actions.cast(person.identity) })
                }
            }
        } }, confirmButton = { TextButton(onClick = { castPicker = false; lookPicker = false }) { Text(stringResource(R.string.image_studio_done)) } })
}

/** 左侧窄标签 + 右侧内容的设置行；同一张卡里上下对齐成一条标签轴。 */
@Composable
private fun PanelRow(label: String, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .defaultMinSize(minHeight = moReadMetrics().rowMinHeight).padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(52.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), content = content)
    }
}

@Composable
private fun CastChip(name: String, reference: String?, state: StudioState, remove: () -> Unit) {
    Row(Modifier.height(36.dp).clip(CircleShape).background(fieldContainerColor()).clickable(onClick = remove)
        .padding(start = 4.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
        CharacterAvatar(name, reference, state, size = 28.dp)
        Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        Icon(Icons.Outlined.Close, stringResource(R.string.image_studio_remove), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 选段引用：默认只读、像书摘；点「编辑」原地变成输入框。 */
@Composable
private fun SceneCard(state: StudioState, editing: Boolean, toggle: () -> Unit, actions: StudioActions) {
    MoReadSection {
        Column(Modifier.padding(start = MoReadSpacing.l, end = MoReadSpacing.xs, top = MoReadSpacing.l, bottom = MoReadSpacing.xs)) {
            Box(Modifier.padding(end = MoReadSpacing.m)) {
                if (editing || state.source.isBlank()) MoReadTextField(state.source, actions.source,
                    placeholder = stringResource(R.string.image_studio_scene), singleLine = false, minLines = 3)
                else Text(state.source, style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Hint(stringResource(R.string.image_studio_chapter, state.chapterIndex + 1), Modifier.weight(1f))
                TextButton(onClick = toggle) {
                    Icon(if (editing) Icons.Outlined.Check else Icons.Outlined.Edit, null, Modifier.size(16.dp))
                    Text(stringResource(if (editing) R.string.image_studio_done else R.string.image_studio_edit), Modifier.padding(start = MoReadSpacing.xs))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.resultItems(state: StudioState, actions: StudioActions) {
    item("candidates") {
        LazyRow(Modifier.fillParentMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            items(state.count, key = { it }) { index ->
                val candidate = state.candidates.getOrNull(index)
                val selected = candidate != null && index == state.selectedCandidate && state.count > 1
                // 候选卡按所选画幅占位，解码完成前后尺寸不变；Fit 保证挑图时看得到整张。
                val (width, ratio) = when (state.sizeIndex) { 1 -> 272.dp to 1f; 2 -> 300.dp to 1.5f; else -> 236.dp to .75f }
                Box(Modifier.width(width).aspectRatio(ratio).clip(moReadMetrics().cardShape).background(fieldContainerColor())
                    .border(studioCardBorder(selected), moReadMetrics().cardShape)
                    .clickable(enabled = candidate != null) { actions.select(index) }) {
                    if (candidate != null) AsyncImage(File(candidate.imagePath), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    else if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                    if (selected) Box(Modifier.align(Alignment.TopEnd).padding(MoReadSpacing.s).size(24.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
    }
    val result = state.result ?: return
    item("recipe") {
        val recipe = state.recipe ?: ImageRecipeCodec.decode(result.recipeJson)
        val cast = recipe?.cast?.joinToString(" · ") { it.name }.orEmpty()
        val style = recipe?.style?.let { selectedStyleName(state, it) }.orEmpty()
        Hint(if (cast.isBlank()) style else stringResource(R.string.image_studio_result_summary, style, cast), Modifier.fillMaxWidth())
    }
    item("result-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            MoReadButton(stringResource(R.string.image_studio_reroll), { actions.generate(true) }, Modifier.weight(1f),
                style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.Refresh, enabled = !state.busy)
            MoReadButton(stringResource(R.string.image_studio_adjust), actions.adjust, Modifier.weight(1f),
                style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.Tune, enabled = !state.busy)
        }
    }
}

@Composable
private fun ResultMenus(state: StudioState, result: com.mozhi.reader.core.database.entity.IllustrationEntity, actions: StudioActions, pickLook: () -> Unit) {
    var more by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    ImageExportActions(result.imagePath, menuTrigger = { enabled, _, open ->
        IconButton(onClick = open, enabled = enabled) { Icon(Icons.Outlined.Download, stringResource(R.string.image_studio_export)) }
    })
    Box {
        IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.image_studio_more)) }
        MoReadDropdownMenu(more, { more = false }) {
            if (!state.portrait) MoReadMenuItem(stringResource(R.string.image_studio_set_look), { more = false; pickLook() }, icon = Icons.Outlined.Face)
            MoReadMenuItem(stringResource(R.string.image_studio_as_style), { more = false; actions.asStyle() }, icon = Icons.Outlined.Palette)
            MoReadMenuItem(stringResource(R.string.image_studio_as_cover), { more = false; actions.asCover(result.imagePath) }, icon = Icons.Outlined.Book)
            if (state.personas.isNotEmpty()) MoReadMenuItem(stringResource(R.string.image_studio_as_avatar), { more = false; avatar = true },
                icon = Icons.Outlined.AccountCircle)
            if (result.id != 0L) MoReadMenuItem(stringResource(R.string.image_studio_delete), { more = false; deleting = true },
                icon = Icons.Outlined.Delete, destructive = true)
        }
    }
    if (avatar) AvatarPickerDialog(state, { avatar = false }) { id -> avatar = false; actions.asAvatar(result.imagePath, id) }
    if (deleting) DeleteIllustrationDialog({ deleting = false }) { deleting = false; actions.delete(result); actions.close() }
}

@Composable
internal fun referenceNoticeTexts(plan: ReferencePlan, capabilities: ImageCapabilities, count: Int): List<String> = buildList {
    plan.notices.forEach { notice -> add(stringResource(when (notice) {
        ReferenceNotice.TEXT_ONLY -> R.string.image_studio_text_only
        ReferenceNotice.PRIMARY_CHARACTER_ONLY -> R.string.image_studio_primary_only
        ReferenceNotice.REFERENCE_LIMIT -> R.string.image_studio_ref_limit
        ReferenceNotice.VIBE_WITHOUT_CHARACTER -> R.string.image_studio_vibe_exclusive
        ReferenceNotice.SEED_UNSUPPORTED -> R.string.image_studio_seed_hint
    })) }
    if (plan.extraCostPerImage > 0) add(stringResource(R.string.image_studio_extra_cost, plan.extraCostPerImage * count))
    if (capabilities.vibe && plan.references.any { it.kind == ReferenceKind.STYLE }) add(stringResource(R.string.image_studio_vibe_cost))
}

@Composable
internal fun ReferenceNotices(plan: ReferencePlan, capabilities: ImageCapabilities, count: Int) {
    referenceNoticeTexts(plan, capabilities, count).forEach { Hint(it) }
}
