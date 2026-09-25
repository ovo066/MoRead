package com.mozhi.reader.feature.illustration

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.R
import com.mozhi.reader.ai.media.*
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.IllustrationQueueEntity
import com.mozhi.reader.core.database.entity.ImageStyleTemplateEntity
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.fieldContainerColor
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.sectionCardColor
import com.mozhi.reader.ui.theme.sectionHairline
import java.io.File

internal data class StudioActions(
    val page: (StudioPage) -> Unit = {}, val back: () -> Unit = {}, val close: () -> Unit = {},
    val style: (StyleSpec) -> Unit = {}, val openStyle: () -> Unit = {}, val saveStyle: () -> Unit = {},
    val openLook: (String) -> Unit = {}, val look: (LookSpec) -> Unit = {}, val saveLook: () -> Unit = {}, val newVersion: (Int) -> Unit = {},
    val upload: (Boolean) -> Unit = {}, val portrait: () -> Unit = {},
    val source: (String) -> Unit = {}, val cast: (String) -> Unit = {}, val count: (Int) -> Unit = {},
    val size: (Int) -> Unit = {}, val references: (Boolean) -> Unit = {}, val prompt: (String) -> Unit = {},
    val prepare: () -> Unit = {}, val generate: (Boolean) -> Unit = {}, val keep: () -> Unit = {},
    val select: (Int) -> Unit = {}, val adjust: () -> Unit = {}, val openResult: (IllustrationEntity) -> Unit = {},
    val newGeneration: () -> Unit = {}, val cancel: () -> Unit = {}, val settings: () -> Unit = {},
    val asStyle: () -> Unit = {}, val asLook: (String) -> Unit = {}, val asCover: (String) -> Unit = {},
    val asAvatar: (String, Long) -> Unit = { _, _ -> }, val delete: (IllustrationEntity) -> Unit = {},
    val planQueue: (Int, Int) -> Unit = { _, _ -> }, val startQueue: () -> Unit = {}, val pauseQueue: () -> Unit = {},
    val retry: (IllustrationQueueEntity) -> Unit = {}, val removeQueue: (String) -> Unit = {},
    val queueRecipe: (String, ImageRecipe) -> Unit = { _, _ -> },
    val evidence: (LookAttribute) -> Unit = {}, val sample: () -> Unit = {},
    val extractAppearance: () -> Unit = {}, val translateAppearance: () -> Unit = {},
    val newStyle: () -> Unit = {}, val copyStyle: () -> Unit = {}, val editStyle: (ImageStyleTemplateEntity) -> Unit = {},
    val styleEditor: (StyleSpec) -> Unit = {}, val styleEditorName: (String) -> Unit = {}, val saveStyleEditor: () -> Unit = {},
    val applyTemplate: (ImageStyleTemplateEntity) -> Unit = {}, val deleteTemplate: (String) -> Unit = {}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageStudio(bookId: Long, entry: StudioEntry = StudioEntry(), onDismiss: () -> Unit,
    onEvidence: ((LookAttribute) -> Unit)? = null,
    viewModel: ImageStudioViewModel = hiltViewModel(key = "image-studio-$bookId")) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(bookId, entry) { viewModel.enter(bookId, entry) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("image-studio-notices", 0) }
    var uploadStyle by remember { mutableStateOf(false) }
    var rights by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(false) }
    var evidence by remember { mutableStateOf<LookAttribute?>(null) }
    val pageStates = rememberSaveableStateHolder()
    val generationList = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.importReference(it, uploadStyle) }
    }
    val close: () -> Unit = {
        when {
            state.page == StudioPage.GENERATE && state.stylePreviewOrigin != null -> viewModel.leaveStylePreview()
            state.page == StudioPage.GENERATE && state.panelReturn != null -> viewModel.leavePanel()
            else -> onDismiss()
        }
    }
    val actions = StudioActions(viewModel::page, viewModel::back, close, viewModel::style, viewModel::openStyle,
        viewModel::saveStyle, viewModel::openLook, viewModel::look, viewModel::saveLook, viewModel::newLookVersion,
        upload = { style -> uploadStyle = style; if (preferences.getBoolean("rights", false)) picker.launch("image/*") else rights = true },
        portrait = viewModel::portrait, source = viewModel::source, cast = viewModel::cast, count = viewModel::count,
        size = viewModel::size, references = viewModel::references, prompt = viewModel::prompt, prepare = viewModel::preparePrompt,
        generate = viewModel::generate, keep = viewModel::saveResult, select = viewModel::select, adjust = viewModel::adjust,
        openResult = viewModel::openResult, newGeneration = viewModel::newGeneration, cancel = viewModel::cancel,
        settings = { settings = true }, asStyle = viewModel::useResultAsStyle, asLook = viewModel::useResultAsLook,
        asCover = viewModel::useAsCover, asAvatar = viewModel::useAsAvatar, delete = viewModel::delete,
        planQueue = viewModel::planQueue, startQueue = viewModel::startQueue, pauseQueue = viewModel::pauseQueue,
        retry = viewModel::retry, removeQueue = viewModel::removeQueuePreview, queueRecipe = viewModel::updateQueueRecipe,
        evidence = { if (onEvidence != null) onEvidence(it) else evidence = it }, sample = viewModel::sample,
        extractAppearance = viewModel::extractAppearance, translateAppearance = viewModel::translateAppearance,
        newStyle = viewModel::newStyle, copyStyle = viewModel::copyStyle, editStyle = viewModel::editStyle,
        styleEditor = viewModel::styleEditor, styleEditorName = viewModel::styleEditorName, saveStyleEditor = viewModel::saveStyleEditor,
        applyTemplate = viewModel::applyTemplate, deleteTemplate = viewModel::deleteTemplate)
    // 生成面板是浮层：从画廊、画风或形象页打开时，下面那一页保持可见，关闭后回到原处。
    val basePage = state.basePage
    if (basePage != StudioPage.GENERATE) {
        MoReadPageDialog({
            when {
                state.page == StudioPage.GENERATE -> close()
                state.page == entry.page || state.page == StudioPage.GALLERY -> onDismiss()
                else -> viewModel.back()
            }
        }) {
            ImageStudioPage(state, actions, pageStates, basePage)
        }
    }
    if (state.page == StudioPage.GENERATE) {
        MoReadBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            ImageGenerationPanel(state, actions, Modifier.fillMaxWidth().fillMaxHeight(.9f), generationList)
        }
    }
    if (settings) MoReadPageDialog({ settings = false }) {
        com.mozhi.reader.feature.settings.ImageGenSettingsScreen(onBack = {
            settings = false; viewModel.refreshService()
        })
    }
    if (rights) AlertDialog(onDismissRequest = { rights = false }, text = { Text(stringResource(R.string.image_studio_rights)) },
        confirmButton = { TextButton(onClick = {
            rights = false; preferences.edit().putBoolean("rights", true).apply(); picker.launch("image/*")
        }) { Text(stringResource(R.string.image_studio_continue)) } })
    evidence?.let { attribute -> AlertDialog(onDismissRequest = { evidence = null },
        title = { Text(stringResource(R.string.image_studio_evidence)) },
        text = { Text("${attribute.chapterIndex?.let { stringResource(R.string.image_studio_chapter, it + 1) }.orEmpty()}\n${attribute.quote}") },
        confirmButton = { TextButton(onClick = { evidence = null }) { Text(stringResource(R.string.image_studio_close)) } }) }
}

