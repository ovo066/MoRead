package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubStylesheetText
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import com.mozhi.reader.feature.reader.engine.ChapterTypesetter
import com.mozhi.reader.feature.reader.engine.FakeMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec
import org.junit.Assert.*
import org.junit.Test

class EpubArtworkPaginationTest {
    private val spec = TypesetSpec(
        visibleWidth = 200f, visibleHeight = 116f, contentLineStep = 24f,
        titleLineStep = 32f, paragraphSpacing = 0f, blankLineSpacing = 0f,
        titleTopSpacing = 0f, titleBottomSpacing = 0f, contentFontSizePx = 20f,
        indentCharCount = 0f, bottomAlign = false,
        publisherStyleMode = PublisherStyleMode.SMART
    )

    @Test
    fun `empty full height background block remains visible on a custom reader paper`() {
        val css = ".art { height:100%; background-image:url('../Images/art.jpg'); background-size:100% auto; background-position:center; background-repeat:no-repeat; }"
        val parsed = EpubLayoutDocumentParser().parseWithText(
            """<html><head><link rel="stylesheet" href="../Styles/main.css"/><style>html, body { height:100%; }</style></head><body><h1 style="display:none">人物</h1><div class="art"></div></body></html>""".toByteArray(),
            0, "OPS/Text/art.xhtml", mapOf("OPS/Styles/main.css" to css)
        )
        val chapter = ChapterTypesetter(spec.copy(preferReaderBackground = true), FakeMeasure()).typeset(
            0, "人物", parsed.text, epubLayout = EpubLayoutChapterBundle(
                document = parsed.document, dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OPS/Styles/main.css", css)),
                resourcePaths = mapOf("OPS/Images/art.jpg" to "art.jpg"), fontPaths = emptyMap()
            )
        )
        val page = chapter.pages.single()
        val art = page.decorations.single { it.backgroundImagePath == "art.jpg" }
        assertEquals(0f, art.top, .01f)
        assertEquals(spec.visibleHeight, art.bottom, .01f)
        assertTrue(page.height >= spec.visibleHeight)
        assertEquals(parsed.text.length, chapter.bodyLength)
    }

    @Test
    fun `ordinary paragraphs fill a page even when one paragraph line would be alone`() {
        // Four lines followed by a two-line paragraph: the fifth line fits exactly on page one.
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><body><p>${"甲".repeat(80)}</p><p>${"乙".repeat(40)}</p></body></html>".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap()
        )
        val chapter = ChapterTypesetter(spec, FakeMeasure()).typeset(
            0, "", parsed.text, epubLayout = EpubLayoutChapterBundle(
                document = parsed.document, dom = parsed.dom,
                stylesheets = listOf(EpubStylesheetText("OPS/main.css", "p { margin:0; text-indent:0; }")),
                resourcePaths = emptyMap(), fontPaths = emptyMap()
            )
        )
        assertEquals(2, chapter.pageCount)
        assertEquals(5, chapter.pages.first().lines.size)
        assertEquals(spec.visibleHeight, chapter.pages.first().height, .01f)
        assertEquals(parsed.text.filterNot(Char::isWhitespace), chapter.pages.flatMap { it.lines }.joinToString("") { it.text })
    }

    @Test
    fun `explicit publisher orphan constraints still keep paragraphs together`() {
        val chapter = typeset("<p>${"甲".repeat(80)}</p><p>${"乙".repeat(40)}</p>",
            "p { margin:0; text-indent:0; orphans:2; widows:2; }")
        assertEquals(2, chapter.pageCount)
        assertEquals(4, chapter.pages.first().lines.size)
        assertEquals(2, chapter.pages.last().lines.size)
    }

    @Test
    fun `built in reader paper overrides important body and plain wrapper colors but preserves panels`() {
        val css = """
            body, .wrapper { background:#fff !important; }
            p { margin:0; text-indent:0; }
            .panel { background:#ffeedd; border:1px solid #333; border-radius:4px; }
        """.trimIndent()
        for (mode in PublisherStyleMode.entries) {
            val chapter = typeset("<div class='wrapper'><p>正文</p><div class='panel'><p>注释</p></div></div>", css,
                spec.copy(publisherStyleMode = mode, themeBackgroundArgb = 0xFFDDEECC.toInt()))
            assertTrue(chapter.pages.all { it.backgroundColorArgb == null })
            val decorations = chapter.pages.flatMap { it.decorations }
            assertTrue("$mode retained the wrapper paper", decorations.none { it.backgroundColorArgb == 0xFFFFFFFF.toInt() })
            if (mode != PublisherStyleMode.TAKE_OVER) {
                assertTrue("$mode lost the panel", decorations.any { it.backgroundColorArgb == 0xFFFFEEDD.toInt() && it.borderTopWidth > 0f })
            }
        }
    }

    @Test
    fun `inline styles alone retain an empty illustration page`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            """<html><body style="height:100%"><div style="height:100%;background:url('art.jpg') center/contain no-repeat"></div></body></html>""".toByteArray(),
            0, "OPS/ch.xhtml", emptyMap()
        )
        val chapter = ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", parsed.text,
            epubLayout = EpubLayoutChapterBundle(parsed.document, mapOf("OPS/art.jpg" to "art.jpg"), emptyMap(),
                dom = parsed.dom))
        assertEquals(spec.visibleHeight, chapter.pages.single().height, .01f)
        assertEquals("art.jpg", chapter.pages.single().decorations.single().backgroundImagePath)
    }

    @Test
    fun `embedded sheets keep their position between linked sheets in the cascade`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            """<html><head><style>body{height:100%} .art{height:25%}</style><link rel="stylesheet" href="main.css"/><style>.art{height:75%}</style></head><body><div class="art"></div></body></html>""".toByteArray(),
            0, "OPS/ch.xhtml", mapOf("OPS/main.css" to ".art{height:50%;background:url('art.jpg') no-repeat}")
        )
        val chapter = ChapterTypesetter(spec, FakeMeasure()).typeset(0, "", parsed.text,
            epubLayout = EpubLayoutChapterBundle(parsed.document, mapOf("OPS/art.jpg" to "art.jpg"), emptyMap(),
                dom = parsed.dom, stylesheets = listOf(EpubStylesheetText("OPS/main.css", ".art{height:50%;background:url('art.jpg') no-repeat}"))))
        assertEquals(spec.visibleHeight * .75f, chapter.pages.single().decorations.single().bottom, .01f)
        assertEquals(2, parsed.dom.embeddedStylesheets.size)
    }

    private fun typeset(body: String, css: String, typesetSpec: TypesetSpec = spec): com.mozhi.reader.feature.reader.engine.TextChapter {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            "<html><head><style>$css</style></head><body>$body</body></html>".toByteArray(), 0, "OPS/ch.xhtml", emptyMap())
        return ChapterTypesetter(typesetSpec, FakeMeasure()).typeset(0, "", parsed.text,
            epubLayout = EpubLayoutChapterBundle(parsed.document, emptyMap(), emptyMap(), dom = parsed.dom))
    }
}
