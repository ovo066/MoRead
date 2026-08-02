package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.theme.onAccent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The combined typography panel from the approved visual proposal: font-size stepper, line/margin
 * presets, typeface, theme swatches, page-turn animation and keep-screen-on — one glass sheet
 * replacing the previous separate 排版 and 翻页 sheets.
 */
@Composable
fun ReaderTypographySheet(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onFontScaleChange: (Float) -> Unit,
    onFontChange: (ReaderFont) -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onPageMarginChange: (Float) -> Unit,
    onThemeChange: (ReaderTheme) -> Unit,
    onCustomThemeSelect: (Long) -> Unit,
    onSaveCustomTheme: (CustomReaderTheme) -> Unit,
    onDeleteCustomTheme: (Long) -> Unit,
    onAnimationChange: (PageTurnAnimation) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit
) {
    var editorDraft by remember { mutableStateOf<CustomReaderTheme?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("排版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)

        SheetRow(label = "字号", palette = palette) {
            val sizeSp = (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt()
            StepButton(text = "A−", palette = palette, modifier = Modifier.weight(1f)) {
                onFontScaleChange(((sizeSp - 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f))
            }
            Text(
                text = sizeSp.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(44.dp)
            )
            StepButton(text = "A＋", palette = palette, modifier = Modifier.weight(1f)) {
                onFontScaleChange(((sizeSp + 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f))
            }
        }

        SheetRow(label = "行距", palette = palette) {
            LINE_HEIGHT_PRESETS.forEach { (label, value) ->
                SegChip(
                    text = label,
                    selected = abs(settings.lineHeight - value) < 0.049f,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) { onLineHeightChange(value) }
            }
        }
        Slider(
            value = settings.lineHeight,
            onValueChange = onLineHeightChange,
            valueRange = 1f..2.2f,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.muted.copy(alpha = 0.18f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .padding(horizontal = 2.dp)
        )

        SheetRow(label = "边距", palette = palette) {
            MARGIN_PRESETS.forEach { (label, value) ->
                SegChip(
                    text = label,
                    selected = abs(settings.pageMargin - value) < 0.11f,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) { onPageMarginChange(value) }
            }
        }

        SheetRow(label = "字体", palette = palette) {
            ReaderFont.entries.forEach { font ->
                SegChip(
                    text = font.shortLabel(),
                    selected = settings.font == font,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) { onFontChange(font) }
            }
        }

        SheetRow(label = "主题", palette = palette) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ReaderTheme.entries.forEach { theme ->
                    ThemeSwatch(
                        theme = theme,
                        selected = settings.activeCustomThemeId == null && settings.theme == theme,
                        palette = palette
                    ) { onThemeChange(theme) }
                }
                settings.customThemes.forEach { custom ->
                    val selected = settings.activeCustomThemeId == custom.id
                    CustomThemeSwatch(
                        theme = custom,
                        selected = selected,
                        palette = palette
                    ) {
                        // 已选中的自定义主题再点一次进入编辑。
                        if (selected) editorDraft = custom else onCustomThemeSelect(custom.id)
                    }
                }
                AddThemeSwatch(palette = palette) {
                    editorDraft = CustomReaderTheme(
                        id = 0L,
                        name = "自定义 ${settings.customThemes.size + 1}",
                        backgroundArgb = palette.background.toArgb(),
                        textArgb = palette.onBackground.toArgb(),
                        accentArgb = palette.accent.toArgb()
                    )
                }
            }
        }

        SheetRow(label = "翻页", palette = palette) {
            PageTurnAnimation.entries.forEach { animation ->
                SegChip(
                    text = animation.shortLabel(),
                    selected = settings.pageTurnAnimation == animation,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) { onAnimationChange(animation) }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "阅读时保持亮屏",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = settings.keepScreenOn,
                onCheckedChange = onKeepScreenOnChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = palette.accent,
                    checkedThumbColor = palette.onAccent
                )
            )
        }
    }

    editorDraft?.let { draft ->
        CustomThemeEditorDialog(
            initial = draft,
            onDismiss = { editorDraft = null },
            onSave = { theme ->
                onSaveCustomTheme(theme)
                editorDraft = null
            },
            onDelete = if (draft.id != 0L) {
                {
                    onDeleteCustomTheme(draft.id)
                    editorDraft = null
                }
            } else {
                null
            }
        )
    }
}

@Composable
private fun SheetRow(
    label: String,
    palette: ReaderPalette,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
            modifier = Modifier.width(34.dp)
        )
        content()
    }
}

@Composable
private fun SegChip(
    text: String,
    selected: Boolean,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = if (selected) palette.accentContainer else Color.Transparent,
        contentColor = if (selected) palette.accent else palette.muted,
        border = BorderStroke(
            1.dp,
            if (selected) palette.accent.copy(alpha = 0.3f) else palette.glassBorder
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun StepButton(
    text: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier,
        onClick = onClick,
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 8.dp)
        )
    }
}

