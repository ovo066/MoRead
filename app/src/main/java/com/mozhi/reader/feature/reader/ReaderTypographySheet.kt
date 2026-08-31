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
    val onPublisherStyleModeChange: (com.mozhi.reader.core.datastore.PublisherStyleMode) -> Unit,
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
    val onSaveBookCustomTheme: (CustomReaderTheme, ReaderThemeSlot) -> Unit,
    val onDeleteCustomTheme: (Long) -> Unit,
    val onDayNightAutoChange: (Boolean) -> Unit,
    val onBookThemeEnabledChange: (Boolean) -> Unit,
    val onBookThemeChange: (ReaderTheme, ReaderThemeSlot) -> Unit,
    val onBookCustomThemeSelect: (Long, ReaderThemeSlot) -> Unit,
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
    val onVolumeKeysPageTurnChange: (Boolean) -> Unit,
    /** [com.mozhi.reader.core.datastore.FOLLOW_SYSTEM_BRIGHTNESS] 或 0..1。 */
    val onScreenBrightnessChange: (Float) -> Unit
)

/** 待编辑的自定义主题草稿；[slot] 决定保存后应用到日间还是夜间。 */
private data class CustomThemeDraft(val theme: CustomReaderTheme, val slot: ReaderThemeSlot)

/**
 * 排版面板（一级）。
 *
 * 分三层，各司其职：
 * - **本半屏弹层**：只放改得最勤的四项（字号、行距、主题、翻页），一屏放得下、不必下滑；
 * - **弹层内二级页**：字体、主题与背景、语法高亮、阅读交互——设定完就走，不需要盯着正文；
 * - **悬浮排版卡片**（[onOpenTypographyCard]）：字间距、段距、缩进、边距、标题这类**每改一格都要
 *   看重排结果**的项。它们留在半屏弹层里就只能对着上半屏的旧内容猜效果，所以单独浮到屏幕中央。
 *
 * @param settings 原始设置（含日、夜两套配色），不是按当前明暗解析后的结果。
 * @param slot 此刻生效的配色槽；一级页改的就是它，所见即所改。
 */
@Composable
fun ReaderTypographySheet(
    settings: ReaderSettings,
    bookId: Long,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onOpenTypographyCard: () -> Unit
) {
    var editorDraft by remember { mutableStateOf<CustomThemeDraft?>(null) }
    var syntaxDraft by remember { mutableStateOf<ReaderSyntaxRule?>(null) }
    var secondaryPage by remember { mutableStateOf<TypographySecondaryPage?>(null) }
    val systemDark = isDarkTheme()
    val bookThemeEnabled = settings.bookThemes[bookId]?.enabled == true
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
        // 一级页六行（亮度/字号/行距/主题抬头/色卡/两张大卡）要在半高 sheet 里一屏放下，
        // 行高已经压到触达下限 44dp，剩下的余量只能从行间距里省。
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val page = secondaryPage
        if (page == null) {
            TypographyMainPanel(
                settings = settings,
                slot = slot,
                bookThemeEnabled = bookThemeEnabled,
                palette = palette,
                actions = actions,
                onOpenPage = { secondaryPage = it },
                onOpenTypographyCard = onOpenTypographyCard,
                onEditCustomTheme = { theme -> editorDraft = CustomThemeDraft(theme, slot) },
                onCreateCustomTheme = { editorDraft = createDraft(slot) }
            )
        } else {
            TypographySecondaryHeader(page.title) {
                // 语法高亮与阅读交互是从「更多设置」进来的，返回该回到那一页而不是一路弹回一级页。
                secondaryPage = when (page) {
                    TypographySecondaryPage.SYNTAX,
                    TypographySecondaryPage.BEHAVIOR -> TypographySecondaryPage.MORE
                    else -> null
                }
            }
            when (page) {
                TypographySecondaryPage.FONT -> FontPage(settings, palette, actions)
                TypographySecondaryPage.PAGE_TURN -> PageTurnPage(settings, palette, actions)
                TypographySecondaryPage.MORE -> MoreSettingsPage(
                    settings = settings,
                    palette = palette,
                    actions = actions,
                    onOpenPage = { secondaryPage = it }
                )
                TypographySecondaryPage.THEME -> ThemePage(
                    settings = settings,
                    activeSlot = slot,
                    bookThemeEnabled = bookThemeEnabled,
                    palette = palette,
                    actions = actions,
                    onEditCustomTheme = { theme, target ->
                        editorDraft = CustomThemeDraft(theme, target)
                    },
                    onCreateCustomTheme = { target -> editorDraft = createDraft(target) }
                )
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
                if (bookThemeEnabled) actions.onSaveBookCustomTheme(theme, draft.slot)
                else actions.onSaveCustomTheme(theme, draft.slot)
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
