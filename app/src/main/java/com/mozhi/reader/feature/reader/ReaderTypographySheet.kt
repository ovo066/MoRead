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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wallpaper
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxMatchMode
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.theme.onAccent
import coil3.compose.AsyncImage
import java.io.File
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
    onCustomFontSelect: (String) -> Unit,
    onImportFont: () -> Unit,
    onLineHeightChange: (Float) -> Unit,
    onPageMarginLeftChange: (Float) -> Unit,
    onPageMarginRightChange: (Float) -> Unit,
    onPageMarginTopChange: (Float) -> Unit,
    onPageMarginBottomChange: (Float) -> Unit,
    onHeaderMarginTopChange: (Float) -> Unit,
    onFooterMarginBottomChange: (Float) -> Unit,
    onFontWeightChange: (Int) -> Unit,
    onLetterSpacingChange: (Float) -> Unit,
    onParagraphSpacingChange: (Float) -> Unit,
    onFirstLineIndentChange: (Float) -> Unit,
    onTitleScaleChange: (Float) -> Unit,
    onTitleTopSpacingChange: (Float) -> Unit,
    onTitleBottomSpacingChange: (Float) -> Unit,
    onTextJustificationChange: (Boolean) -> Unit,
    onShowHeaderChange: (Boolean) -> Unit,
    onShowFooterChange: (Boolean) -> Unit,
    onThemeChange: (ReaderTheme) -> Unit,
    onCustomThemeSelect: (Long) -> Unit,
    onSaveCustomTheme: (CustomReaderTheme) -> Unit,
    onDeleteCustomTheme: (Long) -> Unit,
    onImportBackground: () -> Unit,
    onBackgroundImageSelect: (String) -> Unit,
    onClearBackground: () -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
    onSyntaxHighlightEnabledChange: (Boolean) -> Unit,
    onSaveSyntaxRule: (ReaderSyntaxRule) -> Unit,
    onDeleteSyntaxRule: (Long) -> Unit,
    onAnimationChange: (PageTurnAnimation) -> Unit,
    onPageModeChange: (PageMode) -> Unit,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onImmersiveReadingChange: (Boolean) -> Unit,
    onVolumeKeysPageTurnChange: (Boolean) -> Unit
) {
    var editorDraft by remember { mutableStateOf<CustomReaderTheme?>(null) }
    var syntaxDraft by remember { mutableStateOf<ReaderSyntaxRule?>(null) }
    var secondaryPage by remember { mutableStateOf<TypographySecondaryPage?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (secondaryPage) {
            TypographySecondaryPage.ADVANCED -> {
                TypographySecondaryHeader("高级排版") { secondaryPage = null }
                AdvancedTypographyEditor(
                    settings = settings,
                    palette = palette,
                    onFontWeightChange = onFontWeightChange,
                    onLetterSpacingChange = onLetterSpacingChange,
                    onParagraphSpacingChange = onParagraphSpacingChange,
                    onFirstLineIndentChange = onFirstLineIndentChange,
                    onTitleScaleChange = onTitleScaleChange,
                    onTitleTopSpacingChange = onTitleTopSpacingChange,
                    onTitleBottomSpacingChange = onTitleBottomSpacingChange,
                    onTextJustificationChange = onTextJustificationChange,
                    onShowHeaderChange = onShowHeaderChange,
                    onShowFooterChange = onShowFooterChange,
                    onHeaderMarginTopChange = onHeaderMarginTopChange,
                    onFooterMarginBottomChange = onFooterMarginBottomChange
                )
            }
            TypographySecondaryPage.SYNTAX -> {
                TypographySecondaryHeader("语法高亮") { secondaryPage = null }
                SyntaxHighlightEditor(
                    settings = settings,
                    palette = palette,
                    onEnabledChange = onSyntaxHighlightEnabledChange,
                    onEdit = { syntaxDraft = it },
                    onAdd = {
                        syntaxDraft = ReaderSyntaxRule(
                            id = 0L,
                            name = "自定义规则",
                            startDelimiter = "",
                            endDelimiter = "",
                            colorArgb = palette.accent.toArgb()
                        )
                    }
                )
            }
            null -> {
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
        TypographyValueSlider(
            label = "自由调整",
            valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.lineHeight),
            value = settings.lineHeight,
            range = 1f..2.2f,
            steps = 23,
            palette = palette,
            onValueChange = onLineHeightChange
        )

        Text("正文边距", style = MaterialTheme.typography.labelMedium, color = palette.muted)
        TypographyValueSlider(
            label = "正文左边距",
            valueText = horizontalMarginText(settings.pageMarginLeft),
            value = settings.pageMarginLeft,
            range = 0f..2f,
            steps = 19,
            palette = palette,
            onValueChange = onPageMarginLeftChange
        )
        TypographyValueSlider(
            label = "正文右边距",
            valueText = horizontalMarginText(settings.pageMarginRight),
            value = settings.pageMarginRight,
            range = 0f..2f,
            steps = 19,
            palette = palette,
            onValueChange = onPageMarginRightChange
        )
        TypographyValueSlider(
            label = "正文上边距",
            valueText = verticalMarginText(settings.pageMarginTop),
            value = settings.pageMarginTop,
            range = 0f..2f,
            steps = 19,
            palette = palette,
            onValueChange = onPageMarginTopChange
        )
        TypographyValueSlider(
            label = "正文下边距",
            valueText = verticalMarginText(settings.pageMarginBottom),
            value = settings.pageMarginBottom,
            range = 0f..2f,
            steps = 19,
            palette = palette,
            onValueChange = onPageMarginBottomChange
        )

        SheetRow(label = "字体", palette = palette) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
                    SegChip(
                        text = font.shortLabel(),
                        selected = settings.font == font,
                        palette = palette
                    ) { onFontChange(font) }
                }
                settings.fontLibrary.forEach { font ->
                    SegChip(
                        text = font.displayName,
                        selected = settings.font == ReaderFont.CUSTOM &&
                            settings.selectedCustomFontId == font.id,
                        palette = palette
                    ) { onCustomFontSelect(font.id) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onImportFont,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(11.dp),
                color = Color.Transparent,
                border = BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("导入 TTF/OTF/TTC", style = MaterialTheme.typography.labelMedium)
                }
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Wallpaper,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(18.dp)
                )
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("阅读背景", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (settings.backgroundImagePath == null) {
                            "使用主题底色"
                        } else {
                            settings.imageLibrary.firstOrNull {
                                it.id == settings.selectedBackgroundImageId
                            }?.displayName ?: "背景图片已启用"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted
                    )
                }
                TextButton(onClick = onImportBackground) {
                    Text("导入")
                }
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().height(98.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    BackgroundChoice(
                        name = "主题底色",
                        imagePath = null,
                        selected = settings.backgroundImagePath == null,
                        palette = palette,
                        onClick = onClearBackground
                    )
                }
                items(settings.imageLibrary, key = { it.id }) { image ->
                    BackgroundChoice(
                        name = image.displayName,
                        imagePath = image.filePath,
                        selected = settings.selectedBackgroundImageId == image.id,
                        palette = palette,
                        onClick = { onBackgroundImageSelect(image.id) }
                    )
                }
            }
            if (settings.backgroundImagePath != null) {
                Text(
                    "背景强度 ${(settings.backgroundImageOpacity * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
                Slider(
                    value = settings.backgroundImageOpacity,
                    onValueChange = onBackgroundOpacityChange,
                    valueRange = 0.05f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent
                    )
                )
            }
        }

        SheetRow(label = "翻页", palette = palette) {
            val paginated = settings.pageMode == PageMode.PAGINATED
            PageTurnAnimation.entries.forEach { animation ->
                SegChip(
                    text = animation.shortLabel(),
                    selected = paginated && settings.pageTurnAnimation == animation,
                    palette = palette,
                    modifier = Modifier.weight(1f)
                ) {
                    onPageModeChange(PageMode.PAGINATED)
                    onAnimationChange(animation)
                }
            }
            // 上下滑动 = 以章节为单位的连续滚动模式，与四种翻页动画互斥。
            SegChip(
                text = "上下",
                selected = settings.pageMode == PageMode.SCROLL,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { onPageModeChange(PageMode.SCROLL) }
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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("完全沉浸", style = MaterialTheme.typography.bodyMedium)
                Text("阅读时隐藏系统状态栏", style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
            Switch(
                checked = settings.immersiveReading,
                onCheckedChange = onImmersiveReadingChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = palette.accent,
                    checkedThumbColor = palette.onAccent
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("音量键翻页", style = MaterialTheme.typography.bodyMedium)
                Text("音量加上一页，音量减下一页", style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
            Switch(
                checked = settings.volumeKeysPageTurn,
                onCheckedChange = onVolumeKeysPageTurnChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = palette.accent,
                    checkedThumbColor = palette.onAccent
                )
            )
        }

        SecondaryPageRow(
            title = "高级排版",
            summary = "字距、段距、缩进、标题、字重与页眉页脚",
            palette = palette
        ) { secondaryPage = TypographySecondaryPage.ADVANCED }
        SecondaryPageRow(
            title = "语法高亮",
            summary = "成对符号、正则与扩展文字样式",
            palette = palette
        ) { secondaryPage = TypographySecondaryPage.SYNTAX }
            }
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
    syntaxDraft?.let { draft ->
        SyntaxRuleEditorDialog(
            initial = draft,
            fontLibrary = settings.fontLibrary,
            onDismiss = { syntaxDraft = null },
            onSave = {
                onSaveSyntaxRule(it)
                syntaxDraft = null
            },
            onDelete = if (draft.id != 0L) {
                {
                    onDeleteSyntaxRule(draft.id)
                    syntaxDraft = null
                }
            } else null
        )
    }
}

@Composable
private fun BackgroundChoice(
    name: String,
    imagePath: String?,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.width(82.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.accentContainer else palette.glass,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) palette.accent else palette.muted.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(58.dp)
                    .background(palette.background, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (imagePath == null) {
                    Icon(
                        Icons.Outlined.Wallpaper,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    AsyncImage(
                        model = File(imagePath),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(58.dp)
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "已选择",
                        tint = palette.onAccent,
                        modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                            .background(palette.accent, CircleShape).padding(2.dp)
                    )
                }
            }
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private enum class TypographySecondaryPage { ADVANCED, SYNTAX }

@Composable
private fun TypographySecondaryHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            onClick = onBack,
            shape = CircleShape,
            color = Color.Transparent
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回排版",
                modifier = Modifier.padding(8.dp).size(20.dp)
            )
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun SecondaryPageRow(
    title: String,
    summary: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(summary, style = MaterialTheme.typography.labelSmall, color = palette.muted)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = palette.muted)
        }
    }
}

