package com.mozhi.reader.core.epub.css

import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.epub.style.EpubDecorationStyle
import com.mozhi.reader.core.epub.style.EpubStyleResolver
import com.mozhi.reader.core.library.EpubStylesheetText
import org.junit.Assert.*
import org.junit.Test

class CssPublisherCompatibilityTest {
    @Test fun importsRetainCascadeOrderResolveRelativeUrlsAndStopCycles() {
        val sheets = mapOf(
            "OPS/main.css" to "@import 'base.css'; p{color:red}",
            "OPS/base.css" to "@import 'main.css'; p{color:blue;background-image:url('../Images/paper.png')}"
        )
        val parsed = CssParser("OPS/main.css", importResolver = sheets::get).parse(sheets.getValue("OPS/main.css"))
        assertEquals(2, parsed.stylesheet.rules.size)
        val node = EpubDomNode("body", children = listOf(EpubDomNode("p")))
        val resolved = EpubStyleResolver(sheets.map { EpubStylesheetText(it.key, it.value) }, 300f, 500f, 20f, 0xff222222.toInt(),
            preParsedPublisherRules = parsed.stylesheet.rules).resolve(node).children.single().style
        assertEquals(0xffff0000.toInt(), resolved.colorArgb)
        assertEquals("Images/paper.png", resolved.background.imageHref)
        assertTrue(parsed.diagnostics.any { it.contains("@import") })
    }

    @Test fun importMediaConditionsStillExcludePrintOnlyRules() {
        val parsed = CssParser("OPS/main.css", importResolver = { "p{color:red}" }).parse("@import url('print.css') print; p{font-style:italic}")
        assertFalse(parsed.stylesheet.rules.first().mediaCondition!!.evaluate(300f, 500f))
        assertNull(parsed.stylesheet.rules.last().mediaCondition)
    }

    @Test fun decorationShorthandKeepsWaveColorAndExplicitNoneAcrossNestedSpans() {
        val body = EpubDomNode("body", children = listOf(
            EpubDomNode("a", classes = listOf("named"), children = listOf(EpubDomNode("span"))),
            EpubDomNode("a", classes = listOf("plain"))
        ))
        val resolved = EpubStyleResolver(listOf(EpubStylesheetText("OPS/main.css",
            ".named{text-decoration:underline wavy red}.plain{text-decoration:none}")), 300f, 500f, 20f, 0xff222222.toInt()).resolve(body)
        val span = resolved.children.first().children.single().style
        assertTrue(span.underline)
        assertEquals(EpubDecorationStyle.WAVY, span.decorationStyle)
        assertEquals(0xffff0000.toInt(), span.decorationColorArgb)
        assertFalse(resolved.children.last().style.underline)
        assertTrue("text-decoration-line" in resolved.children.last().style.appliedProperties)
    }
}