@Composable
internal fun styleName(id: String): String = stringResource(when (id) {
    "watercolor" -> R.string.image_style_watercolor; "painting" -> R.string.image_style_painting
    "cel" -> R.string.image_style_cel; "ink" -> R.string.image_style_ink; "pencil" -> R.string.image_style_pencil
    "storybook" -> R.string.image_style_storybook; else -> R.string.image_style_custom
})

@Composable
internal fun styleHint(id: String): String = stringResource(when (id) {
    "painting" -> R.string.image_style_painting_hint; "cel" -> R.string.image_style_cel_hint
    "ink" -> R.string.image_style_ink_hint; "pencil" -> R.string.image_style_pencil_hint
    "storybook" -> R.string.image_style_storybook_hint; else -> R.string.image_style_watercolor_hint
})

@Composable
internal fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** 分组标题：与 [MoReadSection] 的节标题同款，但右侧可带计数或次要动作，用于卡片网格上方。 */
@Composable
internal fun StudioSectionLabel(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.xs).heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.invoke(this)
    }
}

@Composable
internal fun ImageStudioPage(state: StudioState, actions: StudioActions,
    holder: androidx.compose.runtime.saveable.SaveableStateHolder = rememberSaveableStateHolder(),
    page: StudioPage = state.basePage) {
    holder.SaveableStateProvider(page) {
        val title = when (page) {
            StudioPage.STYLE -> stringResource(R.string.image_studio_style)
            StudioPage.STYLE_EDITOR -> stringResource(if (state.styleEditorTemplateId == null) R.string.image_studio_new_style else R.string.image_studio_edit_template)
            StudioPage.PEOPLE -> stringResource(R.string.image_studio_people)
            StudioPage.LOOK -> stringResource(R.string.image_studio_look, state.lookDraft?.name.orEmpty())
            StudioPage.QUEUE -> stringResource(R.string.image_studio_queue)
            else -> stringResource(R.string.image_studio_gallery)
        }
        val progress = stringResource(R.string.image_studio_read_progress, state.progress + 1)
        val subtitle = when (page) {
            StudioPage.STYLE_EDITOR -> stringResource(R.string.image_studio_template_scope)
            StudioPage.STYLE -> stringResource(R.string.image_studio_style_subtitle, state.title)
            StudioPage.LOOK -> "${stringResource(R.string.image_studio_people)} · $progress"
            else -> "${state.title} · $progress"
        }
        val navBar = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.m)
        MoReadSecondaryPage(title, onBack = if (page == StudioPage.GALLERY) actions.close else actions.back,
            modifier = if (page == StudioPage.STYLE_EDITOR || page == StudioPage.LOOK) Modifier.imePadding() else Modifier,
            subtitle = subtitle,
            actions = {
                if (page == StudioPage.LOOK) LookMenu(state, actions)
                IconButton(onClick = actions.close) { Icon(Icons.Outlined.Close, stringResource(R.string.image_studio_close)) }
            },
            bottomBar = {
                when (page) {
                    StudioPage.GALLERY -> MoReadButton(stringResource(R.string.image_studio_new), actions.newGeneration, navBar,
                        icon = Icons.Outlined.AutoAwesome, enabled = !state.loading)
                    StudioPage.STYLE -> StyleBottomBar(state, actions, navBar)
                    StudioPage.STYLE_EDITOR -> Row(navBar, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                        MoReadButton(stringResource(R.string.image_studio_sample), actions.sample, style = MoReadButtonStyle.Tonal,
                            icon = Icons.Outlined.AutoAwesome, enabled = !state.busy && state.configured && state.hasStyleEditorContent)
                        MoReadButton(stringResource(R.string.image_studio_save_template_draft), actions.saveStyleEditor, Modifier.weight(1f), enabled = state.canSaveStyleEditor)
                    }
                    StudioPage.LOOK -> MoReadButton(stringResource(R.string.image_studio_save), actions.saveLook, navBar, enabled = !state.busy && !state.loading)
                    else -> Unit
                }
            }) {
            item("progress") {
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            state.error?.let { error -> item("error") { Text(error.asString(), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium) } }
            when (page) {
                StudioPage.STYLE -> stylePage(state, actions)
                StudioPage.STYLE_EDITOR -> styleEditorPage(state, actions)
                StudioPage.LOOK -> lookPage(state, actions)
                StudioPage.PEOPLE -> peoplePage(state, actions)
                StudioPage.QUEUE -> queuePage(state, actions)
                else -> galleryPage(state, actions)
            }
        }
    }
}

