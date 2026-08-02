package com.mozhi.reader.core.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyLocatorConverterTest {

    @Test
    fun `resolves locator after removing fragments`() {
        val result = LegacyLocatorConverter.resolveChapterIndex(
            locatorHref = "text/chapter-00042.xhtml#paragraph-8",
            readingOrderHrefs = listOf(
                "text/chapter-00001.xhtml",
                "text/chapter-00042.xhtml",
                "text/chapter-00043.xhtml"
            ),
            fallbackIndex = 0
        )

        assertEquals(1, result)
    }

    @Test
    fun `accepts leading relative path marker`() {
        val result = LegacyLocatorConverter.resolveChapterIndex(
            locatorHref = "./text/chapter-00002.xhtml",
            readingOrderHrefs = listOf("text/chapter-00001.xhtml", "text/chapter-00002.xhtml"),
            fallbackIndex = 0
        )

        assertEquals(1, result)
    }

    @Test
    fun `keeps current chapter when locator is not in spine`() {
        val result = LegacyLocatorConverter.resolveChapterIndex(
            locatorHref = "footnotes.xhtml#note-3",
            readingOrderHrefs = listOf("chapter.xhtml"),
            fallbackIndex = 7
        )

        assertEquals(7, result)
    }

    @Test
    fun `parses href and progression`() {
        val locator = LegacyLocatorConverter.parse(
            """{"href":"text/c1.xhtml","type":"application/xhtml+xml",
               "locations":{"progression":0.25,"totalProgression":0.1}}"""
        )

        assertEquals("text/c1.xhtml", locator?.href)
        assertEquals(0.25, locator?.progression ?: 0.0, 0.0001)
    }

    @Test
    fun `treats a missing locations block as the chapter start`() {
        val locator = LegacyLocatorConverter.parse("""{"href":"text/c1.xhtml"}""")

        assertEquals(0.0, locator?.progression ?: -1.0, 0.0001)
    }

    @Test
    fun `rejects malformed json and missing href`() {
        assertNull(LegacyLocatorConverter.parse("not json"))
        assertNull(LegacyLocatorConverter.parse("""{"locations":{"progression":0.5}}"""))
    }

    @Test
    fun `progression maps into the chapter char range`() {
        assertEquals(0, LegacyLocatorConverter.progressionToCharOffset(0.0, 800))
        assertEquals(400, LegacyLocatorConverter.progressionToCharOffset(0.5, 800))
        assertEquals(800, LegacyLocatorConverter.progressionToCharOffset(1.0, 800))
        assertEquals(0, LegacyLocatorConverter.progressionToCharOffset(0.5, 0))
    }
}
