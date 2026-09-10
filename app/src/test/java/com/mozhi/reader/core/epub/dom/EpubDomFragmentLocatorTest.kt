package com.mozhi.reader.core.epub.dom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubDomFragmentLocatorTest {
    private fun text(start: Int, end: Int) = EpubDomNode("#text", textStart = start, textEnd = end)

    private val body = EpubDomNode(
        "body",
        children = listOf(
            EpubDomNode("p", id = "intro", children = listOf(text(0, 10))),
            EpubDomNode("a", id = "empty-anchor"),
            EpubDomNode(
                "div", id = "section", children = listOf(
                    EpubDomNode("h2", children = listOf(text(10, 14))),
                    EpubDomNode("p", children = listOf(text(14, 40), EpubDomNode("span", id = "inner", children = listOf(text(40, 45)))))
                )
            ),
            EpubDomNode("p", children = listOf(text(45, 60)))
        )
    )

    @Test
    fun `element range spans its descendant leaves`() {
        assertEquals(0 until 10, EpubDomFragmentLocator.locate(body, "intro"))
        assertEquals(10 until 45, EpubDomFragmentLocator.locate(body, "#section"))
        assertEquals(40 until 45, EpubDomFragmentLocator.locate(body, "inner"))
    }

    @Test
    fun `empty anchor resolves to the next leaf in document order`() {
        // <a id="x"></a> 没有后代文本：浏览器把视口滚到锚点处，这里退到其后第一个叶子的起点。
        assertEquals(10 until 10, EpubDomFragmentLocator.locate(body, "empty-anchor"))
    }

    @Test
    fun `unknown fragment yields null so callers can fall back`() {
        assertNull(EpubDomFragmentLocator.locate(body, "missing"))
        assertNull(EpubDomFragmentLocator.locate(body, ""))
        assertNull(EpubDomFragmentLocator.locate(body, "#"))
    }
}
