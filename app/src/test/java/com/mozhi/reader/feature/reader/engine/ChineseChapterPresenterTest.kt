package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubLayoutSpan
import com.mozhi.reader.core.library.EpubResolvedFontFace
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.text.ChineseTextConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseChapterPresenterTest {
    private val presenter = ChineseChapterPresenter(ChineseTextConverter())

    @Test
    fun conversionRebuildsEveryEpubBoundaryAndKeepsResources() {
        val body = "滑鼠裡的程式碼\uFFFC"
        val span = EpubLayoutSpan(
            textStart = 4,
            textEnd = 7,
            elements = listOf(EpubElementRef(tag = "a", id = "code")),
            linkHref = "#note",
            rubyText = "程式碼"
        )
        val block = EpubLayoutBlock(
            orderIndex = 0,
            kind = EpubLayoutBlockKind.PARAGRAPH,
            textStart = 0,
            textEnd = body.length,
            element = EpubElementRef(
                tag = "p",
                id = "paragraph",
                classes = listOf("lead"),
                inlineStyle = "font-family: Book"
            ),
            style = EpubComputedStyle(
                fontFamily = "Book",
                textAlign = EpubTextAlign.CENTER,
                backgroundImageHref = "cover.jpg"
            ),
            spans = listOf(span)
        )
        val dom = EpubDomChapter(
            chapterIndex = 0,
            href = "chapter.xhtml",
            bodyNode = EpubDomNode(
                tag = "body",
                textStart = 0,
                textEnd = body.length,
                children = listOf(
                    EpubDomNode(
                        tag = "p",
                        id = "note",
                        classes = listOf("lead"),
                        textStart = 2,
                        textEnd = 7,
                        children = listOf(
                            EpubDomNode(tag = "ruby", textStart = 4, textEnd = 7)
                        )
                    )
                )
            ),
            textLength = body.length
        )
        val layout = EpubLayoutChapterBundle(
            document = EpubLayoutChapter(
                chapterIndex = 0,
                href = "chapter.xhtml",
                blocks = listOf(block),
                textLength = body.length
            ),
            resourcePaths = mapOf("cover.jpg" to "/tmp/cover.jpg"),
            fontPaths = mapOf("book.otf" to "/tmp/book.otf"),
            fontFaces = listOf(EpubResolvedFontFace("Book", "/tmp/book.otf", 600, true)),
            dom = dom,
            stylesheets = listOf(EpubStylesheetText("style.css", "p { color: red; }"))
        )
        val image = InlineImageSource(body.lastIndex, "/tmp/cover.jpg", 100, 200, "封面")

        val shown = presenter.present(
            body,
            layout,
            listOf(image),
            ChineseConversionMode.TW2SP
        )

        assertEquals("鼠标里的代码\uFFFC", shown.body)
        val shownLayout = shown.epubLayout!!
        val shownBlock = shownLayout.document.blocks.single()
        val shownSpan = shownBlock.spans.single()
        val shownDom = shownLayout.dom!!
        val shownRoot = shownDom.bodyNode
        val shownChild = shownRoot.children.single()
        val shownNested = shownChild.children.single()
        assertEquals(shown.body.length, shownLayout.document.textLength)
        assertEquals(0, shownBlock.textStart)
        assertEquals(7, shownBlock.textEnd)
        assertEquals(4, shownSpan.textStart)
        assertEquals(6, shownSpan.textEnd)
        assertEquals(0, shownRoot.textStart)
        assertEquals(7, shownRoot.textEnd)
        assertEquals(2, shownChild.textStart)
        assertEquals(6, shownChild.textEnd)
        assertEquals(4, shownNested.textStart)
        assertEquals(6, shownNested.textEnd)
        assertEquals(shown.body.length, shownDom.textLength)
        assertEquals("#note", shownSpan.linkHref)
        assertEquals("代码", shownSpan.rubyText)
        assertEquals("note", shownChild.id)
        assertEquals(block.element, shownBlock.element)
        assertEquals(block.style, shownBlock.style)
        assertEquals(layout.resourcePaths, shownLayout.resourcePaths)
        assertEquals(layout.fontPaths, shownLayout.fontPaths)
        assertEquals(layout.fontFaces, shownLayout.fontFaces)
        assertEquals(layout.stylesheets, shownLayout.stylesheets)
        assertEquals(image.copy(charOffset = shown.body.lastIndex), shown.inlineImages.single())
    }
}
