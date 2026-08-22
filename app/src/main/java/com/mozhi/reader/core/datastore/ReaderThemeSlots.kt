package com.mozhi.reader.core.datastore

/**
 * 阅读纸色的日/夜两个槽位。
 *
 * 「日间方案 / 夜间方案」要名副其实：日、夜各记一套配色与背景图，跟随应用日夜模式自动换。
 * 只换外观（纸色、正文色、强调色、背景图与强度），字号行距边距这些排版两边共用——
 * 排版跟着模式跳变会让人以为设置丢了（Legado / 微信读书同样只切外观）。
 */
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
 * ReaderPageStyle）不必知道槽的存在。排版字段原样保留。
 */
fun ReaderSettings.resolveThemeSlot(slot: ReaderThemeSlot): ReaderSettings =
    if (slot == ReaderThemeSlot.DAY) {
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
