package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.chineseConversionModeFor
import com.mozhi.reader.core.datastore.resolveForBook
import com.mozhi.reader.core.datastore.resolveThemeSlot
import com.mozhi.reader.ui.theme.isDarkTheme

/**
 * 弹层内的二级页。
 *
 * [FONT]/[THEME] 由一级页的控件旁入口直达；[PAGE_TURN]/[MORE] 由面板下沿两张大卡进入。
 * 阅读交互收进 [MORE]；语法高亮由右上角菜单独立进入。
 */
internal enum class TypographySecondaryPage(val title: String) {
    FONT("正文字体"),
    THEME("主题与背景"),
    PAGE_TURN("翻页方式"),
    MORE("更多设置"),
    CHINESE_CONVERSION("繁简转换"),
    BEHAVIOR("阅读交互")
}

/** 待编辑的自定义主题草稿；[slot] 决定保存后应用到日间还是夜间。 */
private data class CustomThemeDraft(val theme: CustomReaderTheme, val slot: ReaderThemeSlot)

/**
 * 排版面板（一级）。
 *
 * 分三层，各司其职：
 * - **默认展开的弹层**：放改得最勤的四项（字号、行距、主题、翻页），打开即可查看全部控件；
 * - **弹层内二级页**：字体、主题与背景、阅读交互——设定完就走，不需要盯着正文；
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
    var secondaryPage by remember { mutableStateOf<TypographySecondaryPage?>(null) }
    val systemDark = isDarkTheme()
    val typography = settings.resolveForBook(bookId, slot).copy(
        theme = settings.theme, activeCustomThemeId = settings.activeCustomThemeId,
        nightTheme = settings.nightTheme, nightActiveCustomThemeId = settings.nightActiveCustomThemeId)
    val bookThemeEnabled = settings.bookThemes[bookId]?.enabled == true
    // 新建主题时取目标槽当前的纸色做种子，配夜间方案时不会从白纸起步。
    val dayPalette = readerPalette(settings.resolveThemeSlot(ReaderThemeSlot.DAY), systemDark)
    val nightPalette = readerPalette(settings.resolveThemeSlot(ReaderThemeSlot.NIGHT), systemDark)
    val createDraft: (ReaderThemeSlot) -> CustomThemeDraft = { target ->
        val seed = if (target == ReaderThemeSlot.NIGHT) nightPalette else dayPalette
        CustomThemeDraft(
            theme = settings.resolveForBook(bookId, target).toCustomReaderTheme(
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
        // 保持常用控件紧凑；较小窗口内仍可滚动查看全部内容。
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val page = secondaryPage
        if (page == null) {
            TypographyMainPanel(
                settings = typography,
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
                // 更多设置里的深层页返回 More，而不是一路弹回一级页。
                secondaryPage = when (page) {
                    TypographySecondaryPage.CHINESE_CONVERSION,
                    TypographySecondaryPage.BEHAVIOR -> TypographySecondaryPage.MORE
                    else -> null
                }
            }
            when (page) {
                TypographySecondaryPage.FONT -> FontPage(typography, palette, actions.font)
                TypographySecondaryPage.PAGE_TURN -> PageTurnPage(settings, palette, actions.behavior)
                TypographySecondaryPage.MORE -> MoreSettingsPage(
                    settings = typography,
                    bookId = bookId,
                    palette = palette,
                    actions = actions.layout,
                    onOpenPage = { secondaryPage = it }
                )
                TypographySecondaryPage.THEME -> ThemePage(
                    settings = settings,
                    activeSlot = slot,
                    bookThemeEnabled = bookThemeEnabled,
                    palette = palette,
                    actions = actions.theme,
                    onEditCustomTheme = { theme, target ->
                        editorDraft = CustomThemeDraft(theme, target)
                    },
                    onCreateCustomTheme = { target -> editorDraft = createDraft(target) }
                )
                TypographySecondaryPage.CHINESE_CONVERSION -> ChineseConversionPage(
                    mode = settings.chineseConversionModeFor(bookId),
                    palette = palette,
                    onChange = actions.behavior.onChineseConversionModeChange
                )
                TypographySecondaryPage.BEHAVIOR -> BehaviorPage(settings, palette, actions.behavior)
            }
        }
    }

    editorDraft?.let { draft ->
        CustomThemeEditorDialog(
            initial = draft.theme,
            settings = settings.resolveForBook(bookId, draft.slot),
            palette = palette,
            onImportFont = actions.font.onImportFont,
            onImportBackground = { actions.theme.onImportBackground(draft.slot) },
            onDismiss = { editorDraft = null },
            onSave = { theme ->
                if (bookThemeEnabled) actions.theme.onSaveBookCustomTheme(theme, draft.slot)
                else actions.theme.onSaveCustomTheme(theme, draft.slot)
                editorDraft = null
            },
            onDelete = if (draft.theme.id != 0L) {
                {
                    actions.theme.onDeleteCustomTheme(draft.theme.id)
                    editorDraft = null
                }
            } else {
                null
            }
        )
    }
}
