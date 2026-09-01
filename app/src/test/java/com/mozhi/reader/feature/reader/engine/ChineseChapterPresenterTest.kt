package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubElementRef
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapter
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubLayoutSpan
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
            element = EpubElementRef(tag = "p"),
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
                    EpubDomNode(tag = "p", id = "note", textStart = 0, textEnd = body.length)
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
            fontPaths = emptyMap(),
            dom = dom
        )
        val image = InlineImageSource(body.lastIndex, "/tmp/cover.jpg", 100, 200, "封面")

        val shown = presenter.present(
            body,
            layout,
            listOf(image),
            ChineseConversionMode.TW2SP
        )

        assertEquals("鼠标里的代码\uFFFC", shown.body)
        assertEquals(shown.body.length, shown.epubLayout!!.document.textLength)
        assertEquals(shown.body.length, shown.epubLayout!!.dom!!.textLength)
        assertEquals(shown.body.lastIndex, shown.inlineImages.single().charOffset)
        assertEquals("#note", shown.epubLayout!!.document.blocks.single().spans.single().linkHref)
        assertEquals("代码", shown.epubLayout!!.document.blocks.single().spans.single().rubyText)
        assertEquals("note", shown.epubLayout!!.dom!!.bodyNode.children.single().id)
        assertEquals("/tmp/cover.jpg", shown.epubLayout!!.resourcePaths.getValue("cover.jpg"))
    }
}
