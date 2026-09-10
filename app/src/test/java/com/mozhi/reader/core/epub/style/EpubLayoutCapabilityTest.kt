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
    fun `vertical chapter is reported with a structured reason and inherits through the tree`() {
        val styled = resolve("body { -epub-writing-mode: vertical-rl }", simpleBody)
        assertEquals(EpubWritingMode.VERTICAL_RL, styled.style.writingMode)
        assertEquals(EpubWritingMode.VERTICAL_RL, styled.children.single().style.writingMode)
        val capability = EpubLayoutCapabilityAnalyzer.analyze(styled)
        assertFalse(capability.supported)
        assertEquals(EpubLayoutFallbackReason.VERTICAL_WRITING_MODE, capability.reason)
        assertEquals(EpubWritingMode.VERTICAL_RL, capability.chapterWritingMode)
        assertTrue(capability.detail.contains("vertical-rl"))
    }

    @Test
    fun `take over mode still sees the writing mode because it is structural`() {
        val capability = EpubLayoutCapabilityAnalyzer.analyze(
            resolve("body { writing-mode: vertical-lr }", simpleBody, PublisherStyleMode.TAKE_OVER)
        )
        assertEquals(EpubLayoutFallbackReason.VERTICAL_WRITING_MODE, capability.reason)
        assertEquals(EpubWritingMode.VERTICAL_LR, capability.chapterWritingMode)
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
