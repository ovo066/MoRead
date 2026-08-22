package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderFontLibraryCodec
import com.mozhi.reader.core.datastore.ReaderImageLibraryCodec
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxMatchMode
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.backgroundImageIdFor
import com.mozhi.reader.core.datastore.backgroundOpacityFor
import com.mozhi.reader.core.datastore.customThemeIdFor
import com.mozhi.reader.core.datastore.resolveThemeSlot
import com.mozhi.reader.core.datastore.themeFor
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.onAccent
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 排版面板的全部回调。之前是三十多个平铺参数，加一项设置要在调用点与签名两处各改一遍；
 * 收成一个对象后，分类页只取自己需要的那几个字段。
 *
 * 配色与背景相关的回调都带 [ReaderThemeSlot]：日、夜各存一套，改的是哪一套由面板决定。
 */
data class ReaderTypographyActions(
    val onFontScaleChange: (Float) -> Unit,
    val onFontChange: (ReaderFont) -> Unit,
    val onCustomFontSelect: (String) -> Unit,
    val onImportFont: () -> Unit,
    val onLineHeightChange: (Float) -> Unit,
    val onPageMarginLeftChange: (Float) -> Unit,
    val onPageMarginRightChange: (Float) -> Unit,
    val onPageMarginTopChange: (Float) -> Unit,
    val onPageMarginBottomChange: (Float) -> Unit,
    val onHeaderMarginTopChange: (Float) -> Unit,
    val onFooterMarginBottomChange: (Float) -> Unit,
    val onFontWeightChange: (Int) -> Unit,
    val onLetterSpacingChange: (Float) -> Unit,
    val onParagraphSpacingChange: (Float) -> Unit,
    val onFirstLineIndentChange: (Float) -> Unit,
    val onTitleScaleChange: (Float) -> Unit,
    val onTitleTopSpacingChange: (Float) -> Unit,
    val onTitleBottomSpacingChange: (Float) -> Unit,
    val onTextJustificationChange: (Boolean) -> Unit,
    val onShowHeaderChange: (Boolean) -> Unit,
    val onShowFooterChange: (Boolean) -> Unit,
    val onThemeChange: (ReaderTheme, ReaderThemeSlot) -> Unit,
    val onCustomThemeSelect: (Long, ReaderThemeSlot) -> Unit,
    val onSaveCustomTheme: (CustomReaderTheme, ReaderThemeSlot) -> Unit,
    val onDeleteCustomTheme: (Long) -> Unit,
    val onDayNightAutoChange: (Boolean) -> Unit,
    val onImportBackground: (ReaderThemeSlot) -> Unit,
    val onBackgroundImageSelect: (String, ReaderThemeSlot) -> Unit,
    val onClearBackground: (ReaderThemeSlot) -> Unit,
    val onBackgroundOpacityChange: (Float, ReaderThemeSlot) -> Unit,
    val onSyntaxHighlightEnabledChange: (Boolean) -> Unit,
    val onSaveSyntaxRule: (ReaderSyntaxRule) -> Unit,
    val onDeleteSyntaxRule: (Long) -> Unit,
    val onAnimationChange: (PageTurnAnimation) -> Unit,
    val onPageModeChange: (PageMode) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onImmersiveReadingChange: (Boolean) -> Unit,
    val onVolumeKeysPageTurnChange: (Boolean) -> Unit
)

/** 待编辑的自定义主题草稿；[slot] 决定保存后应用到日间还是夜间。 */
private data class CustomThemeDraft(val theme: CustomReaderTheme, val slot: ReaderThemeSlot)

/**
 * 排版面板。
 *
 * 一级页只放改得最勤的四项：字号、行距、主题、翻页，半高 sheet 一屏放得下，不必下滑；
 * 其余全进二级页，入口做成一排胶囊——列表行太占高，六行入口会把主题挤到屏幕外，
 * 而主题恰恰是最常改的那一项。
 *
 * @param settings 原始设置（含日、夜两套配色），不是按当前明暗解析后的结果。
 * @param slot 此刻生效的配色槽；一级页改的就是它，所见即所改。
 */
