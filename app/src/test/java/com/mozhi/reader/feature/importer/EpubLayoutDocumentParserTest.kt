package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubResourcePath
import org.junit.Assert.assertEquals
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
}
