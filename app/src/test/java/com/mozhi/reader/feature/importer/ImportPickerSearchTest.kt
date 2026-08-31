package com.mozhi.reader.feature.importer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportPickerSearchTest {

    @Test
    fun `blank query matches every file`() {
        assertTrue(matchesImportSearch("三体.epub", "科幻/刘慈欣", "   "))
    }

    @Test
    fun `search matches file name and folder case insensitively`() {
        assertTrue(matchesImportSearch("Dune.EPUB", "SciFi/English", "dune"))
        assertTrue(matchesImportSearch("三体.txt", "科幻/刘慈欣", "刘慈欣"))
        assertTrue(matchesImportSearch("Dune.EPUB", "SciFi/English", "scifi epub"))
    }

    @Test
    fun `all search terms must match`() {
        assertFalse(matchesImportSearch("Dune.EPUB", "SciFi/English", "dune 中文"))
    }
}