@Composable
private fun AdvancedTypographyEditor(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onFontWeightChange: (Int) -> Unit,
    onLetterSpacingChange: (Float) -> Unit,
    onParagraphSpacingChange: (Float) -> Unit,
    onFirstLineIndentChange: (Float) -> Unit,
    onTitleScaleChange: (Float) -> Unit,
    onTitleTopSpacingChange: (Float) -> Unit,
    onTitleBottomSpacingChange: (Float) -> Unit,
    onTextJustificationChange: (Boolean) -> Unit,
    onShowHeaderChange: (Boolean) -> Unit,
    onShowFooterChange: (Boolean) -> Unit,
    onHeaderMarginTopChange: (Float) -> Unit,
    onFooterMarginBottomChange: (Float) -> Unit
) {
    Text("正文字重", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(300, 400, 500, 600, 700).forEach { weight ->
            SegChip(
                text = weight.toString(),
                selected = settings.fontWeight == weight,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { onFontWeightChange(weight) }
        }
    }
    TypographyValueSlider(
        label = "字间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.letterSpacingEm),
        value = settings.letterSpacingEm,
        range = -0.05f..0.2f,
        steps = 24,
        palette = palette,
        onValueChange = onLetterSpacingChange
    )
    TypographyValueSlider(
        label = "段落间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.paragraphSpacingEm),
        value = settings.paragraphSpacingEm,
        range = 0f..1.5f,
        steps = 29,
        palette = palette,
        onValueChange = onParagraphSpacingChange
    )
    TypographyValueSlider(
        label = "段首缩进",
        valueText = String.format(java.util.Locale.ROOT, "%.1f 字", settings.firstLineIndentEm),
        value = settings.firstLineIndentEm,
        range = 0f..4f,
        steps = 15,
        palette = palette,
        onValueChange = onFirstLineIndentChange
    )
    Text("标题", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    TypographyValueSlider(
        label = "标题比例",
        valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.titleScale),
        value = settings.titleScale,
        range = 1f..2f,
        steps = 19,
        palette = palette,
        onValueChange = onTitleScaleChange
    )
    TypographyValueSlider(
        label = "标题上边距",
        valueText = spacingLineText(settings.titleTopSpacing),
        value = settings.titleTopSpacing,
        range = 0f..3f,
        steps = 29,
        palette = palette,
        onValueChange = onTitleTopSpacingChange
    )
    TypographyValueSlider(
        label = "标题下边距",
        valueText = spacingLineText(settings.titleBottomSpacing),
        value = settings.titleBottomSpacing,
        range = 0f..3f,
        steps = 29,
        palette = palette,
        onValueChange = onTitleBottomSpacingChange
    )
    SheetRow(label = "对齐", palette = palette) {
        SegChip(
            text = "两端",
            selected = settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { onTextJustificationChange(true) }
        SegChip(
            text = "左对齐",
            selected = !settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { onTextJustificationChange(false) }
    }
    Text("页眉与页脚", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    AdvancedSwitchRow("显示页眉", "章节标题", settings.showHeader, palette, onShowHeaderChange)
    TypographyValueSlider(
        label = "页眉上边距",
        valueText = chromeMarginText(settings.headerMarginTop),
        value = settings.headerMarginTop,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = onHeaderMarginTopChange
    )
    AdvancedSwitchRow("显示页脚", "页码、进度、时间与电量", settings.showFooter, palette, onShowFooterChange)
    TypographyValueSlider(
        label = "页脚边距",
        valueText = chromeMarginText(settings.footerMarginBottom),
        value = settings.footerMarginBottom,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = onFooterMarginBottomChange
    )
}

@Composable
private fun TypographyValueSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    palette: ReaderPalette,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = palette.muted)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.muted.copy(alpha = 0.18f)
            )
        )
    }
}

