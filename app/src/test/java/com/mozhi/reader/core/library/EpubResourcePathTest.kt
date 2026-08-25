package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubResourcePathTest {
    @Test
    fun `readium package-relative href matches canonical spine href`() {
        assertEquals(
            "OEBPS/Text/Chapter0001.xhtml",
            EpubResourcePath.matchKnown(
                "Text/Chapter0001.xhtml",
                listOf("OEBPS/Text/Chapter0001.xhtml", "OEBPS/Text/Chapter0002.xhtml")
            )
        )
    }

    @Test
    fun `ambiguous suffix is not guessed`() {
        assertNull(
            EpubResourcePath.matchKnown(
                "chapter.xhtml",
                listOf("OPS/part-a/chapter.xhtml", "OPS/part-b/chapter.xhtml")
            )
        )
    }

    @Test
    fun `package aliases include opf-relative resource path`() {
        assertEquals(
            listOf("OEBPS/Styles/style.css", "Styles/style.css"),
            EpubResourcePath.packageAliases(
                href = "OEBPS/Styles/style.css",
                packageDocumentPath = "OEBPS/content.opf"
            )
        )
    }
}