/* ============================== 画廊 ============================== */

private fun LazyListScope.galleryPage(state: StudioState, actions: StudioActions) {
    item("entries") { MoReadSection {
        MoReadRow(stringResource(R.string.image_studio_style), subtitle = selectedStyleName(state, state.style),
            onClick = actions.openStyle, icon = Icons.Outlined.Palette)
        MoReadRowDivider()
        val ready = state.people.count { person -> visibleLook(state.looks, person.identity, state.progress)?.referenceIds?.isNotEmpty() == true }
        MoReadRow(stringResource(R.string.image_studio_people), subtitle = stringResource(R.string.image_studio_people_summary, state.people.size, ready),
            onClick = { actions.page(StudioPage.PEOPLE) }, icon = Icons.Outlined.Face)
        MoReadRowDivider()
        val done = state.queue.count { it.status == "done" }
        MoReadRow(stringResource(R.string.image_studio_batch),
            subtitle = if (state.queue.isEmpty()) stringResource(R.string.image_studio_queue_idle)
                else stringResource(R.string.image_studio_queue_progress, done, state.queue.size),
            onClick = { actions.page(StudioPage.QUEUE) }, icon = Icons.Outlined.Collections)
    } }
    if (state.gallery.isEmpty()) {
        item("empty") { MoReadSection {
            Column(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(fieldContainerColor()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Hint(stringResource(R.string.image_studio_empty_gallery))
            }
        } }
        return
    }
    item("gallery-label") { StudioSectionLabel(stringResource(R.string.image_studio_gallery_count, state.gallery.size)) }
    items(state.gallery.chunked(2), key = { row -> "gallery-${row.first().id}" }) { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            row.forEach { result -> IllustrationTile(result, actions, Modifier.weight(1f)) }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** 画廊缩略图：点开进入结果面板（可重来、调整、导出），长按直接删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IllustrationTile(result: IllustrationEntity, actions: StudioActions, modifier: Modifier = Modifier) {
    var deleting by remember { mutableStateOf(false) }
    val shape = moReadMetrics().cardShape
    Box(modifier.aspectRatio(.75f).clip(shape).background(fieldContainerColor())
        .combinedClickable(onClick = { actions.openResult(result) }, onLongClick = { deleting = true })) {
        AsyncImage(File(result.imagePath), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        result.chapterIndex?.let { chapter ->
            ImageBadge(stringResource(R.string.image_studio_chapter, chapter + 1), Modifier.align(Alignment.BottomStart).padding(MoReadSpacing.s))
        }
    }
    if (deleting) DeleteIllustrationDialog({ deleting = false }) { deleting = false; actions.delete(result) }
}

@Composable
internal fun DeleteIllustrationDialog(dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.image_studio_delete_title)) },
        text = { Text(stringResource(R.string.image_studio_delete_hint)) },
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(R.string.image_studio_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.image_studio_close)) } })
}

