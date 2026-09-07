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
    fun `inherits toc title for split chapter body documents`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem(null, "Text/part0004.xhtml"),
                EpubReadingOrderItem(null, "Text/part0005.xhtml"),
                EpubReadingOrderItem(null, "Text/part0006.xhtml")
            ),
            tableOfContents = listOf(
                EpubNavigationNode("第一回 景阳冈武松打虎", "Text/part0004.xhtml"),
                EpubNavigationNode("第二回 西门庆巧遇潘金莲", "Text/part0006.xhtml")
            )
        )

        assertEquals(
            listOf("第一回 景阳冈武松打虎", "第一回 景阳冈武松打虎", "第二回 西门庆巧遇潘金莲"),
            structure.chapters.map { it.title }
        )
    }

    @Test
    fun `authored document title wins over filename guess but a shared template title does not`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem("金瓶梅", "Text/cover_page.xhtml"),
                EpubReadingOrderItem("第一回 景阳冈武松打虎", "Text/part0004.xhtml"),
                EpubReadingOrderItem("第一回 景阳冈武松打虎", "Text/part0005.xhtml"),
                EpubReadingOrderItem("新刻绣像批评金瓶梅", "Text/part0006.xhtml"),
                EpubReadingOrderItem("新刻绣像批评金瓶梅", "Text/part0007.xhtml"),
                EpubReadingOrderItem("新刻绣像批评金瓶梅", "Text/part0008.xhtml")
            ),
            tableOfContents = listOf(
                EpubNavigationNode("第一回 景阳冈武松打虎", "Text/part0004.xhtml"),
                EpubNavigationNode("第二回 西门庆巧遇潘金莲", "Text/part0006.xhtml")
            )
        )

        assertEquals(
            listOf(
                // 作者写下的 <title> 强于按文件名猜「封面」
                "金瓶梅",
                "第一回 景阳冈武松打虎",
                // 拆页正文：与标题页共用的 <title> 仍算真章名
                "第一回 景阳冈武松打虎",
                "第二回 西门庆巧遇潘金莲",
                // 被三个文档共用的书名是模板噪声，回落到目录延续
                "第二回 西门庆巧遇潘金莲",
                "第二回 西门庆巧遇潘金莲"
            ),
            structure.chapters.map { it.title }
        )
    }

    @Test
    fun `semantic trailing navigation page does not inherit previous chapter title`() {
        val structure = buildEpubImportStructure(
            readingOrder = listOf(
                EpubReadingOrderItem(null, "Text/part0203.xhtml"),
                EpubReadingOrderItem("未知", "Text/part0204.xhtml", "未知\n目录\n制作说明\n第一回")
            ),
            tableOfContents = listOf(
                EpubNavigationNode("第一百回 韩爱姐路遇二捣鬼", "Text/part0203.xhtml")
            )
        )

        assertEquals(listOf("第一百回 韩爱姐路遇二捣鬼", "目录"), structure.chapters.map { it.title })
    }

    @Test
    fun `repair changes placeholders only and preserves authored chapter titles`() {
        val repaired = repairPlaceholderChapterTitles(
            chapters = listOf(
                com.mozhi.reader.core.library.ChapterDraft(0, "第一回", "Text/part0004.xhtml", 10),
                com.mozhi.reader.core.library.ChapterDraft(1, "第 2 章", "Text/part0005.xhtml", 20),
                com.mozhi.reader.core.library.ChapterDraft(2, "作者自定后记", "Text/afterword.xhtml", 30),
                com.mozhi.reader.core.library.ChapterDraft(3, "第 4 章", "Text/part0204.xhtml", 40)
            ),
            tocTitles = listOf(0 to "第一回"),
            chapterTextHints = mapOf(3 to "未知\n目录\n制作说明\n第一回")
        )

        assertEquals(listOf(1 to "第一回", 3 to "目录"), repaired)
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
