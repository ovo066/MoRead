package com.mozhi.reader.core.epub.style

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubStylesheetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubLayoutCapabilityTest {
    private fun resolve(css: String, body: EpubDomNode, mode: PublisherStyleMode = PublisherStyleMode.RESPECT) =
        EpubStyleResolver(
            stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css)),
            viewportWidthPx = 300f,
            viewportHeightPx = 500f,
            rootFontSizePx = 20f,
            themeTextArgb = 0xFF222222.toInt(),
            publisherStyleMode = mode
        ).resolve(body)

    private val simpleBody = EpubDomNode("body", children = listOf(EpubDomNode("p", children = listOf(EpubDomNode("#text", textStart = 0, textEnd = 3)))))

    @Test
    fun `horizontal chapters are supported`() {
        val capability = EpubLayoutCapabilityAnalyzer.analyze(resolve("p { color: red }", simpleBody))
        assertTrue(capability.supported)
        assertNull(capability.reason)
        assertEquals(EpubWritingMode.HORIZONTAL_TB, capability.chapterWritingMode)
    }

    @Test
    fun `vertical-rl chapter is supported and the mode inherits through the tree`() {
        val styled = resolve("body { -epub-writing-mode: vertical-rl }", simpleBody)
        assertEquals(EpubWritingMode.VERTICAL_RL, styled.style.writingMode)
        assertEquals(EpubWritingMode.VERTICAL_RL, styled.children.single().style.writingMode)
        val capability = EpubLayoutCapabilityAnalyzer.analyze(styled)
        assertTrue(capability.supported)
        assertNull(capability.reason)
        assertEquals(EpubWritingMode.VERTICAL_RL, capability.chapterWritingMode)
    }

    @Test
    fun `writing mode declared on the html root reaches the body`() {
        val styled = EpubStyleResolver(
            stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css",
                "html { -epub-writing-mode: vertical-rl; font-size: 125% } p { font-size: 1rem }")),
            viewportWidthPx = 300f,
            viewportHeightPx = 500f,
            rootFontSizePx = 20f,
            themeTextArgb = 0xFF222222.toInt()
        ).resolve(simpleBody, EpubDomNode("html"))
        assertEquals(EpubWritingMode.VERTICAL_RL, styled.children.single().style.writingMode)
        // rem 相对根元素的计算字号，与浏览器一致。
        assertEquals(25f, styled.children.single().style.fontSizePx, .01f)
    }

    @Test
    fun `vertical-lr is reported with a structured reason`() {
        val capability = EpubLayoutCapabilityAnalyzer.analyze(resolve("body { writing-mode: vertical-lr }", simpleBody))
        assertFalse(capability.supported)
        assertEquals(EpubLayoutFallbackReason.VERTICAL_WRITING_MODE, capability.reason)
        assertTrue(capability.detail.contains("vertical-lr"))
    }

    @Test
    fun `nested writing mode switch inside a horizontal chapter is a distinct reason`() {
        val body = EpubDomNode(
            "body",
            children = listOf(
                EpubDomNode("p", children = listOf(EpubDomNode("#text", textStart = 0, textEnd = 3))),
                EpubDomNode("div", classes = listOf("poem"), children = listOf(EpubDomNode("#text", textStart = 3, textEnd = 6)))
            )
        )
        val capability = EpubLayoutCapabilityAnalyzer.analyze(resolve(".poem { -webkit-writing-mode: vertical-rl }", body))
        assertFalse(capability.supported)
        assertEquals(EpubLayoutFallbackReason.NESTED_WRITING_MODE, capability.reason)
        assertEquals(EpubWritingMode.HORIZONTAL_TB, capability.chapterWritingMode)
    }

    @Test
    fun `writing mode keywords parse with legacy aliases`() {
        assertEquals(EpubWritingMode.VERTICAL_RL, EpubWritingMode.parse("tb-rl"))
        assertEquals(EpubWritingMode.HORIZONTAL_TB, EpubWritingMode.parse(null))
        assertNull(EpubWritingMode.parse("sideways-rl"))
    }
}
