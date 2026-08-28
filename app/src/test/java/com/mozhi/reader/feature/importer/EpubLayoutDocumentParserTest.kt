package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubResourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubLayoutDocumentParserTest {
    @Test
    fun `package-relative readium href still resolves linked css and font`() {
        val href = EpubResourcePath.matchKnown(
            "Text/chapter.xhtml",
            listOf("OEBPS/Text/chapter.xhtml")
        )!!
        val chapter = EpubLayoutDocumentParser().parse(
            bytes = """
                <html><head><link rel="stylesheet" href="../Styles/style.css" /></head>
                <body><p class="chapter">正文</p></body></html>
            """.trimIndent().toByteArray(),
            chapterIndex = 0,
            href = href,
            expectedText = "正文",
            stylesheets = mapOf(
                "OEBPS/Styles/style.css" to """
                    @font-face { font-family: sample; src: url('../Fonts/sample.ttf'); }
                    .chapter { font-family: sample; color: #123456; }
                """.trimIndent()
            )
        )

        assertEquals(listOf("OEBPS/Styles/style.css"), chapter.stylesheetHrefs)
        assertEquals("sample", chapter.blocks.single().style.fontFamily)
        assertEquals(0xFF123456.toInt(), chapter.blocks.single().style.colorArgb)
    }

    @Test
    fun `formatting whitespace does not duplicate container decoration onto paragraphs`() {
        val chapter = EpubLayoutDocumentParser().parseWithText(
            bytes = """
                <html><head><style>
                    .card { background: #eeeeee; border: 1px solid #999999; }
                    .card p { color: #123456; }
                </style></head><body>
                    <div class="card">
                        <p>第一段</p>
                        <p>第二段</p>
                    </div>
                </body></html>
            """.trimIndent().toByteArray(),
            chapterIndex = 0,
            href = "OEBPS/Text/chapter.xhtml",
            stylesheets = emptyMap()
        ).document

        val paragraphs = chapter.blocks.filter { it.kind == EpubLayoutBlockKind.PARAGRAPH }
        assertEquals(2, paragraphs.size)
        assertTrue(paragraphs.all { it.element.tag == "p" })
        assertTrue(paragraphs.all { it.style.backgroundColorArgb == null && it.style.borderWidthEm == null })
        val container = chapter.blocks.single { it.kind == EpubLayoutBlockKind.CONTAINER }
        assertEquals(0xFFEEEEEE.toInt(), container.style.backgroundColorArgb)
        assertTrue(container.style.borderWidthEm != null)
    }

    @Test
    fun `direct text div does not get a duplicate same-element container`() {
        val chapter = EpubLayoutDocumentParser().parseWithText(
            bytes = """
                <html><head><style>.badge { padding: 1em; background: #eeeeee; }</style></head>
                <body><div class="badge"><span>直接文本</span></div></body></html>
            """.trimIndent().toByteArray(),
            chapterIndex = 0,
            href = "OEBPS/Text/chapter.xhtml",
            stylesheets = emptyMap()
        ).document

        assertEquals(1, chapter.blocks.size)
        assertEquals(EpubLayoutBlockKind.PARAGRAPH, chapter.blocks.single().kind)
        assertEquals(listOf("badge"), chapter.blocks.single().element.classes)
        assertEquals(0xFFEEEEEE.toInt(), chapter.blocks.single().style.backgroundColorArgb)
    }

    @Test
    fun `single pass parser returns text images and matching layout anchors`() {
        val parsed = EpubLayoutDocumentParser().parseWithText(
            bytes = """
                <html><body><h1>标题</h1><p>正文<img src='../Images/hero.jpg' alt='头图'/></p></body></html>
            """.trimIndent().toByteArray(),
            chapterIndex = 2,
            href = "OEBPS/Text/chapter.xhtml",
            stylesheets = emptyMap()
        )

        assertEquals("标题\n正文\n［图片］", parsed.text)
        assertEquals(parsed.text.length, parsed.document.textLength)
        assertEquals(1, parsed.images.size)
        assertEquals(parsed.text.indexOf("［图片］"), parsed.images.single().charOffset)
        assertEquals("OEBPS/Images/hero.jpg", parsed.images.single().href)
    }
}
