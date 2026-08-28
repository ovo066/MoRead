package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 某一本书固定使用的日/夜主题引用。这里只保存预设引用，不复制主题内容；用户修改预设后，
 * 使用该预设的书会同步更新。删除预设时悬空引用自动回落到对应内置主题。
 */
@Serializable
data class BookReaderTheme(
    val enabled: Boolean = false,
    val dayTheme: ReaderTheme = ReaderTheme.LIGHT,
    val dayCustomThemeId: Long? = null,
    val nightTheme: ReaderTheme = ReaderTheme.DARK,
    val nightCustomThemeId: Long? = null
) {
    fun themeFor(slot: ReaderThemeSlot): ReaderTheme = when (slot) {
        ReaderThemeSlot.DAY -> dayTheme
        ReaderThemeSlot.NIGHT -> nightTheme
    }

    fun customThemeIdFor(slot: ReaderThemeSlot): Long? = when (slot) {
        ReaderThemeSlot.DAY -> dayCustomThemeId
        ReaderThemeSlot.NIGHT -> nightCustomThemeId
    }

    fun withTheme(slot: ReaderThemeSlot, theme: ReaderTheme): BookReaderTheme = when (slot) {
        ReaderThemeSlot.DAY -> copy(dayTheme = theme, dayCustomThemeId = null)
        ReaderThemeSlot.NIGHT -> copy(nightTheme = theme, nightCustomThemeId = null)
    }

    fun withCustomTheme(slot: ReaderThemeSlot, id: Long): BookReaderTheme = when (slot) {
        ReaderThemeSlot.DAY -> copy(dayCustomThemeId = id)
        ReaderThemeSlot.NIGHT -> copy(nightCustomThemeId = id)
    }
}

object BookReaderThemeCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(values: Map<Long, BookReaderTheme>): String = json.encodeToString(values)

    fun decode(raw: String?): Map<Long, BookReaderTheme> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<Long, BookReaderTheme>>(raw) }
            .getOrDefault(emptyMap())
            .filterKeys { it > 0L }
    }
}

/** 当前书在主题面板里应该显示为选中的方案；不改变真正用于排版的其他字段。 */
fun ReaderSettings.withBookThemeSelection(bookId: Long): ReaderSettings {
    val override = bookThemes[bookId]?.takeIf(BookReaderTheme::enabled) ?: return this
    val dayCustom = override.dayCustomThemeId?.takeIf { id -> customThemes.any { it.id == id } }
    val nightCustom = override.nightCustomThemeId?.takeIf { id -> customThemes.any { it.id == id } }
    return copy(
        theme = override.dayTheme,
        activeCustomThemeId = dayCustom,
        nightTheme = override.nightTheme,
        nightActiveCustomThemeId = nightCustom
    )
}

/**
 * 五层样式管线中的“本书设置”：全局设置先作为基准，再覆盖本书选中的主题预设。
 * 内置主题只换色；自定义主题是一整套方案，因此同时带入字号、行距、边距、字体和背景。
 */
fun ReaderSettings.resolveForBook(bookId: Long, slot: ReaderThemeSlot): ReaderSettings {
    val override = bookThemes[bookId]?.takeIf(BookReaderTheme::enabled)
        ?: return resolveThemeSlot(slot)
    val customId = override.customThemeIdFor(slot)
        ?.takeIf { id -> customThemes.any { it.id == id } }
    val selected = customId?.let { id -> customThemes.firstOrNull { it.id == id } }
    if (selected == null) {
        return resolveThemeSlot(slot).copy(
            theme = override.themeFor(slot),
            activeCustomThemeId = null,
            selectedBackgroundImageId = null,
            backgroundImagePath = null
        )
    }
    return applyThemeSnapshot(selected).copy(
        theme = override.themeFor(slot),
        activeCustomThemeId = selected.id
    )
}

private fun ReaderSettings.applyThemeSnapshot(theme: CustomReaderTheme): ReaderSettings {
    val fontId = theme.customFontId ?: theme.customFontPath?.let(ReaderFontLibraryCodec::legacyId)
    val fontAsset = fontLibrary.firstOrNull { it.id == fontId }
    val imageId = theme.backgroundImageId
        ?: theme.backgroundImagePath?.let(ReaderImageLibraryCodec::legacyId)
    val imagePath = imageLibrary.firstOrNull { it.id == imageId }?.filePath
        ?: theme.backgroundImagePath
    return copy(
        fontScale = theme.fontScale.coerceIn(0.75f, 2f),
        font = theme.font,
        selectedCustomFontId = fontId,
        customFontPath = fontAsset?.filePath ?: theme.customFontPath,
        customFontName = fontAsset?.displayName ?: theme.customFontName,
        fontWeight = theme.fontWeight.coerceIn(300, 700),
        lineHeight = theme.lineHeight.coerceIn(1f, 2.2f),
        pageMarginLeft = theme.pageMarginLeft.coerceIn(0f, 2f),
        pageMarginRight = theme.pageMarginRight.coerceIn(0f, 2f),
        pageMarginTop = theme.pageMarginTop.coerceIn(0f, 2f),
        pageMarginBottom = theme.pageMarginBottom.coerceIn(0f, 2f),
        letterSpacingEm = theme.letterSpacingEm.coerceIn(-0.05f, 0.2f),
        paragraphSpacingEm = theme.paragraphSpacingEm.coerceIn(0f, 1.5f),
        firstLineIndentEm = theme.firstLineIndentEm.coerceIn(0f, 4f),
        titleScale = theme.titleScale.coerceIn(1f, 2f),
        titleTopSpacing = theme.titleTopSpacing.coerceIn(0f, 3f),
        titleBottomSpacing = theme.titleBottomSpacing.coerceIn(0f, 3f),
        headerMarginTop = theme.headerMarginTop.coerceIn(0f, 2f),
        footerMarginBottom = theme.footerMarginBottom.coerceIn(0f, 2f),
        textJustification = theme.textJustification,
        showHeader = theme.showHeader,
        showFooter = theme.showFooter,
        selectedBackgroundImageId = imageId,
        backgroundImagePath = imagePath,
        backgroundImageOpacity = theme.backgroundImageOpacity.coerceIn(0.05f, 1f)
    )
}
