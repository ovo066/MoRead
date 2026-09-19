package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.FOLLOW_SYSTEM_BRIGHTNESS
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.WidePageLayout

/** Only composition roots need all groups; each settings page receives its own actions. */
data class ReaderTypographyActions(
    val font: ReaderFontActions,
    val layout: ReaderLayoutActions,
    val theme: ReaderThemeActions,
    val syntax: ReaderSyntaxActions,
    val behavior: ReaderBehaviorActions
)

data class ReaderFontActions(
    val onFontChange: (ReaderFont) -> Unit,
    val onCustomFontSelect: (String) -> Unit,
    val onImportFont: () -> Unit
)

data class ReaderLayoutActions(
    val onFontScaleChange: (Float) -> Unit,
    val onLineHeightChange: (Float) -> Unit,
    val onPublisherStyleModeChange: (PublisherStyleMode) -> Unit,
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
    val onShowFooterChange: (Boolean) -> Unit
)

data class ReaderThemeActions(
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
    val onBackgroundOpacityChange: (Float, ReaderThemeSlot) -> Unit
)

data class ReaderSyntaxActions(
    val onSyntaxHighlightEnabledChange: (Boolean) -> Unit,
    val onSaveSyntaxRule: (ReaderSyntaxRule) -> Unit,
    val onDeleteSyntaxRule: (Long) -> Unit
)

data class ReaderBehaviorActions(
    val onAnimationChange: (PageTurnAnimation) -> Unit,
    val onPageModeChange: (PageMode) -> Unit,
    val onKeepScreenOnChange: (Boolean) -> Unit,
    val onImmersiveReadingChange: (Boolean) -> Unit,
    val onVolumeKeysPageTurnChange: (Boolean) -> Unit,
    val onChineseConversionModeChange: (ChineseConversionMode) -> Unit,
    /** [FOLLOW_SYSTEM_BRIGHTNESS] 或 0..1。 */
    val onScreenBrightnessChange: (Float) -> Unit,
    val onWidePageLayoutChange: (WidePageLayout) -> Unit = {},
    val spreadActive: Boolean = false
)
