package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CssParserTest {
    private fun declarations(css: String) = CssParser("OEBPS/Styles/main.css").parseDeclarations(css)

    @Test
    fun `margin padding and border shorthands expand without losing negative percentages`() {
        val values = declarations("margin: auto -10%; padding: 1px 2em 3% 4pt; border: 1px solid #123456")
            .associateBy { it.property }

        assertEquals(CssValue.Keyword("auto"), values.getValue("margin-top").value)
        assertEquals(CssValue.Length(-10f, CssUnit.PERCENT), values.getValue("margin-right").value)
        assertEquals(CssValue.Length(3f, CssUnit.PERCENT), values.getValue("padding-bottom").value)
        assertEquals(CssValue.Length(1f, CssUnit.PX), values.getValue("border-left-width").value)
        assertEquals(CssValue.Keyword("solid"), values.getValue("border-top-style").value)
        assertEquals(CssValue.Color(0xFF123456.toInt()), values.getValue("border-bottom-color").value)
    }

    @Test
    fun `edge border quads and asymmetric radius expand`() {
        val values = declarations(
            "border-left: 2px dashed red; border-width: 1px 2px 3px 4px; " +
                "border-color: red green blue black; border-style: solid dashed dotted double; " +
                "border-radius: 1px 2px 3px 4px / 9px"
        ).associateBy { it.property }

        assertEquals(CssValue.Length(4f, CssUnit.PX), values.getValue("border-left-width").value)
        assertEquals(CssValue.Color(0xFF008000.toInt()), values.getValue("border-right-color").value)
        assertEquals(CssValue.Keyword("dotted"), values.getValue("border-bottom-style").value)
        assertEquals(CssValue.Length(4f, CssUnit.PX), values.getValue("border-bottom-left-radius").value)
    }

    @Test
    fun `background font and list style shorthands expand`() {
        val values = declarations(
            "background: url(x.png) no-repeat center / 3em 3em; " +
                "font: bold .9em/1.35 \"kt\", serif; list-style: square inside"
        ).associateBy { it.property }

        assertEquals(CssValue.Url("OEBPS/Styles/x.png"), values.getValue("background-image").value)
        assertEquals(CssValue.Keyword("no-repeat"), values.getValue("background-repeat").value)
        assertEquals(CssValue.Tuple(listOf(CssValue.Length(3f, CssUnit.EM), CssValue.Length(3f, CssUnit.EM))), values.getValue("background-size").value)
        assertEquals(CssValue.Keyword("bold"), values.getValue("font-weight").value)
        assertEquals(CssValue.Number(1.35f), values.getValue("line-height").value)
        assertEquals(CssValue.CommaList(listOf(CssValue.Ident("kt"), CssValue.Ident("serif"))), values.getValue("font-family").value)
        assertEquals(CssValue.Keyword("inside"), values.getValue("list-style-position").value)
    }

    @Test
    fun `media stays attached for layout-time evaluation and print remains inactive`() {
        val parsed = CssParser("book.css").parse(
            "@media screen and (min-width: 500px) { p { color: red } } " +
                "@media print { p { display:none } }"
        )
        val screen = parsed.stylesheet.rules[0].mediaCondition!!
        val print = parsed.stylesheet.rules[1].mediaCondition!!

        assertFalse(screen.evaluate(499f, 800f))
        assertTrue(screen.evaluate(500f, 800f))
        assertFalse(print.evaluate(800f, 600f))
    }

    @Test
    fun `bad declarations unknown properties and unsupported selector do not poison neighbors`() {
        val parsed = CssParser("book.css").parse(
            ".bad:hover, .good { unknown-x: 1; color: #zzzzzz; margin: auto -10%; padding: nope; } " +
                "} .after { color: blue; }"
        )

        assertTrue(parsed.unsupportedSelectors.any { it.contains(":hover") })
        assertTrue(parsed.unsupportedProperties.contains("unknown-x"))
        assertTrue(parsed.unsupportedProperties.contains("color"))
        assertTrue(parsed.stylesheet.rules.any { it.selector.source == ".good" })
        assertTrue(parsed.stylesheet.rules.any { it.selector.source == ".after" })
        assertEquals(4, parsed.stylesheet.rules.single { it.selector.source == ".good" }.declarations.size)
    }

    @Test
    fun `font face normalizes urls and keeps weight and style while import is diagnosed`() {
        val parsed = CssParser("OEBPS/Styles/font.css").parse(
            "@import 'other.css'; @font-face { font-family: 'Demo'; src: url('../Fonts/demo.ttf'); font-weight:700; font-style:italic }"
        )
        val face = parsed.stylesheet.fontFaces.single()

        assertEquals("Demo", face.family)
        assertEquals(listOf("OEBPS/Fonts/demo.ttf"), face.sources)
        assertEquals(700, face.weight)
        assertEquals("italic", face.style)
        assertTrue(parsed.diagnostics.any { it.contains("@import") })
    }
}