/** 压在图片上的小标签：半透明深底白字，任何图片上都读得清。 */
@Composable
internal fun ImageBadge(text: String, modifier: Modifier = Modifier, solid: Boolean = false) {
    Text(text, modifier.clip(CircleShape)
        .background(if (solid) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = .48f))
        .padding(horizontal = MoReadSpacing.s, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = if (solid) MaterialTheme.colorScheme.onPrimary else Color.White, maxLines = 1)
}

/* ============================== 人物 ============================== */

private fun LazyListScope.peoplePage(state: StudioState, actions: StudioActions) {
    if (state.people.isEmpty()) { item("empty") { MoReadSection { Hint(stringResource(R.string.image_studio_empty_people), Modifier.padding(MoReadSpacing.l)) } }; return }
    item("people") { MoReadSection {
        state.people.forEachIndexed { index, person ->
            if (index > 0) MoReadRowDivider(inset = 72.dp)
            val look = visibleLook(state.looks, person.identity, state.progress)
            val ready = look?.referenceIds?.isNotEmpty() == true
            Row(Modifier.fillMaxWidth().clickable { actions.openLook(person.identity) }
                .defaultMinSize(minHeight = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                CharacterAvatar(person.name, look?.referenceIds?.firstOrNull(), state, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(person.name, style = MaterialTheme.typography.titleSmall)
                    Hint(stringResource(if (ready) R.string.image_studio_look_ready else R.string.image_studio_look_text))
                }
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } }
}

/* ============================== 形象 ============================== */

private fun LazyListScope.lookPage(state: StudioState, actions: StudioActions) {
    val look = state.lookDraft ?: return
    item("references") { LookReferences(look, state, actions) }
    item("appearance") { MoReadSection(title = stringResource(R.string.image_studio_appearance),
        footer = stringResource(R.string.image_studio_fixed_look)) {
        Box(Modifier.padding(start = MoReadSpacing.l, end = MoReadSpacing.l, top = MoReadSpacing.l, bottom = MoReadSpacing.m)) {
            MoReadTextField(look.natural, { actions.look(look.copy(natural = it, tags = "", source = "manual")) },
                placeholder = stringResource(R.string.image_studio_description), singleLine = false, minLines = 3)
        }
        look.attributes.forEach { attr ->
            MoReadRowDivider(inset = MoReadSpacing.l)
            MoReadRow(attr.value, subtitle = attr.label.takeIf { it.isNotBlank() }, onClick = { actions.evidence(attr) }, trailing = {
                attr.chapterIndex?.let { MoReadPill(stringResource(R.string.image_studio_chapter, it + 1)) }
            })
        }
        MoReadRowDivider(inset = MoReadSpacing.l)
        MoReadRow(stringResource(R.string.image_studio_extract_appearance), icon = Icons.Outlined.AutoAwesome,
            onClick = { if (!state.busy) actions.extractAppearance() }, trailing = {})
    } }
    item("versions") { LookVersions(look, state, actions) }
    item("advanced") {
        var expanded by remember { mutableStateOf(false) }
        MoReadSection { MoReadDisclosureRow(stringResource(R.string.image_studio_advanced),
            stringResource(R.string.image_studio_look_advanced_summary, "%.2f".format(look.referenceStrength), "%.2f".format(look.fidelity)),
            expanded, { expanded = !expanded }) {
            StudioSlider(R.string.image_studio_strength, look.referenceStrength) { actions.look(look.copy(referenceStrength = it)) }
            StudioSlider(R.string.image_studio_fidelity, look.fidelity) { actions.look(look.copy(fidelity = it)) }
            MoReadTextField(look.tags, { actions.look(look.copy(tags = it)) }, label = stringResource(R.string.image_studio_tags), singleLine = false, minLines = 3)
            MoReadButton(stringResource(R.string.image_studio_translate_appearance), actions.translateAppearance, style = MoReadButtonStyle.Tonal,
                enabled = !state.busy && look.natural.isNotBlank())
        } }
    }
    val related = state.gallery.filter { image -> ImageRecipeCodec.decode(image.recipeJson)?.cast?.any { it.characterKey == look.characterKey } == true }
    if (related.isNotEmpty()) item("gallery") { Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
        StudioSectionLabel(stringResource(R.string.image_studio_in_gallery)) { Hint(related.size.toString()) }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            items(related, key = { "look-${it.id}" }) { image ->
                AsyncImage(File(image.imagePath), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.width(96.dp).aspectRatio(.75f).clip(moReadMetrics().rowShape)
                        .background(fieldContainerColor()).clickable { actions.openResult(image) })
            }
        }
    } }
}

@Composable
private fun LookReferences(look: LookSpec, state: StudioState, actions: StudioActions) {
    val canGenerate = !state.busy && state.configured && (look.natural.isNotBlank() || look.tags.isNotBlank() || look.referenceIds.isNotEmpty())
    if (look.referenceIds.isEmpty()) {
        // 空态是一张完整的卡：说明为什么要定妆、怎样的图最好，两个动作就在卡里。
        MoReadSection {
            Column(Modifier.fillMaxWidth().padding(MoReadSpacing.xl), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(fieldContainerColor()), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Face, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.image_studio_no_reference), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.image_studio_reference_hint))
                Row(Modifier.padding(top = MoReadSpacing.s), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
                    MoReadButton(stringResource(R.string.image_studio_upload), { actions.upload(false) }, Modifier.weight(1f),
                        style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.AddPhotoAlternate, enabled = !state.busy)
                    MoReadButton(stringResource(R.string.image_studio_from_text), actions.portrait, Modifier.weight(1f),
                        icon = Icons.Outlined.AutoAwesome, enabled = canGenerate)
                }
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
            items(look.referenceIds, key = { it }) { id ->
                ReferenceImage(id, state, primary = id == look.referenceIds.first(), width = 200.dp,
                    onPrimary = { actions.look(look.copy(referenceIds = listOf(id) + (look.referenceIds - id))) },
                    onRemove = { actions.look(look.copy(referenceIds = look.referenceIds - id)) })
            }
            if (look.referenceIds.size < 3) item("add") {
                AddTile(stringResource(R.string.image_studio_add_reference), Modifier.width(120.dp).aspectRatio(.75f), enabled = !state.busy) { actions.upload(false) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            MoReadButton(stringResource(R.string.image_studio_upload), { actions.upload(false) }, Modifier.weight(1f),
                style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.AddPhotoAlternate, enabled = !state.busy && look.referenceIds.size < 3)
            MoReadButton(stringResource(R.string.image_studio_from_text), actions.portrait, Modifier.weight(1f),
                style = MoReadButtonStyle.Tonal, icon = Icons.Outlined.AutoAwesome, enabled = canGenerate)
        }
    }
}

/** 参考图：点一下出菜单（设为主图 / 移除），第一张带「主图」角标。 */
@Composable
internal fun ReferenceImage(id: String, state: StudioState, primary: Boolean, width: Dp,
    onPrimary: (() -> Unit)?, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.width(width).aspectRatio(.75f)) {
        Box(Modifier.fillMaxSize().clip(moReadMetrics().cardShape).background(fieldContainerColor()).clickable { menu = true }) {
            AsyncImage(state.assets.firstOrNull { it.id == id }?.filePath?.let(::File), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (primary && onPrimary != null) ImageBadge(stringResource(R.string.image_studio_primary_badge), Modifier.align(Alignment.TopStart).padding(MoReadSpacing.s), solid = true)
        }
        MoReadDropdownMenu(menu, { menu = false }) {
            if (!primary && onPrimary != null) MoReadMenuItem(stringResource(R.string.image_studio_primary), { menu = false; onPrimary() }, icon = Icons.Outlined.Star)
            MoReadMenuItem(stringResource(R.string.image_studio_remove), { menu = false; onRemove() }, icon = Icons.Outlined.Delete, destructive = true)
        }
    }
}

/** 虚线「添加」格：与相邻卡片同尺寸，一眼看出这里还能放东西。 */
@Composable
internal fun AddTile(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, subtitle: String? = null, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) .7f else .3f)
    val radius = moReadMetrics().radiusCard
    Column(modifier.clip(moReadMetrics().cardShape).clickable(enabled = enabled, onClick = onClick)
        .drawBehind {
            val inset = 1.dp.toPx()
            drawRoundRect(outline, topLeft = androidx.compose.ui.geometry.Offset(inset / 2, inset / 2),
                size = androidx.compose.ui.geometry.Size(size.width - inset, size.height - inset),
                cornerRadius = CornerRadius(radius.toPx()),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
        }.padding(MoReadSpacing.m), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(fieldContainerColor()), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(text, Modifier.padding(top = MoReadSpacing.s), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        if (subtitle != null) Hint(subtitle, Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun LookVersions(look: LookSpec, state: StudioState, actions: StudioActions) {
    var dialog by remember { mutableStateOf<Boolean?>(null) } // true = 新增变化，false = 修改当前版本起始章
    val versions = state.looks.filter { it.characterKey == look.characterKey }
    val visible = (versions.filter { it.sinceChapter <= state.progress && it.id != look.id } + look).sortedBy { it.sinceChapter }
    val future = versions.count { it.sinceChapter > state.progress }
    MoReadSection(title = stringResource(R.string.image_studio_versions)) {
        visible.forEachIndexed { index, version ->
            if (index > 0) MoReadRowDivider(inset = 44.dp)
            val editing = version.id == look.id
            TimelineRow(stringResource(R.string.image_studio_version_since, version.sinceChapter + 1),
                version.natural.ifBlank { stringResource(R.string.image_studio_template_empty_look) }, active = editing,
                onClick = { if (editing) dialog = false else actions.look(version) }) {
                if (editing) MoReadPill(stringResource(R.string.image_studio_editing), container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary)
            }
        }
        if (future > 0) {
            MoReadRowDivider(inset = 44.dp)
            TimelineRow(stringResource(R.string.image_studio_future, future), stringResource(R.string.image_studio_future_locked),
                active = false, locked = true, onClick = null)
        }
        MoReadRowDivider(inset = MoReadSpacing.l)
        MoReadRow(stringResource(R.string.image_studio_new_version), icon = Icons.Outlined.Add, onClick = { dialog = true }, trailing = {})
    }
    dialog?.let { creating ->
        var text by remember { mutableStateOf(((if (creating) state.progress else look.sinceChapter) + 1).toString()) }
        val chapter = text.toIntOrNull()?.takeIf { it in 1..state.progress + 1 }
        AlertDialog(onDismissRequest = { dialog = null },
            title = { Text(stringResource(if (creating) R.string.image_studio_version_dialog else R.string.image_studio_change_since)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                Hint(stringResource(R.string.image_studio_version_chapter_hint, state.progress + 1))
                MoReadTextField(text, { text = it.filter(Char::isDigit).take(6) }, label = stringResource(R.string.image_studio_since), isError = chapter == null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
            } },
            confirmButton = { TextButton(enabled = chapter != null, onClick = {
                val index = chapter!! - 1
                if (creating) actions.newVersion(index) else actions.look(look.copy(sinceChapter = index))
                dialog = null
            }) { Text(stringResource(R.string.image_studio_done)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.image_studio_close)) } })
    }
}

@Composable
private fun TimelineRow(title: String, subtitle: String, active: Boolean, locked: Boolean = false,
    onClick: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .defaultMinSize(minHeight = moReadMetrics().rowMinHeight).padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m),
        verticalAlignment = Alignment.CenterVertically) {
        val dot = MaterialTheme.colorScheme.primary
        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
            when {
                locked -> Icon(Icons.Outlined.Lock, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                active -> Box(Modifier.size(16.dp).clip(CircleShape).background(dot.copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                }
                else -> Box(Modifier.padding(start = 4.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
            }
        }
        Column(Modifier.weight(1f).padding(end = MoReadSpacing.s)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = if (locked) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        trailing()
    }
}

/** 形象页右上角：把定妆图用于封面、伴读头像这类「去别处」的动作收进菜单，不占正文。 */
@Composable
private fun LookMenu(state: StudioState, actions: StudioActions) {
    val path = state.lookDraft?.referenceIds?.firstOrNull()?.let { id -> state.assets.firstOrNull { it.id == id }?.filePath } ?: return
    var menu by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.image_studio_more)) }
        MoReadDropdownMenu(menu, { menu = false }) {
            MoReadMenuItem(stringResource(R.string.image_studio_as_cover), { menu = false; actions.asCover(path) }, icon = Icons.Outlined.Book)
            if (state.personas.isNotEmpty()) MoReadMenuItem(stringResource(R.string.image_studio_as_avatar), { menu = false; avatar = true }, icon = Icons.Outlined.AccountCircle)
        }
    }
    if (avatar) AvatarPickerDialog(state, { avatar = false }) { id -> avatar = false; actions.asAvatar(path, id) }
}

@Composable
internal fun StudioSlider(label: Int, value: Float, change: (Float) -> Unit) =
    MoReadSlider(stringResource(label), "%.2f".format(value), value, 0f..1f, .05f, change)

@Composable
internal fun CharacterAvatar(name: String, reference: String?, state: StudioState, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val path = state.assets.firstOrNull { it.id == reference }?.filePath
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
        Text(name.take(1), style = if (size < 36.dp) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
        if (path != null) AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun StyleSample(id: String, modifier: Modifier) {
    val image = when (id) {
        "painting" -> R.drawable.image_style_painting
        "cel" -> R.drawable.image_style_cel
        "ink" -> R.drawable.image_style_ink
        "pencil" -> R.drawable.image_style_pencil
        "storybook" -> R.drawable.image_style_storybook
        else -> R.drawable.image_style_watercolor
    }
    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(image), contentDescription = null,
        modifier = modifier, contentScale = ContentScale.Crop)
}

/** 画风缩略图：自定义画风优先用它的第一张参考图，没有时回落到它所基于的预设样张。 */
@Composable
internal fun StyleThumbnail(style: StyleSpec, state: StudioState, modifier: Modifier) {
    val path = style.referenceIds.firstOrNull()?.let { id -> state.assets.firstOrNull { it.id == id }?.filePath }
    when {
        path != null -> AsyncImage(File(path), null, contentScale = ContentScale.Crop, modifier = modifier.background(fieldContainerColor()))
        style.presetId in IMAGE_STYLE_PRESETS.map { it.id } -> StyleSample(style.presetId, modifier)
        // 没有参考图的自定义画风：把它的描述排成一张「文字样张」，比一枚空图标更能认出是哪一套。
        else -> Box(modifier.background(fieldContainerColor()).padding(MoReadSpacing.m)) {
            Icon(Icons.Outlined.Palette, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(style.natural.ifBlank { style.tags }, Modifier.align(Alignment.BottomStart), style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun selectedStyleName(state: StudioState, style: StyleSpec = state.styleDraft): String = state.styleTemplates.firstOrNull { template ->
    runCatching { ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson) == style }.getOrDefault(false)
}?.name ?: styleName(style.presetId)

@Composable
internal fun AvatarPickerDialog(state: StudioState, dismiss: () -> Unit, pick: (Long) -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.image_studio_as_avatar)) },
        text = { LazyColumn(Modifier.heightIn(max = 440.dp)) { items(state.personas, key = { it.id }) { persona ->
            Row(Modifier.fillMaxWidth().clip(moReadMetrics().rowShape).clickable { pick(persona.id) }.padding(vertical = MoReadSpacing.s),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m)) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Text(persona.name.take(1))
                    persona.avatarPath?.let { AsyncImage(File(it), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Text(persona.name, Modifier.weight(1f))
            }
        } } }, confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.image_studio_close)) } })
}

/** 与 [MoReadSection] 同款的素面卡外观，给需要自定义内部布局的卡片（画风卡、结果卡）复用。 */
@Composable
internal fun studioCardBorder(selected: Boolean) = androidx.compose.foundation.BorderStroke(
    if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else sectionHairline())

@Composable
internal fun studioCardColor() = sectionCardColor()
