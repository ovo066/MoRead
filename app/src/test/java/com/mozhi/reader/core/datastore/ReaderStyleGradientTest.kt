package com.mozhi.reader.core.datastore

import org.junit.Assert.*
import org.junit.Test

class ReaderStyleGradientTest {
    @Test fun directionsStopsAndAlphaColorsParseWithoutSplittingRgbFunctions() {
        val css = ReaderStyleCss.parse("background: linear-gradient(to right, #f008 10%, rgba(0, 255, 0, 0.5), rgb(0, 0, 255) 90%);")
        assertTrue(css.errors.toString(), css.errors.isEmpty())
        val gradient = css.paint.backgroundGradient!!
        assertEquals(90f, gradient.angle)
        listOf(.1f, .5f, .9f).zip(gradient.stops).forEach { (expected, actual) -> assertEquals(expected, actual, .0001f) }
        assertEquals(listOf(0x88ff0000.toInt(), 0x7f00ff00, 0xff0000ff.toInt()), gradient.colors)
        assertEquals(listOf(0f, .8f, .8f, 1f), ReaderLinearGradient.parse("linear-gradient(#000, #fff 80%, #f00 20%, #000)").stops)
    }

    @Test fun standardTextClipIsOrderIndependentAndCanBeCleared() {
        for (css in listOf(
            "background: linear-gradient(45deg, #f00, #00f); background-clip: text; color: transparent;",
            "-webkit-text-fill-color: transparent; -webkit-background-clip: text; background-image: linear-gradient(45deg, #f00, #00f);")) {
            val parsed = ReaderStyleCss.parse(css)
            assertTrue(parsed.errors.toString(), parsed.errors.isEmpty())
            assertNotNull(parsed.paint.textGradient)
            assertNull(parsed.paint.backgroundGradient)
            assertNull(ReaderTitleStyle(backgroundArgb = -1, css = css).resolved().backgroundArgb)
            assertNull(ReaderSyntaxHighlighter.spans("[abc]", listOf(ReaderSyntaxRule(1, "test", "[", "]", -1, backgroundArgb = -1, css = css))).single().backgroundArgb)
        }
        val cleared = ReaderStyleCss.parse("background: linear-gradient(#f00,#00f); background: #eee;")
        assertNull(cleared.paint.backgroundGradient)
        assertEquals(0xffeeeeee.toInt(), cleared.background)
        assertNull(ReaderTitleStyle(imageAssetId = "old", css = "background-image: none;").resolved().imageAssetId)
    }

    @Test fun savedCssRestoresBothPaintsAndOriginalMatchIdentityThroughBionicSplits() {
        val rule = ReaderSyntaxRule(10, "test", "[", "]", -1,
            css = "color: linear-gradient(90deg, #f00, #00f); background-image: url(\"asset:paper-1\");")
        val restored = ReaderSyntaxRuleCodec.decode(ReaderSyntaxRuleCodec.encode(listOf(rule))).single()
        val spans = ReaderSyntaxHighlighter.spans("[first word] [second word]", listOf(restored,
            ReaderSyntaxRule(20, "english", "", "", -1, englishPrefixes = true)))
        assertTrue(spans.size > 2)
        assertEquals(2, spans.mapNotNull { it.paintSpan }.distinct().size)
        assertEquals(setOf("paper-1"), spans.mapNotNull { it.paintSpan?.paint?.backgroundImageId }.toSet())
        val title = ReaderTitleStyle(css = rule.css)
        assertEquals(title.resolved(), ReaderTitleStyleCodec.decode(ReaderTitleStyleCodec.encode(title)).resolved())
    }

    @Test fun unsupportedAndMalformedPaintsCannotBeSaved() {
        for (value in listOf("linear-gradient(#f00)", "linear-gradient(NaNdeg,#f00,#00f)",
            "linear-gradient(#f00 -1%, #00f)", "linear-gradient(#f00 101%, #00f)",
            "linear-gradient(#f00 1..2%, #00f)", "url(https://example.com/a.png)",
            "url(asset:../private)", "radial-gradient(#f00,#00f)", "linear-gradient(#f00,#00f),url(asset:a)")) {
            assertTrue(value, ReaderStyleCss.parse("background: $value;").errors.isNotEmpty())
        }
        assertTrue(ReaderStyleCss.parse("background-clip: text; color: transparent;").errors.isNotEmpty())
    }
}
