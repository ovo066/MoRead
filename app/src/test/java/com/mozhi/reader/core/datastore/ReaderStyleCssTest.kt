package com.mozhi.reader.core.datastore

import org.junit.Assert.*
import org.junit.Test

class ReaderStyleCssTest {
    @Test fun cssColorsUseCssAlphaOrderAndUnknownDeclarationsAreReported() {
        assertEquals(0x804488cc.toInt(), ReaderStyleCss.color("#4488cc80"))
        assertEquals(0xffaabbcc.toInt(), ReaderStyleCss.color("#abc"))
        assertTrue(ReaderStyleCss.parse("position: fixed;").errors.isNotEmpty())
        assertTrue(ReaderStyleCss.parse("padding: NaNem;", true).errors.isNotEmpty())
        assertTrue(ReaderStyleCss.parse("font-size: 30px;", true).errors.isNotEmpty())
    }

    @Test fun syntaxCssOverridesWidgetStyleAndSurvivesSerialization() {
        val rule = ReaderSyntaxRule(1, "test", "“", "”", 0xff000000.toInt(), css =
            "color: #336699; background-color: #aabbcc80; font-weight: bold; font-style: italic; text-decoration: underline line-through;")
        val restored = ReaderSyntaxRuleCodec.decode(ReaderSyntaxRuleCodec.encode(listOf(rule))).single()
        val span = ReaderSyntaxHighlighter.spans("他说：“你好”。", listOf(restored)).single()
        assertEquals(0xff336699.toInt(), span.colorArgb)
        assertEquals(0x80aabbcc.toInt(), span.backgroundArgb)
        assertTrue(span.bold && span.italic && span.underline && span.strikethrough)
        assertEquals(3, span.start)
        assertEquals(7, span.endExclusive)
    }

    @Test fun themeSlotsRestoreTheirOwnTypographyIncludingBookOverrides() {
        val day = CustomReaderTheme(1, "day", -1, 0, 0, font = ReaderFont.SERIF, fontScale = 1.4f,
            titleStyle = ReaderTitleStyle(css = "text-align: center;"))
        val night = day.copy(id = 2, font = ReaderFont.MONOSPACE, fontScale = .9f, lineHeight = 2f,
            titleStyle = ReaderTitleStyle(css = "color: #ffddaa;"))
        val settings = ReaderSettings(customThemes = listOf(day, night), activeCustomThemeId = 1, nightActiveCustomThemeId = 2)
        assertEquals(ReaderFont.SERIF, settings.resolveThemeSlot(ReaderThemeSlot.DAY).font)
        val dark = settings.resolveThemeSlot(ReaderThemeSlot.NIGHT)
        assertEquals(.9f, dark.fontScale, .001f)
        assertEquals(2f, dark.lineHeight, .001f)
        assertEquals(night.titleStyle, dark.titleStyle)
        assertEquals(day.titleStyle, settings.copy(bookThemes = mapOf(9L to BookReaderTheme(true, nightCustomThemeId = 1)))
            .resolveForBook(9, ReaderThemeSlot.NIGHT).titleStyle)
        assertEquals(listOf(day, night), CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(listOf(day, night))))
    }

    @Test fun builtinThemesRestoreTypographyWithoutReplacingTheirColorsOrBackgrounds() {
        val profile = ReaderSettings(font = ReaderFont.SERIF, fontScale = 1.6f,
            titleStyle = ReaderTitleStyle(css = "text-align: center;")).typographySnapshot()
        val settings = ReaderSettings(theme = ReaderTheme.LIGHT, nightTheme = ReaderTheme.DARK,
            backgroundImagePath = "paper.jpg", builtinThemeTypography = mapOf(ReaderTheme.LIGHT.name to profile))
        val light = settings.resolveThemeSlot(ReaderThemeSlot.DAY)
        assertEquals(ReaderTheme.LIGHT, light.theme)
        assertEquals("paper.jpg", light.backgroundImagePath)
        assertEquals(1.6f, light.fontScale, .001f)
        assertEquals(settings.fontScale, settings.resolveThemeSlot(ReaderThemeSlot.NIGHT).fontScale, .001f)
        assertEquals(profile.titleStyle, settings.copy(bookThemes = mapOf(9L to BookReaderTheme(enabled = true)))
            .resolveForBook(9, ReaderThemeSlot.DAY).titleStyle)
        assertEquals(settings.builtinThemeTypography, ReaderThemeTypographyCodec.decode(
            ReaderThemeTypographyCodec.encode(settings.builtinThemeTypography)))
    }
}