@Composable
private fun AdvancedSwitchRow(
    title: String,
    summary: String,
    checked: Boolean,
    palette: ReaderPalette,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(summary, style = MaterialTheme.typography.labelSmall, color = palette.muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedTrackColor = palette.accent)
        )
    }
}

@Composable
private fun SyntaxHighlightEditor(
    settings: ReaderSettings,
    palette: ReaderPalette,
    onEnabledChange: (Boolean) -> Unit,
    onEdit: (ReaderSyntaxRule) -> Unit,
    onAdd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("语法高亮", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "匹配引号、书名号等成对符号，并美化其中内容",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            Switch(
                checked = settings.syntaxHighlightEnabled,
                onCheckedChange = onEnabledChange,
                colors = SwitchDefaults.colors(checkedTrackColor = palette.accent)
            )
        }
        settings.syntaxHighlightRules.forEach { rule ->
            Surface(
                onClick = { onEdit(rule) },
                color = palette.glass.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color(rule.colorArgb), CircleShape)
                    )
                    Column(modifier = Modifier.weight(1f).padding(start = 9.dp)) {
                        Text(rule.name, style = MaterialTheme.typography.labelLarge)
                        Text(
                            buildString {
                                if (rule.matchMode == ReaderSyntaxMatchMode.REGEX) {
                                    append("正则：")
                                    append(rule.pattern)
                                } else {
                                    append("${rule.startDelimiter} 内容 ${rule.endDelimiter}")
                                }
                                if (rule.font != ReaderSyntaxFont.INHERIT) {
                                    append(" · ")
                                    append(
                                        if (rule.font == ReaderSyntaxFont.CUSTOM) {
                                            settings.fontLibrary
                                                .firstOrNull { it.id == rule.fontAssetId }
                                                ?.displayName
                                                ?: "已删除字体"
                                        } else {
                                            rule.font.shortLabel()
                                        }
                                    )
                                }
                                if (rule.bold) append(" · 粗体")
                                if (rule.italic) append(" · 斜体")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(if (rule.enabled) "启用" else "停用", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        TextButton(onClick = onAdd, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Text("添加规则")
        }
    }
}

@Composable
private fun SyntaxRuleEditorDialog(
    initial: ReaderSyntaxRule,
    fontLibrary: List<com.mozhi.reader.core.datastore.ReaderFontAsset>,
    onDismiss: () -> Unit,
    onSave: (ReaderSyntaxRule) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var start by remember { mutableStateOf(initial.startDelimiter) }
    var end by remember { mutableStateOf(initial.endDelimiter) }
    var color by remember { mutableStateOf(Color(initial.colorArgb)) }
    var includeDelimiters by remember { mutableStateOf(initial.includeDelimiters) }
    var underline by remember { mutableStateOf(initial.underline) }
    var matchMode by remember { mutableStateOf(initial.matchMode) }
    var pattern by remember { mutableStateOf(initial.pattern) }
    var ignoreCase by remember { mutableStateOf(initial.ignoreCase) }
    var backgroundEnabled by remember { mutableStateOf(initial.backgroundArgb != null) }
    var backgroundColor by remember {
        mutableStateOf(Color(initial.backgroundArgb ?: 0x33D06B42))
    }
    var syntaxFont by remember { mutableStateOf(initial.font) }
    var syntaxFontAssetId by remember { mutableStateOf(initial.fontAssetId) }
    var bold by remember { mutableStateOf(initial.bold) }
    var italic by remember { mutableStateOf(initial.italic) }
    var strikethrough by remember { mutableStateOf(initial.strikethrough) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    val valid = when (matchMode) {
        ReaderSyntaxMatchMode.DELIMITED -> start.isNotEmpty() && end.isNotEmpty()
        ReaderSyntaxMatchMode.REGEX -> pattern.isNotBlank() && pattern.length <= 256 &&
            runCatching { Regex(pattern) }.isSuccess
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "添加高亮规则" else "编辑高亮规则") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(20) },
                    label = { Text("规则名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderSyntaxMatchMode.entries.forEach { mode ->
                        FilterChip(
                            selected = matchMode == mode,
                            onClick = { matchMode = mode },
                            label = { Text(if (mode == ReaderSyntaxMatchMode.DELIMITED) "成对符号" else "正则") }
                        )
                    }
                }
                if (matchMode == ReaderSyntaxMatchMode.DELIMITED) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it.take(8) },
                            label = { Text("开始符号") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it.take(8) },
                            label = { Text("结束符号") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    OutlinedTextField(
                        value = pattern,
                        onValueChange = { pattern = it.take(256) },
                        label = { Text("正则表达式") },
                        supportingText = { Text("多行模式，匹配到的完整文本会应用样式") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    SyntaxRuleSwitch("忽略大小写", ignoreCase) { ignoreCase = it }
                }
                Text("文字颜色", style = MaterialTheme.typography.labelMedium)
                NoteStyleColorPalette(
                    color = color,
                    onColorChange = { color = it }
                )
                SyntaxRuleSwitch("背景色", backgroundEnabled) { backgroundEnabled = it }
                if (backgroundEnabled) {
                    NoteStyleColorPalette(
                        color = backgroundColor,
                        onColorChange = { backgroundColor = it.copy(alpha = 0.24f) }
                    )
                }
                Text("字体", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ReaderSyntaxFont.entries.filter { it != ReaderSyntaxFont.CUSTOM }.forEach { candidate ->
                        FilterChip(
                            selected = syntaxFont == candidate,
                            onClick = {
                                syntaxFont = candidate
                                syntaxFontAssetId = null
                            },
                            label = { Text(candidate.shortLabel()) }
                        )
                    }
                    fontLibrary.forEach { font ->
                        FilterChip(
                            selected = syntaxFont == ReaderSyntaxFont.CUSTOM &&
                                syntaxFontAssetId == font.id,
                            onClick = {
                                syntaxFont = ReaderSyntaxFont.CUSTOM
                                syntaxFontAssetId = font.id
                            },
                            label = { Text(font.displayName) }
                        )
                    }
                }
                if (matchMode == ReaderSyntaxMatchMode.DELIMITED) {
                    SyntaxRuleSwitch("符号本身也着色", includeDelimiters) { includeDelimiters = it }
                }
                SyntaxRuleSwitch("粗体", bold) { bold = it }
                SyntaxRuleSwitch("斜体", italic) { italic = it }
                SyntaxRuleSwitch("添加下划线", underline) { underline = it }
                SyntaxRuleSwitch("添加删除线", strikethrough) { strikethrough = it }
                SyntaxRuleSwitch("启用这条规则", enabled) { enabled = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "高亮规则" },
                            startDelimiter = start,
                            endDelimiter = end,
                            colorArgb = color.toArgb(),
                            includeDelimiters = includeDelimiters,
                            underline = underline,
                            matchMode = matchMode,
                            pattern = pattern.trim(),
                            ignoreCase = ignoreCase,
                            backgroundArgb = backgroundColor.toArgb().takeIf { backgroundEnabled },
                            font = syntaxFont,
                            fontAssetId = syntaxFontAssetId,
                            bold = bold,
                            italic = italic,
                            strikethrough = strikethrough,
                            enabled = enabled
                        )
                    )
                }
            ) { Text("保存") }
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
private fun SyntaxRuleSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

private fun horizontalMarginText(value: Float): String =
    "${(ReaderPageStyle.MARGIN_BASE_DP + ReaderPageStyle.MARGIN_RANGE_DP * value).roundToInt()} dp"

private fun verticalMarginText(value: Float): String =
    "额外 ${(ReaderPageStyle.VERTICAL_MARGIN_RANGE_DP * value).roundToInt()} dp"

private fun chromeMarginText(value: Float): String =
    "额外 ${(ReaderPageStyle.CHROME_MARGIN_RANGE_DP * value).roundToInt()} dp"

private fun spacingLineText(value: Float): String =
    String.format(java.util.Locale.ROOT, "%.1f 行", value)

private fun ReaderFont.shortLabel(): String = when (this) {
    ReaderFont.SYSTEM -> "默认"
    ReaderFont.SERIF -> "宋体"
    ReaderFont.SANS_SERIF -> "黑体"
    ReaderFont.MONOSPACE -> "等宽"
    ReaderFont.CUSTOM -> "自定义"
}

private fun ReaderSyntaxFont.shortLabel(): String = when (this) {
    ReaderSyntaxFont.INHERIT -> "跟随正文"
    ReaderSyntaxFont.SYSTEM -> "系统"
    ReaderSyntaxFont.SERIF -> "宋体"
    ReaderSyntaxFont.SANS_SERIF -> "黑体"
    ReaderSyntaxFont.MONOSPACE -> "等宽"
    ReaderSyntaxFont.CUSTOM -> "导入字体"
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

/** 三色编辑器：实时预览 + 目标切换 + 笔记软件式直观色板；保存即应用。 */
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
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
                NoteStyleColorPalette(
                    color = current,
                    onColorChange = ::update
                )
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
