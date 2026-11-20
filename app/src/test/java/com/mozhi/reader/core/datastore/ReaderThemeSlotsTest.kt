package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 日/夜主题槽的解析规则：自动开关、深浅判定、悬空 id 回落。 */
class ReaderThemeSlotsTest {

    private val nightCustom = CustomReaderTheme(
        id = 7L,
        name = "夜蓝",
        backgroundArgb = 0xFF0D1420.toInt(),
        textArgb = 0xFFB8C4D8.toInt(),
        accentArgb = 0xFF87B4E8.toInt(),
        isDark = true
    )
    private val images = listOf(
        ReaderImageAsset(id = "day-img", displayName = "宣纸", filePath = "/img/day.jpg"),
        ReaderImageAsset(id = "night-img", displayName = "夜幕", filePath = "/img/night.jpg")
    )
    private val settings = ReaderSettings(
        theme = ReaderTheme.PAPER,
        selectedBackgroundImageId = "day-img",
        backgroundImagePath = "/img/day.jpg",
        backgroundImageOpacity = 0.3f,
        imageLibrary = images,
        customThemes = listOf(nightCustom),
        dayNightThemeAuto = true,
        nightTheme = ReaderTheme.AMOLED,
        nightSelectedBackgroundImageId = "night-img",
        nightBackgroundImageOpacity = 0.5f
    )

    @Test
    fun `自动关闭时深色也留在日间槽`() {
        val off = settings.copy(dayNightThemeAuto = false)
        assertEquals(ReaderThemeSlot.DAY, off.activeThemeSlot(dark = true))
        assertEquals(ReaderTheme.PAPER, off.resolveThemeSlot(off.activeThemeSlot(true)).theme)
    }

    @Test
    fun `自动开启时按明暗选槽`() {
        assertEquals(ReaderThemeSlot.DAY, settings.activeThemeSlot(dark = false))
        assertEquals(ReaderThemeSlot.NIGHT, settings.activeThemeSlot(dark = true))
    }

    @Test
    fun `夜间槽解析出夜间配色与背景`() {
        val resolved = settings.resolveThemeSlot(ReaderThemeSlot.NIGHT)
        assertEquals(ReaderTheme.AMOLED, resolved.theme)
        assertEquals("night-img", resolved.selectedBackgroundImageId)
        assertEquals("/img/night.jpg", resolved.backgroundImagePath)
        assertEquals(0.5f, resolved.backgroundImageOpacity, 0.0001f)
    }

    @Test
    fun `日间槽解析后原样返回`() {
        assertEquals(settings, settings.resolveThemeSlot(ReaderThemeSlot.DAY))
    }

    @Test
    fun `排版字段不随槽切换`() {
        val typography = settings.copy(fontScale = 1.4f, lineHeight = 1.9f, pageMarginLeft = 0.4f)
        val resolved = typography.resolveThemeSlot(ReaderThemeSlot.NIGHT)
        assertEquals(1.4f, resolved.fontScale, 0.0001f)
        assertEquals(1.9f, resolved.lineHeight, 0.0001f)
        assertEquals(0.4f, resolved.pageMarginLeft, 0.0001f)
    }

    @Test
    fun `夜间自定义主题生效时覆盖内置主题`() {
        val withCustom = settings.copy(nightActiveCustomThemeId = 7L)
        val resolved = withCustom.resolveThemeSlot(ReaderThemeSlot.NIGHT)
        assertEquals(7L, resolved.activeCustomThemeId)
        assertEquals(nightCustom, withCustom.customThemeFor(ReaderThemeSlot.NIGHT))
    }

    @Test
    fun `悬空的自定义主题 id 回落到内置主题`() {
        val dangling = settings.copy(nightActiveCustomThemeId = 99L)
        assertNull(dangling.resolveThemeSlot(ReaderThemeSlot.NIGHT).activeCustomThemeId)
        assertEquals(ReaderTheme.AMOLED, dangling.resolveThemeSlot(ReaderThemeSlot.NIGHT).theme)
    }

    @Test
    fun `背景图已删除时按无背景处理`() {
        val dangling = settings.copy(nightSelectedBackgroundImageId = "gone")
        val resolved = dangling.resolveThemeSlot(ReaderThemeSlot.NIGHT)
        assertNull(resolved.selectedBackgroundImageId)
        assertNull(resolved.backgroundImagePath)
    }

    @Test
    fun `夜间槽没配背景图时不继承日间背景`() {
        val noNightBackground = settings.copy(nightSelectedBackgroundImageId = null)
        assertNull(noNightBackground.resolveThemeSlot(ReaderThemeSlot.NIGHT).backgroundImagePath)
    }

    @Test
    fun `主题快照保留新边距范围且全局页眉页脚不随主题覆盖`() {
        val chrome = ReaderChromeLayout.DEFAULT.with(ReaderChromeSlot.HEADER_CENTER, listOf(ReaderChromeItem.BOOK_TITLE))
        val profile = nightCustom.copy(pageMarginLeft = -1.4f, pageMarginRight = 3f, pageMarginTop = 0f, pageMarginBottom = 3f)
        val configured = settings.copy(
            chromeLayout = chrome,
            customThemes = listOf(profile),
            builtinThemeTypography = mapOf(ReaderTheme.PAPER.name to profile),
            bookThemes = mapOf(11L to BookReaderTheme(enabled = true, dayTheme = ReaderTheme.PAPER, nightCustomThemeId = profile.id))
        )
        ReaderThemeSlot.entries.forEach { slot ->
            val resolved = configured.resolveForBook(11L, slot)
            assertEquals(-1.4f, resolved.pageMarginLeft, 0f)
            assertEquals(3f, resolved.pageMarginRight, 0f)
            assertEquals(0f, resolved.pageMarginTop, 0f)
            assertEquals(3f, resolved.pageMarginBottom, 0f)
            assertEquals(chrome, resolved.chromeLayout)
        }
        val snapshot = configured.applyThemeSnapshot(profile).typographySnapshot()
        assertEquals(profile.pageMarginLeft, snapshot.pageMarginLeft, 0f)
        assertEquals(profile.pageMarginBottom, snapshot.pageMarginBottom, 0f)
    }

    @Test
    fun `主题应用夹住正文边距并保留页眉页脚偏移的原范围`() {
        val resolved = settings.applyThemeSnapshot(nightCustom.copy(
            pageMarginLeft = -9f, pageMarginRight = 9f, pageMarginTop = -9f, pageMarginBottom = 9f,
            headerMarginTop = -1f, footerMarginBottom = 3f
        ))
        assertEquals(-1.4f, resolved.pageMarginLeft, 0f)
        assertEquals(3f, resolved.pageMarginRight, 0f)
        assertEquals(0f, resolved.pageMarginTop, 0f)
        assertEquals(3f, resolved.pageMarginBottom, 0f)
        assertEquals(0f, resolved.headerMarginTop, 0f)
        assertEquals(2f, resolved.footerMarginBottom, 0f)
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
            val fallback = settings.applyThemeSnapshot(nightCustom.copy(
                pageMarginLeft = value, pageMarginRight = value, pageMarginTop = value, pageMarginBottom = value
            ))
            assertEquals(1f, fallback.pageMarginLeft, 0f)
            assertEquals(1f, fallback.pageMarginRight, 0f)
            assertEquals(0f, fallback.pageMarginTop, 0f)
            assertEquals(0f, fallback.pageMarginBottom, 0f)
        }
    }
}
