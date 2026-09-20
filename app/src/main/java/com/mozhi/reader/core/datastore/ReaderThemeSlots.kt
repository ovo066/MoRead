package com.mozhi.reader.core.datastore

/** 日夜主题各自保存配色、字体与排版；自动切换应用所选预设的整套设置。 */
enum class ReaderThemeSlot {
    DAY,
    NIGHT
}

/** 当前该用哪个槽：只有「日夜自动切换」开着且此刻是深色，才走夜间槽。 */
fun ReaderSettings.activeThemeSlot(dark: Boolean): ReaderThemeSlot =
    if (dayNightThemeAuto && dark) ReaderThemeSlot.NIGHT else ReaderThemeSlot.DAY

fun ReaderSettings.themeFor(slot: ReaderThemeSlot): ReaderTheme = when (slot) {
    ReaderThemeSlot.DAY -> theme
    ReaderThemeSlot.NIGHT -> nightTheme
}

/** 悬空 id（主题已删）按未启用处理，与 [activeCustomTheme] 同一策略。 */
fun ReaderSettings.customThemeIdFor(slot: ReaderThemeSlot): Long? {
    val id = when (slot) {
        ReaderThemeSlot.DAY -> activeCustomThemeId
        ReaderThemeSlot.NIGHT -> nightActiveCustomThemeId
    }
    return id?.takeIf { candidate -> customThemes.any { it.id == candidate } }
}

fun ReaderSettings.customThemeFor(slot: ReaderThemeSlot): CustomReaderTheme? =
    customThemeIdFor(slot)?.let { id -> customThemes.firstOrNull { it.id == id } }

/** 悬空图片 id（图已删）按无背景处理。 */
fun ReaderSettings.backgroundImageIdFor(slot: ReaderThemeSlot): String? {
    val id = when (slot) {
        ReaderThemeSlot.DAY -> selectedBackgroundImageId
        ReaderThemeSlot.NIGHT -> nightSelectedBackgroundImageId
    }
    return id?.takeIf { candidate -> imageLibrary.any { it.id == candidate } }
}

fun ReaderSettings.backgroundImagePathFor(slot: ReaderThemeSlot): String? = when (slot) {
    // 日间槽要保留老数据的裸路径回落（图片库尚未收编的旧背景）。
    ReaderThemeSlot.DAY -> backgroundImageIdFor(slot)
        ?.let { id -> imageLibrary.firstOrNull { it.id == id }?.filePath }
        ?: backgroundImagePath
    ReaderThemeSlot.NIGHT -> backgroundImageIdFor(slot)
        ?.let { id -> imageLibrary.firstOrNull { it.id == id }?.filePath }
}

fun ReaderSettings.backgroundOpacityFor(slot: ReaderThemeSlot): Float = when (slot) {
    ReaderThemeSlot.DAY -> backgroundImageOpacity
    ReaderThemeSlot.NIGHT -> nightBackgroundImageOpacity
}

/**
 * 把指定槽的外观提到顶层字段，调色板与渲染只认这份结果——下游（readerPalette /
 * ReaderPageStyle）不必知道槽的存在。自定义主题同时带入绑定的字体、正文排版与标题样式。
 */
fun ReaderSettings.resolveThemeSlot(slot: ReaderThemeSlot): ReaderSettings {
    val selected = customThemeFor(slot) ?: builtinThemeTypography[themeFor(slot).name]
    val resolved = if (slot == ReaderThemeSlot.DAY) {
        this
    } else {
        copy(
            theme = nightTheme,
            activeCustomThemeId = customThemeIdFor(slot),
            selectedBackgroundImageId = backgroundImageIdFor(slot),
            backgroundImagePath = backgroundImagePathFor(slot),
            backgroundImageOpacity = nightBackgroundImageOpacity
        )
    }
    return (if (selected == null) resolved else resolved.applyThemeSnapshot(selected).copy(
        // Background selection remains editable independently of the saved typography.
        selectedBackgroundImageId = resolved.selectedBackgroundImageId,
        backgroundImagePath = resolved.backgroundImagePath,
        backgroundImageOpacity = resolved.backgroundImageOpacity
    )).resolveTitleStylePreset()
}