@Composable
private fun ThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val swatchBrush = when (theme) {
        // 跟随系统：日/夜两个底色的硬分割。取值直接从调色板来，不再另写字面量。
        ReaderTheme.SYSTEM -> {
            val light = readerPalette(ReaderTheme.LIGHT, systemDark = false, palette.accent)
                .background
            val dark = readerPalette(ReaderTheme.DARK, systemDark = true, palette.accent)
                .background
            Brush.linearGradient(
                0f to light,
                0.5f to light,
                0.5f to dark,
                1f to dark
            )
        }
        else -> {
            val color = readerPalette(theme, systemDark = false, palette.accent).background
            Brush.linearGradient(listOf(color, color))
        }
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(swatchBrush, CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) palette.accent else palette.glassBorder,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = theme.swatchLabel(),
                tint = palette.accent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

private val LINE_HEIGHT_PRESETS = listOf("紧凑" to 1.4f, "标准" to 1.7f, "宽松" to 2.0f)
private val MARGIN_PRESETS = listOf("窄" to 0.4f, "标准" to 1.0f, "宽" to 1.8f)

private fun ReaderFont.shortLabel(): String = when (this) {
    ReaderFont.SYSTEM -> "默认"
    ReaderFont.SERIF -> "宋体"
    ReaderFont.SANS_SERIF -> "黑体"
    ReaderFont.MONOSPACE -> "等宽"
}

private fun PageTurnAnimation.shortLabel(): String = when (this) {
    PageTurnAnimation.SIMULATION -> "仿真"
    PageTurnAnimation.COVER -> "覆盖"
    PageTurnAnimation.SLIDE -> "平移"
    PageTurnAnimation.NONE -> "无"
}

private fun ReaderTheme.swatchLabel(): String = when (this) {
    ReaderTheme.SYSTEM -> "跟随系统"
    ReaderTheme.LIGHT -> "日间"
    ReaderTheme.DARK -> "夜间"
    ReaderTheme.PAPER -> "纸张"
    ReaderTheme.EYE_CARE -> "护眼"
    ReaderTheme.AMOLED -> "深空"
    ReaderTheme.MIST -> "青简"
}

/** 自定义主题色板：背景色打底、右下角强调色小点；选中后再点进入编辑。 */
@Composable
private fun CustomThemeSwatch(
    theme: CustomReaderTheme,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val background = Color(theme.backgroundArgb)
    val accent = Color(theme.accentArgb)
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(background, CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) palette.accent else palette.glassBorder,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "编辑「${theme.name}」",
                tint = Color(theme.textArgb),
                modifier = Modifier.size(13.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(9.dp)
                .align(Alignment.BottomEnd)
                .background(accent, CircleShape)
                .border(1.dp, background, CircleShape)
        )
    }
}

@Composable
private fun AddThemeSwatch(palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .border(1.dp, palette.glassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = "新建自定义主题",
            tint = palette.muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

private enum class ThemeColorTarget(val label: String) {
    BACKGROUND("背景"),
    TEXT("正文"),
    ACCENT("强调")
}

/** 三色编辑器：实时预览 + 目标切换 + RGB 滑条；保存即应用。 */
@Composable
private fun CustomThemeEditorDialog(
    initial: CustomReaderTheme,
    onDismiss: () -> Unit,
    onSave: (CustomReaderTheme) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var background by remember { mutableStateOf(Color(initial.backgroundArgb)) }
    var text by remember { mutableStateOf(Color(initial.textArgb)) }
    var accent by remember { mutableStateOf(Color(initial.accentArgb)) }
    var target by remember { mutableStateOf(ThemeColorTarget.BACKGROUND) }

    val current = when (target) {
        ThemeColorTarget.BACKGROUND -> background
        ThemeColorTarget.TEXT -> text
        ThemeColorTarget.ACCENT -> accent
    }
    fun update(color: Color) = when (target) {
        ThemeColorTarget.BACKGROUND -> background = color
        ThemeColorTarget.TEXT -> text = color
        ThemeColorTarget.ACCENT -> accent = color
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "新建阅读主题" else "编辑阅读主题") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("方案名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 实时预览：正文样张 + 强调胶囊，所见即所得。
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .background(background, RoundedCornerShape(14.dp))
                        .border(1.dp, text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "春江潮水连海平，海上明月共潮生。",
                        color = text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Surface(color = accent, shape = RoundedCornerShape(9.dp)) {
                        Text(
                            text = "强调色",
                            color = accent.onAccent(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeColorTarget.entries.forEach { candidate ->
                        val dotColor = when (candidate) {
                            ThemeColorTarget.BACKGROUND -> background
                            ThemeColorTarget.TEXT -> text
                            ThemeColorTarget.ACCENT -> accent
                        }
                        FilterChip(
                            selected = target == candidate,
                            onClick = { target = candidate },
                            label = { Text(candidate.label) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(dotColor, CircleShape)
                                        .border(
                                            1.dp,
                                            MaterialTheme.colorScheme.outlineVariant,
                                            CircleShape
                                        )
                                )
                            }
                        )
                    }
                }
                EditorChannelSlider("红", current.red) { update(current.copy(red = it)) }
                EditorChannelSlider("绿", current.green) { update(current.copy(green = it)) }
                EditorChannelSlider("蓝", current.blue) { update(current.copy(blue = it)) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CustomReaderTheme(
                            id = initial.id,
                            name = name.trim().ifBlank { "自定义主题" },
                            backgroundArgb = background.toArgb(),
                            textArgb = text.toArgb(),
                            accentArgb = accent.toArgb()
                        )
                    )
                }
            ) { Text("保存并应用") }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@Composable
private fun EditorChannelSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(20.dp)
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${(value * 255).roundToInt()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp)
        )
    }
}
