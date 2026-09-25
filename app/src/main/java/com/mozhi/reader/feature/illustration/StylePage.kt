package com.mozhi.reader.feature.illustration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import com.mozhi.reader.ai.media.IMAGE_STYLE_PRESETS
import com.mozhi.reader.ai.media.ImageRecipeCodec
import com.mozhi.reader.ai.media.StyleSpec
import com.mozhi.reader.core.database.entity.ImageStyleTemplateEntity
import com.mozhi.reader.ui.components.*
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.moReadMetrics

/**
 * 画风页：预设与「我的画风」是同一种卡片、同一张网格；新建永远是最后一张虚线卡。
 * 这与系统相册滤镜、Keynote / Canva 模板选择器的习惯一致——自定义内容排在内置内容之后，
 * 「添加」出现在列表末尾，而不是藏在网格上方的导航行或另一页里。
 */
internal fun LazyListScope.stylePage(state: StudioState, actions: StudioActions) {
    val draft = state.styleDraft
    item("presets-label") { StudioSectionLabel(stringResource(R.string.image_studio_presets)) }
    items(IMAGE_STYLE_PRESETS.chunked(2), key = { "preset-${it.first().id}" }) { row ->
        GridRow {
            row.forEach { preset ->
                val spec = StyleSpec(presetId = preset.id, natural = preset.natural, tags = preset.tags)
                StyleCard(styleName(preset.id), styleHint(preset.id), selected = draft.isPreset(preset.id), inUse = state.style.isPreset(preset.id),
                    onClick = { actions.style(spec) }, modifier = Modifier.weight(1f)) { StyleSample(preset.id, it) }
            }
        }
    }
    item("mine-label") { StudioSectionLabel(stringResource(R.string.image_studio_templates), Modifier.padding(top = MoReadSpacing.s)) {
        Hint(stringResource(R.string.image_studio_my_styles_hint))
    } }
    val templates = state.styleTemplates.mapNotNull { template ->
        runCatching { template to ImageRecipeCodec.json.decodeFromString<StyleSpec>(template.specJson) }.getOrNull()
    }
    val cells: List<Pair<ImageStyleTemplateEntity, StyleSpec>?> = templates + listOf(null)
    items(cells.chunked(2), key = { row -> "mine-${row.first()?.first?.id ?: "new"}" }) { row ->
        GridRow {
            row.forEach { cell ->
                if (cell == null) NewStyleCard(state, actions, Modifier.weight(1f))
                else TemplateCard(cell.first, cell.second, state, actions, Modifier.weight(1f))
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

/** 自定义画风的 presetId 恒为 custom，所以预设卡只认 presetId；预设上追加的参考图不影响归属。 */
private fun StyleSpec.isPreset(id: String): Boolean = presetId == id

@Composable
private fun GridRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m), content = content)

/** 画风卡：上图下文；选中是一圈强调色描边 + 右上角对勾，正在使用的另标一枚小胶囊。 */
@Composable
private fun StyleCard(title: String, subtitle: String, selected: Boolean, inUse: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, image: @Composable (Modifier) -> Unit) {
    Surface(onClick = onClick, modifier = modifier.fillMaxHeight(), shape = moReadMetrics().cardShape,
        color = studioCardColor(), border = studioCardBorder(selected)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f)) {
                image(Modifier.fillMaxSize())
                if (inUse) ImageBadge(stringResource(R.string.image_studio_current_style), Modifier.align(Alignment.BottomStart).padding(MoReadSpacing.s))
                if (selected) Box(Modifier.align(Alignment.TopEnd).padding(MoReadSpacing.s).size(24.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            Row(Modifier.padding(start = MoReadSpacing.m, top = MoReadSpacing.s + 2.dp, bottom = MoReadSpacing.m, end = if (trailing == null) MoReadSpacing.m else 0.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
                trailing?.invoke()
            }
        }
    }
}

@Composable
private fun TemplateCard(template: ImageStyleTemplateEntity, style: StyleSpec, state: StudioState, actions: StudioActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    // 没有参考图时缩略图本身就是描述的文字样张，副标题不再重复一遍。
    val subtitle = if (style.referenceIds.isEmpty()) stringResource(R.string.image_style_custom)
        else style.natural.ifBlank { style.tags }.ifBlank { stringResource(R.string.image_studio_template_empty_look) }
    StyleCard(template.name, subtitle,
        selected = state.styleDraft == style, inUse = state.style == style,
        onClick = { if (!state.busy) actions.applyTemplate(template) }, modifier = modifier.testTag("style-template-${template.id}"),
        trailing = {
            Box {
                IconButton(onClick = { menu = true }, enabled = !state.busy) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.image_studio_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                MoReadDropdownMenu(menu, { menu = false }) {
                    MoReadMenuItem(stringResource(R.string.image_studio_edit_template), { menu = false; actions.editStyle(template) }, icon = Icons.Outlined.Edit)
                    MoReadMenuItem(stringResource(R.string.image_studio_delete_template_action), { menu = false; deleting = true },
                        icon = Icons.Outlined.Delete, destructive = true)
                }
            }
        }) { StyleThumbnail(style, state, it) }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.image_studio_template_delete_title, template.name)) },
        text = { Text(stringResource(R.string.image_studio_template_delete_hint)) },
        confirmButton = { TextButton(onClick = { actions.deleteTemplate(template.id); deleting = false }, enabled = !state.busy) {
            Text(stringResource(R.string.image_studio_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.image_studio_close)) } })
}

/** 最后一张虚线卡：点开问一句「从空白开始，还是以当前选中的画风为基础」。 */
@Composable
private fun NewStyleCard(state: StudioState, actions: StudioActions, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier.fillMaxHeight()) {
        AddTile(stringResource(R.string.image_studio_new_style), Modifier.fillMaxWidth().fillMaxHeight().heightIn(min = 180.dp).testTag("new-style-entry"),
            enabled = !state.busy, subtitle = stringResource(R.string.image_studio_new_style_hint)) { menu = true }
        MoReadDropdownMenu(menu, { menu = false }) {
            MoReadMenuItem(stringResource(R.string.image_studio_new_blank), { menu = false; actions.newStyle() }, icon = Icons.Outlined.Add,
                modifier = Modifier.testTag("new-style-blank"))
            MoReadMenuItem(stringResource(R.string.image_studio_new_from, selectedStyleName(state)), { menu = false; actions.copyStyle() },
                icon = Icons.Outlined.ContentCopy)
        }
    }
}

/** 底栏：未改动时显示「正在使用」且不可点；改动后才出现「使用此画风」。试一张永远在旁边。 */
@Composable
internal fun StyleBottomBar(state: StudioState, actions: StudioActions, modifier: Modifier) {
    val changed = state.styleDraft != state.style
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MoReadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
        MoReadButton(stringResource(R.string.image_studio_sample), actions.sample, style = MoReadButtonStyle.Tonal,
            icon = Icons.Outlined.AutoAwesome, enabled = !state.busy && state.configured)
        MoReadButton(stringResource(if (changed) R.string.image_studio_use_style else R.string.image_studio_current_style), actions.saveStyle,
            Modifier.weight(1f), enabled = changed && !state.busy && !state.loading)
    }
}
