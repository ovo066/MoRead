package com.mozhi.reader.core.epub.dom

import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubDomTest {
    @Test
    fun `inline image remains between anchored text siblings and indices are precomputed`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            bytes = """<html><body><p>甲<img src="x.png"/>乙</p><p>丙</p></body></html>""".toByteArray(),
            chapterIndex = 0,
            href = "OEBPS/Text/ch.xhtml",
            stylesheets = emptyMap()
        )
        val paragraphs = parsed.dom.bodyNode.children.filter { it.tag == "p" }
        val first = paragraphs.first()

        assertEquals(listOf("#text", "img", "#text"), first.children.map { it.tag })
        assertEquals(listOf(0, 1), paragraphs.map { it.childIndex })
        assertEquals(listOf(0, 1), paragraphs.map { it.childIndexOfType })
        assertEquals("甲\n［图片］\n乙\n丙", parsed.text)
        assertEquals(parsed.text.indexOf("［图片］"), first.children[1].textStart)
        assertAnchorsMonotonic(parsed.dom.bodyNode, parsed.text.length)
    }

    @Test
    fun `ruby annotation is structural metadata and rt text does not enter body`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p><ruby>汉<rt>han</rt></ruby>字</p></body></html>".toByteArray(),
            0,
            "chapter.xhtml",
            emptyMap()
        )
        val ruby = parsed.dom.bodyNode.children.single().children.first { it.tag == "ruby" }

        assertEquals("汉字", parsed.text)
        assertEquals("han", ruby.attributes["data-ruby"])
        assertTrue(ruby.children.none { it.tag == "rt" })
    }

    @Test
    fun `v9 adapter preserves ranges and emits inline declarations`() {
        val legacy = EpubLayoutChapter(
            chapterIndex = 2,
            href = "old.xhtml",
            blocks = listOf(
                EpubLayoutBlock(
                    orderIndex = 0,
                    kind = EpubLayoutBlockKind.PARAGRAPH,
                    textStart = 0,
                    textEnd = 2,
                    element = EpubElementRef("p", classes = listOf("old")),
                    style = EpubComputedStyle(fontSizeEm = 1.2f, widthFraction = .5f)
                )
            ),
            textLength = 2
        )
        val dom = EpubV9DomAdapter.adapt(legacy)
        val paragraph = dom.bodyNode.children.single()

        assertEquals(10, dom.schemaVersion)
        assertEquals(0, paragraph.children.single().textStart)
        assertEquals(2, paragraph.children.single().textEnd)
        assertTrue(paragraph.attributes.getValue("style").contains("font-size:1.2em"))
        assertTrue(paragraph.attributes.getValue("style").contains("width:50.0%"))
    }

    private fun assertAnchorsMonotonic(root: EpubDomNode, textLength: Int) {
        val ranges = flatten(root).mapNotNull { node ->
            node.textStart.takeIf { it >= 0 }?.let { node.textStart until node.textEnd }
        }.sortedBy { it.first }
        assertTrue(ranges.all { it.first >= 0 && it.last < textLength })
        ranges.zipWithNext().forEach { (left, right) -> assertTrue(left.first <= right.first) }
    }

    private fun flatten(node: EpubDomNode): List<EpubDomNode> = listOf(node) + node.children.flatMap(::flatten)
}
