package com.mozhi.reader.core.epub.style

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubStylesheetText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubStyleResolverTest {
    @Test
    fun `font relative units inherit correctly while box percentages remain unresolved`() {
        val body = EpubDomNode("body", children = listOf(
            EpubDomNode("div", classes = listOf("box"), children = listOf(
                EpubDomNode("span", classes = listOf("child"))
            ))
        ))
        val resolved = resolver(
            ".box { font-size:150%; color:rebeccapurple; margin:30% 10%; padding:2em; line-height:1.5 } " +
                ".child { font-size:.5em; color:inherit }"
        ).resolve(body)
        val box = resolved.children.single()
        val child = box.children.single()

        assertEquals(30f, box.style.fontSizePx, .01f)
        assertEquals(15f, child.style.fontSizePx, .01f)
        assertEquals(ResolvedLength.Percent(30f), box.style.marginTop)
        assertEquals(ResolvedLength.Percent(10f), box.style.marginLeft)
        assertEquals(ResolvedLength.Px(60f), box.style.paddingLeft)
        assertEquals(0xFF663399.toInt(), child.style.colorArgb)
        assertEquals(45f, box.style.lineHeight!!, .01f)
    }

    @Test
    fun `media display defaults current color and takeover mode are resolved at layout time`() {
        val body = EpubDomNode("body", children = listOf(EpubDomNode("img", classes = listOf("art"))))
        val css = ".art { display:block; border-color:currentColor; color:red } @media print { .art { display:none } }"
        val respect = resolver(css).resolve(body).children.single().style
        val takeover = resolver(css, PublisherStyleMode.TAKE_OVER).resolve(body).children.single().style

        assertEquals(EpubDisplay.BLOCK, respect.display)
        assertEquals(0xFFFF0000.toInt(), respect.colorArgb)
        // 接管模式保留结构（display），丢弃外观（color）。
        assertEquals(EpubDisplay.BLOCK, takeover.display)
        assertTrue(takeover.colorArgb != respect.colorArgb)
    }

    @Test
    fun `inline style resource urls resolve against the chapter document`() {
        val body = EpubDomNode(
            "body",
            children = listOf(
                EpubDomNode("div", attributes = mapOf("style" to "background-image:url('../Images/paper.png')"))
            )
        )
        val resolved = EpubStyleResolver(
            stylesheets = emptyList(),
            viewportWidthPx = 300f,
            viewportHeightPx = 500f,
            rootFontSizePx = 20f,
            themeTextArgb = 0xFF222222.toInt(),
            documentHref = "OEBPS/Text/chapter.xhtml"
        ).resolve(body)

        assertEquals("OEBPS/Images/paper.png", resolved.children.single().style.background.imageHref)
    }

    private fun resolver(css: String, mode: PublisherStyleMode = PublisherStyleMode.RESPECT) = EpubStyleResolver(
        stylesheets = listOf(EpubStylesheetText("OEBPS/Styles/main.css", css)),
        viewportWidthPx = 300f,
        viewportHeightPx = 500f,
        rootFontSizePx = 20f,
        themeTextArgb = 0xFF222222.toInt(),
        publisherStyleMode = mode
    )
}
