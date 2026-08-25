package com.mozhi.reader.feature.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubTocMapperTest {

    @Test
    fun `preserves volume hierarchy when section has its own spine document`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem("Part", "Text/part-1.xhtml"),
                EpubReadingOrderItem(null, "Text/chapter-1.xhtml"),
                EpubReadingOrderItem(null, "Text/chapter-2.xhtml")
            ),
            tableOfContents = listOf(
                EpubNavigationNode(
                    title = "卷一 真心话大冒险",
                    href = "Text/part-1.xhtml",
                    children = listOf(
                        EpubNavigationNode("第一章", "Text/chapter-1.xhtml"),
                        EpubNavigationNode("第二章", "Text/chapter-2.xhtml")
                    )
                )
            )
        )

        assertEquals(listOf("卷一 真心话大冒险", "第一章", "第二章"), structure.chapters.map { it.title })
        assertEquals(listOf(0, 1, 1), structure.tocEntries.map { it.depth })
        assertEquals(listOf(null, 0, 0), structure.tocEntries.map { it.parentOrderIndex })
        assertTrue(structure.tocEntries.first().hasChildren)
    }

    @Test
    fun `keeps section and chapter when they share the same href`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem(null, "Text/chapter-1.xhtml"),
                EpubReadingOrderItem(null, "Text/chapter-2.xhtml")
            ),
            tableOfContents = listOf(
                EpubNavigationNode(
                    title = "卷一",
                    href = "Text/chapter-1.xhtml",
                    children = listOf(
                        EpubNavigationNode("第一章", "Text/chapter-1.xhtml#start"),
                        EpubNavigationNode("第二章", "Text/chapter-2.xhtml")
                    )
                )
            )
        )

        assertEquals("第一章", structure.chapters.first().title)
        assertEquals(listOf(0, 0, 1), structure.tocEntries.map { it.chapterIndex })
        assertEquals(listOf("卷一", "第一章", "第二章"), structure.tocEntries.map { it.title })
    }

    @Test
    fun `normalizes fragments and preserves three levels and unlinked groups`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(EpubReadingOrderItem("正文", "Text/chapter.xhtml")),
            tableOfContents = listOf(
                EpubNavigationNode(
                    title = "第一部",
                    href = null,
                    children = listOf(
                        EpubNavigationNode(
                            title = "第一卷",
                            href = "",
                            children = listOf(
                                EpubNavigationNode("", "./Text/chapter.xhtml#section-2")
                            )
                        )
                    )
                )
            )
        )

        assertEquals(listOf(0, 1, 2), structure.tocEntries.map { it.depth })
        assertEquals(listOf(null, 0, 1), structure.tocEntries.map { it.parentOrderIndex })
        assertNull(structure.tocEntries[0].chapterIndex)
        assertEquals(0, structure.tocEntries[2].chapterIndex)
        assertEquals("正文", structure.tocEntries[2].title)
    }

    @Test
    fun `falls back to flat chapter entries when navigation is absent`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem("序章", "Text/start.xhtml"),
                EpubReadingOrderItem(null, "Text/next.xhtml")
            ),
            tableOfContents = emptyList()
        )

        assertEquals(listOf("序章", "第 2 章"), structure.chapters.map { it.title })
        assertEquals(listOf(0, 0), structure.tocEntries.map { it.depth })
        assertEquals(listOf(0, 1), structure.tocEntries.map { it.chapterIndex })
    }
}