@Composable
fun ReaderTypographySheet(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    var editorDraft by remember { mutableStateOf<CustomThemeDraft?>(null) }
    var syntaxDraft by remember { mutableStateOf<ReaderSyntaxRule?>(null) }
    var secondaryPage by remember { mutableStateOf<TypographySecondaryPage?>(null) }
    val systemDark = isDarkTheme()
    // 新建主题时取目标槽当前的纸色做种子，配夜间方案时不会从白纸起步。
    val dayPalette = readerPalette(settings.resolveThemeSlot(ReaderThemeSlot.DAY), systemDark)
    val nightPalette = readerPalette(settings.resolveThemeSlot(ReaderThemeSlot.NIGHT), systemDark)
    val createDraft: (ReaderThemeSlot) -> CustomThemeDraft = { target ->
        val seed = if (target == ReaderThemeSlot.NIGHT) nightPalette else dayPalette
        CustomThemeDraft(
            theme = settings.toCustomReaderTheme(
                id = 0L,
                name = "自定义 " + (settings.customThemes.size + 1),
                backgroundArgb = seed.background.toArgb(),
                textArgb = seed.onBackground.toArgb(),
                accentArgb = seed.accent.toArgb(),
                isDark = seed.isDark,
                slot = target
            ),
            slot = target
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        val page = secondaryPage
        if (page == null) {
            TypographyMainPanel(
                settings = settings,
                slot = slot,
                palette = palette,
                actions = actions,
                onOpenPage = { secondaryPage = it },
                onEditCustomTheme = { theme -> editorDraft = CustomThemeDraft(theme, slot) },
                onCreateCustomTheme = { editorDraft = createDraft(slot) }
            )
        } else {
            TypographySecondaryHeader(page.title) { secondaryPage = null }
            when (page) {
                TypographySecondaryPage.FONT -> FontPage(settings, palette, actions)
                TypographySecondaryPage.MARGIN -> MarginPage(settings, palette, actions)
                TypographySecondaryPage.THEME -> ThemePage(
                    settings = settings,
                    activeSlot = slot,
                    palette = palette,
                    actions = actions,
                    onEditCustomTheme = { theme, target ->
                        editorDraft = CustomThemeDraft(theme, target)
                    },
                    onCreateCustomTheme = { target -> editorDraft = createDraft(target) }
                )
                TypographySecondaryPage.ADVANCED -> AdvancedTypographyPage(settings, palette, actions)
                TypographySecondaryPage.SYNTAX -> SyntaxHighlightEditor(
                    settings = settings,
                    palette = palette,
                    onEnabledChange = actions.onSyntaxHighlightEnabledChange,
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
                TypographySecondaryPage.BEHAVIOR -> BehaviorPage(settings, palette, actions)
            }
        }
    }

    editorDraft?.let { draft ->
        CustomThemeEditorDialog(
            initial = draft.theme,
            settings = settings,
            palette = palette,
            onImportFont = actions.onImportFont,
            onImportBackground = { actions.onImportBackground(draft.slot) },
            onDismiss = { editorDraft = null },
            onSave = { theme ->
                actions.onSaveCustomTheme(theme, draft.slot)
                editorDraft = null
            },
            onDelete = if (draft.theme.id != 0L) {
                {
                    actions.onDeleteCustomTheme(draft.theme.id)
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
                actions.onSaveSyntaxRule(it)
                syntaxDraft = null
            },
            onDelete = if (draft.id != 0L) {
                {
                    actions.onDeleteSyntaxRule(draft.id)
                    syntaxDraft = null
                }
            } else null
        )
    }
}

/** 一级页：字号、行距、主题、翻页直调，其余按目标进入二级页。 */
@Composable
private fun TypographyMainPanel(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onOpenPage: (TypographySecondaryPage) -> Unit,
    onEditCustomTheme: (CustomReaderTheme) -> Unit,
    onCreateCustomTheme: () -> Unit
) {
    Text("排版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)

    SheetRow(label = "字号", palette = palette) {
        val sizeSp = (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt()
        StepButton(text = "A−", palette = palette, modifier = Modifier.weight(1f)) {
            actions.onFontScaleChange(
                ((sizeSp - 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f)
            )
        }
        Text(
            text = sizeSp.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp)
        )
        StepButton(text = "A＋", palette = palette, modifier = Modifier.weight(1f)) {
            actions.onFontScaleChange(
                ((sizeSp + 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f)
            )
        }
    }

    SheetRow(label = "行距", palette = palette) {
        LINE_HEIGHT_PRESETS.forEach { (label, value) ->
            SegChip(
                text = label,
                selected = abs(settings.lineHeight - value) < 0.049f,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { actions.onLineHeightChange(value) }
        }
    }

    SheetRow(label = "主题", palette = palette) {
        ThemeSwatchStrip(
            settings = settings,
            slot = slot,
            palette = palette,
            actions = actions,
            onEditCustomTheme = onEditCustomTheme,
            onCreateCustomTheme = onCreateCustomTheme,
            modifier = Modifier.weight(1f)
        )
        // 日夜各一套时，标出此刻在改哪一套；点进去可以配另一套。
        if (settings.dayNightThemeAuto) {
            SlotPill(slot = slot, palette = palette) { onOpenPage(TypographySecondaryPage.THEME) }
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
                actions.onPageModeChange(PageMode.PAGINATED)
                actions.onAnimationChange(animation)
            }
        }
        SegChip(
            text = "上下",
            selected = settings.pageMode == PageMode.SCROLL,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onPageModeChange(PageMode.SCROLL) }
    }

    HorizontalDivider(color = palette.glassBorder)

    SecondaryPageChips(palette = palette, onOpenPage = onOpenPage)
}

/** 二级页入口：一排胶囊，两行放下六个，不占用一级页的纵向预算。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SecondaryPageChips(
    palette: ReaderPalette,
    onOpenPage: (TypographySecondaryPage) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TypographySecondaryPage.entries.forEach { page ->
            EntryChip(
                icon = page.icon,
                text = page.chipLabel,
                palette = palette
            ) { onOpenPage(page) }
        }
    }
}

@Composable
private fun EntryChip(
    icon: ImageVector,
    text: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = palette.muted, modifier = Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** 日/夜标记：一级页用它说明「现在改的是哪一套」，点击进主题页配另一套。 */
@Composable
private fun SlotPill(slot: ReaderThemeSlot, palette: ReaderPalette, onClick: () -> Unit) {
    val night = slot == ReaderThemeSlot.NIGHT
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = palette.accentContainer,
        contentColor = palette.accent,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (night) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (night) "夜间" else "日间",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** 内置色卡 + 我的主题 + 新建，横滑一排；一级页与主题页共用。 */
@Composable
private fun ThemeSwatchStrip(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onEditCustomTheme: (CustomReaderTheme) -> Unit,
    onCreateCustomTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCustomId = settings.customThemeIdFor(slot)
    val activeTheme = settings.themeFor(slot)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReaderTheme.entries
            // 日夜自动切换开着时，「跟随系统」已经由日夜两套方案接管，留着只会让人迷惑。
            .filterNot { settings.dayNightThemeAuto && it == ReaderTheme.SYSTEM }
            .forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = activeCustomId == null && activeTheme == theme,
                    palette = palette
                ) { actions.onThemeChange(theme, slot) }
            }
        settings.customThemes.forEach { custom ->
            val selected = activeCustomId == custom.id
            val imagePath = settings.imageLibrary.firstOrNull {
                it.id == custom.backgroundImageId
            }?.filePath ?: custom.backgroundImagePath
            CustomThemeSwatch(
                theme = custom,
                imagePath = imagePath,
                selected = selected,
                palette = palette
            ) {
                if (selected) onEditCustomTheme(custom) else actions.onCustomThemeSelect(custom.id, slot)
            }
        }
        AddThemeSwatch(palette = palette, onClick = onCreateCustomTheme)
    }
}

@Composable
private fun FontPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
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
                ) { actions.onFontChange(font) }
            }
            settings.fontLibrary.forEach { font ->
                SegChip(
                    text = font.displayName,
                    selected = settings.font == ReaderFont.CUSTOM &&
                        settings.selectedCustomFontId == font.id,
                    palette = palette
                ) { actions.onCustomFontSelect(font.id) }
            }
        }
    }
    Surface(
        onClick = actions.onImportFont,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text("导入 TTF/OTF/TTC", style = MaterialTheme.typography.labelMedium)
        }
    }
    TypographyValueSlider(
        label = "字号",
        valueText = "" + (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt() + " sp",
        value = settings.fontScale,
        range = 0.75f..2f,
        steps = 24,
        palette = palette,
        onValueChange = actions.onFontScaleChange
    )
    TypographyValueSlider(
        label = "行距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.lineHeight),
        value = settings.lineHeight,
        range = 1f..2.2f,
        steps = 23,
        palette = palette,
        onValueChange = actions.onLineHeightChange
    )
    Text("正文字重", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(300, 400, 500, 600, 700).forEach { weight ->
            SegChip(
                text = weight.toString(),
                selected = settings.fontWeight == weight,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { actions.onFontWeightChange(weight) }
        }
    }
}

@Composable
private fun MarginPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    Text("正文边距", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    TypographyValueSlider(
        label = "左边距",
        valueText = horizontalMarginText(settings.pageMarginLeft),
        value = settings.pageMarginLeft,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onPageMarginLeftChange
    )
    TypographyValueSlider(
        label = "右边距",
        valueText = horizontalMarginText(settings.pageMarginRight),
        value = settings.pageMarginRight,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onPageMarginRightChange
    )
    TypographyValueSlider(
        label = "上边距",
        valueText = verticalMarginText(settings.pageMarginTop),
        value = settings.pageMarginTop,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onPageMarginTopChange
    )
    TypographyValueSlider(
        label = "下边距",
        valueText = verticalMarginText(settings.pageMarginBottom),
        value = settings.pageMarginBottom,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onPageMarginBottomChange
    )
    HorizontalDivider(color = palette.glassBorder)
    Text("页眉与页脚", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    AdvancedSwitchRow(
        "显示页眉",
        "章节标题",
        settings.showHeader,
        palette,
        actions.onShowHeaderChange
    )
    TypographyValueSlider(
        label = "页眉上边距",
        valueText = chromeMarginText(settings.headerMarginTop),
        value = settings.headerMarginTop,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onHeaderMarginTopChange
    )
    AdvancedSwitchRow(
        "显示页脚",
        "页码、进度、时间与电量",
        settings.showFooter,
        palette,
        actions.onShowFooterChange
    )
    TypographyValueSlider(
        label = "页脚边距",
        valueText = chromeMarginText(settings.footerMarginBottom),
        value = settings.footerMarginBottom,
        range = 0f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onFooterMarginBottomChange
    )
}

/**
 * 主题页：日夜两套方案在这里配。开了自动切换后，白天也能提前把夜间那套调好——
 * 否则只能等天黑了再摸黑调。
 */
@Composable
private fun ThemePage(
    settings: ReaderSettings,
    activeSlot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onEditCustomTheme: (CustomReaderTheme, ReaderThemeSlot) -> Unit,
    onCreateCustomTheme: (ReaderThemeSlot) -> Unit
) {
    var editingSlot by remember { mutableStateOf(activeSlot) }
    val slot = if (settings.dayNightThemeAuto) editingSlot else ReaderThemeSlot.DAY

    AdvancedSwitchRow(
        "日夜自动切换",
        "白天和夜里各用一套配色，跟随应用日夜模式换",
        settings.dayNightThemeAuto,
        palette,
        actions.onDayNightAutoChange
    )
    if (settings.dayNightThemeAuto) {
        SheetRow(label = "方案", palette = palette) {
            SegChip(
                text = "日间",
                selected = slot == ReaderThemeSlot.DAY,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { editingSlot = ReaderThemeSlot.DAY }
            SegChip(
                text = "夜间",
                selected = slot == ReaderThemeSlot.NIGHT,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { editingSlot = ReaderThemeSlot.NIGHT }
        }
    }

    SheetRow(label = "纸色", palette = palette) {
        ThemeSwatchStrip(
            settings = settings,
            slot = slot,
            palette = palette,
            actions = actions,
            onEditCustomTheme = { theme -> onEditCustomTheme(theme, slot) },
            onCreateCustomTheme = { onCreateCustomTheme(slot) },
            modifier = Modifier.weight(1f)
        )
    }

    HorizontalDivider(color = palette.glassBorder)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val backgroundId = settings.backgroundImageIdFor(slot)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Wallpaper,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text("背景图片", style = MaterialTheme.typography.bodyMedium)
                Text(
                    settings.imageLibrary.firstOrNull { it.id == backgroundId }?.displayName
                        ?: "未设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            TextButton(onClick = { actions.onImportBackground(slot) }) { Text("导入") }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(98.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                BackgroundChoice(
                    name = "无背景图",
                    imagePath = null,
                    selected = backgroundId == null,
                    palette = palette,
                    onClick = { actions.onClearBackground(slot) }
                )
            }
            items(settings.imageLibrary, key = { it.id }) { image ->
                BackgroundChoice(
                    name = image.displayName,
                    imagePath = image.filePath,
                    selected = backgroundId == image.id,
                    palette = palette,
                    onClick = { actions.onBackgroundImageSelect(image.id, slot) }
                )
            }
        }
        if (backgroundId != null) {
            val opacity = settings.backgroundOpacityFor(slot)
            TypographyValueSlider(
                label = "背景强度",
                valueText = "" + (opacity * 100).roundToInt() + "%",
                value = opacity,
                range = 0.05f..1f,
                steps = 18,
                palette = palette,
                onValueChange = { actions.onBackgroundOpacityChange(it, slot) }
            )
        }
    }
}

@Composable
private fun BehaviorPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    AdvancedSwitchRow(
        "阅读时保持亮屏",
        "适合长时间连读",
        settings.keepScreenOn,
        palette,
        actions.onKeepScreenOnChange
    )
    AdvancedSwitchRow(
        "完全沉浸",
        "阅读时隐藏系统状态栏",
        settings.immersiveReading,
        palette,
        actions.onImmersiveReadingChange
    )
    AdvancedSwitchRow(
        "音量键翻页",
        "音量加上一页，音量减下一页",
        settings.volumeKeysPageTurn,
        palette,
        actions.onVolumeKeysPageTurnChange
    )
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

/** 分类二级页。[chipLabel] 上胶囊，[title] 上二级页标题栏。 */
private enum class TypographySecondaryPage(
    val chipLabel: String,
    val title: String,
    val icon: ImageVector
) {
    FONT("字体", "字体与字号", Icons.Outlined.TextFields),
    MARGIN("边距", "版面留白", Icons.Outlined.Straighten),
    THEME("主题", "主题与背景", Icons.Outlined.Palette),
    ADVANCED("细排版", "高级排版", Icons.Outlined.Tune),
    SYNTAX("高亮", "语法高亮", Icons.Outlined.Brush),
    BEHAVIOR("交互", "阅读交互", Icons.Outlined.TouchApp)
}

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
private fun AdvancedTypographyPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    TypographyValueSlider(
        label = "字间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.letterSpacingEm),
        value = settings.letterSpacingEm,
        range = -0.05f..0.2f,
        steps = 24,
        palette = palette,
        onValueChange = actions.onLetterSpacingChange
    )
    TypographyValueSlider(
        label = "段落间距",
        valueText = String.format(java.util.Locale.ROOT, "%.2f em", settings.paragraphSpacingEm),
        value = settings.paragraphSpacingEm,
        range = 0f..1.5f,
        steps = 29,
        palette = palette,
        onValueChange = actions.onParagraphSpacingChange
    )
    TypographyValueSlider(
        label = "段首缩进",
        valueText = String.format(java.util.Locale.ROOT, "%.1f 字", settings.firstLineIndentEm),
        value = settings.firstLineIndentEm,
        range = 0f..4f,
        steps = 15,
        palette = palette,
        onValueChange = actions.onFirstLineIndentChange
    )
    SheetRow(label = "对齐", palette = palette) {
        SegChip(
            text = "两端",
            selected = settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onTextJustificationChange(true) }
        SegChip(
            text = "左对齐",
            selected = !settings.textJustification,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onTextJustificationChange(false) }
    }
    Text("标题", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    TypographyValueSlider(
        label = "标题比例",
        valueText = String.format(java.util.Locale.ROOT, "%.2f×", settings.titleScale),
        value = settings.titleScale,
        range = 1f..2f,
        steps = 19,
        palette = palette,
        onValueChange = actions.onTitleScaleChange
    )
    TypographyValueSlider(
        label = "标题上边距",
        valueText = spacingLineText(settings.titleTopSpacing),
        value = settings.titleTopSpacing,
        range = 0f..3f,
        steps = 29,
        palette = palette,
        onValueChange = actions.onTitleTopSpacingChange
    )
    TypographyValueSlider(
        label = "标题下边距",
        valueText = spacingLineText(settings.titleBottomSpacing),
        value = settings.titleBottomSpacing,
        range = 0f..3f,
        steps = 29,
        palette = palette,
        onValueChange = actions.onTitleBottomSpacingChange
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
            ShaderBrush.linearGradient(
                0f to light,
                0.5f to light,
                0.5f to dark,
                1f to dark
            )
        }
        else -> {
            val color = readerPalette(theme, systemDark = false, palette.accent).background
            ShaderBrush.linearGradient(listOf(color, color))
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

/** 自定义主题卡片优先展示背景图片；再次点击当前主题进入编辑。 */
@Composable
private fun CustomThemeSwatch(
    theme: CustomReaderTheme,
    imagePath: String?,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val background = Color(theme.backgroundArgb)
    val accent = Color(theme.accentArgb)
    Column(
        modifier = Modifier.width(50.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) palette.accent else palette.glassBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (imagePath != null) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = theme.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑「${theme.name}」",
                    tint = Color(theme.textArgb),
                    modifier = Modifier.size(15.dp)
                        .background(background.copy(alpha = 0.72f), CircleShape)
                        .padding(2.dp)
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
        Text(
            text = theme.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) palette.accent else palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
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
            modifier = Modifier.size(17.dp)
        )
    }
}

/** 把当前排版与指定槽的配色打包成一套自定义主题的初值。 */
private fun ReaderSettings.toCustomReaderTheme(
    id: Long,
    name: String,
    backgroundArgb: Int,
    textArgb: Int,
    accentArgb: Int,
    isDark: Boolean,
    slot: ReaderThemeSlot
): CustomReaderTheme {
    val selectedFont = fontLibrary.firstOrNull { it.id == selectedCustomFontId }
    val backgroundId = backgroundImageIdFor(slot)
    val selectedBackground = imageLibrary.firstOrNull { it.id == backgroundId }
    return CustomReaderTheme(
        id = id,
        name = name,
        backgroundArgb = backgroundArgb,
        textArgb = textArgb,
        accentArgb = accentArgb,
        isDark = isDark,
        font = font,
        customFontId = selectedCustomFontId,
        customFontPath = selectedFont?.filePath ?: customFontPath,
        customFontName = selectedFont?.displayName ?: customFontName,
        fontScale = fontScale,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        pageMarginLeft = pageMarginLeft,
        pageMarginRight = pageMarginRight,
        pageMarginTop = pageMarginTop,
        pageMarginBottom = pageMarginBottom,
        letterSpacingEm = letterSpacingEm,
        paragraphSpacingEm = paragraphSpacingEm,
        firstLineIndentEm = firstLineIndentEm,
        titleScale = titleScale,
        titleTopSpacing = titleTopSpacing,
        titleBottomSpacing = titleBottomSpacing,
        headerMarginTop = headerMarginTop,
        footerMarginBottom = footerMarginBottom,
        textJustification = textJustification,
        showHeader = showHeader,
        showFooter = showFooter,
        backgroundImageId = backgroundId,
        backgroundImagePath = selectedBackground?.filePath,
        backgroundImageOpacity = backgroundOpacityFor(slot)
    )
}

private enum class ThemeColorTarget(val label: String) {
    BACKGROUND("底色"),
    TEXT("正文"),
    ACCENT("强调")
}

/** 完整主题编辑器：字体、背景图片与配色在同一处选择，保存后立即应用。 */
@Composable
private fun CustomThemeEditorDialog(
    initial: CustomReaderTheme,
    settings: ReaderSettings,
    palette: ReaderPalette,
    onImportFont: () -> Unit,
    onImportBackground: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (CustomReaderTheme) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var background by remember { mutableStateOf(Color(initial.backgroundArgb)) }
    var text by remember { mutableStateOf(Color(initial.textArgb)) }
    var accent by remember { mutableStateOf(Color(initial.accentArgb)) }
    var isDark by remember { mutableStateOf(initial.isDark ?: (Color(initial.backgroundArgb).luminance() < 0.5f)) }
    var selectedFont by remember { mutableStateOf(initial.font) }
    val initialCustomFontId = initial.customFontId
        ?: initial.customFontPath?.let(ReaderFontLibraryCodec::legacyId)
    val initialBackgroundImageId = initial.backgroundImageId
        ?: initial.backgroundImagePath?.let(ReaderImageLibraryCodec::legacyId)
    var selectedCustomFontId by remember { mutableStateOf(initialCustomFontId) }
    var selectedBackgroundImageId by remember { mutableStateOf(initialBackgroundImageId) }
    var backgroundImageOpacity by remember { mutableStateOf(initial.backgroundImageOpacity) }
    var target by remember { mutableStateOf(ThemeColorTarget.BACKGROUND) }

    val current = when (target) {
        ThemeColorTarget.BACKGROUND -> background
        ThemeColorTarget.TEXT -> text
        ThemeColorTarget.ACCENT -> accent
    }
    val selectedBackground = settings.imageLibrary.firstOrNull { it.id == selectedBackgroundImageId }
    val selectedBackgroundPath = selectedBackground?.filePath
        ?: initial.backgroundImagePath.takeIf { selectedBackgroundImageId == initialBackgroundImageId }
    val selectedFontAsset = settings.fontLibrary.firstOrNull { it.id == selectedCustomFontId }
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
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("主题名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 只决定界面元素按浅底还是深底渲染；日夜自动切换在「主题与背景」里设。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("明暗基调", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = !isDark,
                        onClick = { isDark = false },
                        label = { Text("浅色") }
                    )
                    FilterChip(
                        selected = isDark,
                        onClick = { isDark = true },
                        label = { Text("深色") }
                    )
                }

                Text("字体", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
                        SegChip(
                            text = font.shortLabel(),
                            selected = selectedFont == font,
                            palette = palette
                        ) { selectedFont = font }
                    }
                    settings.fontLibrary.forEach { font ->
                        SegChip(
                            text = font.displayName,
                            selected = selectedFont == ReaderFont.CUSTOM && selectedCustomFontId == font.id,
                            palette = palette
                        ) {
                            selectedFont = ReaderFont.CUSTOM
                            selectedCustomFontId = font.id
                        }
                    }
                }
                TextButton(onClick = onImportFont) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("导入字体", modifier = Modifier.padding(start = 5.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("背景图片", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onImportBackground) { Text("导入图片") }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(98.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        BackgroundChoice(
                            name = "无背景图",
                            imagePath = null,
                            selected = selectedBackgroundImageId == null,
                            palette = palette,
                            onClick = { selectedBackgroundImageId = null }
                        )
                    }
                    items(settings.imageLibrary, key = { it.id }) { image ->
                        BackgroundChoice(
                            name = image.displayName,
                            imagePath = image.filePath,
                            selected = selectedBackgroundImageId == image.id,
                            palette = palette,
                            onClick = { selectedBackgroundImageId = image.id }
                        )
                    }
                }
                if (selectedBackgroundImageId != null) {
                    TypographyValueSlider(
                        label = "背景强度",
                        valueText = "" + (backgroundImageOpacity * 100).roundToInt() + "%",
                        value = backgroundImageOpacity,
                        range = 0.05f..1f,
                        steps = 18,
                        palette = palette,
                        onValueChange = { backgroundImageOpacity = it }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .border(1.dp, text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                ) {
                    if (selectedBackgroundPath != null) {
                        AsyncImage(
                            model = File(selectedBackgroundPath),
                            contentDescription = "主题背景预览",
                            contentScale = ContentScale.Crop,
                            alpha = backgroundImageOpacity,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column(
                        modifier = Modifier.padding(12.dp),
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
                }
                Text("配色", style = MaterialTheme.typography.labelMedium)
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
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                )
                            }
                        )
                    }
                }
                NoteStyleColorPalette(color = current, onColorChange = ::update)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "自定义主题" },
                            backgroundArgb = background.toArgb(),
                            textArgb = text.toArgb(),
                            accentArgb = accent.toArgb(),
                            isDark = isDark,
                            font = selectedFont,
                            customFontId = selectedCustomFontId.takeIf { selectedFont == ReaderFont.CUSTOM },
                            customFontPath = if (selectedFont == ReaderFont.CUSTOM) {
                                selectedFontAsset?.filePath
                                    ?: initial.customFontPath.takeIf { selectedCustomFontId == initialCustomFontId }
                            } else null,
                            customFontName = if (selectedFont == ReaderFont.CUSTOM) {
                                selectedFontAsset?.displayName
                                    ?: initial.customFontName.takeIf { selectedCustomFontId == initialCustomFontId }
                            } else null,
                            backgroundImageId = selectedBackgroundImageId,
                            backgroundImagePath = selectedBackgroundPath,
                            backgroundImageOpacity = backgroundImageOpacity
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
