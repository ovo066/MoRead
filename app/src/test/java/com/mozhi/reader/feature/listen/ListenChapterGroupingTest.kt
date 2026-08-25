package com.mozhi.reader.feature.listen

import com.mozhi.reader.core.database.entity.ChapterEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenChapterGroupingTest {
    @Test
    fun groupsChineseAndEnglishVolumes() {
        val groups = groupListenChapters(
            listOf(
                chapter(0, "序章"),
                chapter(1, "第一卷 风起"),
                chapter(2, "第一章 初见"),
                chapter(3, "Volume 2: Return"),
                chapter(4, "Chapter 8")
            )
        )

        assertEquals(listOf("正文", "第一卷 风起", "Volume 2: Return"), groups.map { it.title })
        assertEquals(listOf("序章"), groups[0].chapters.map { it.displayTitle })
        assertEquals(listOf("第一章 初见"), groups[1].chapters.map { it.displayTitle })
        assertEquals(listOf("Chapter 8"), groups[2].chapters.map { it.displayTitle })
        assertEquals(1, groups[1].headerChapterIndex)
        assertEquals(3, groups[2].headerChapterIndex)
    }

    @Test
    fun keepsStandaloneVolumeAsNonChapterHeader() {
        val groups = groupListenChapters(listOf(chapter(0, "正文卷", charCount = 120)))

        assertEquals("正文卷", groups.single().title)
        assertEquals(emptyList<ListenChapterItem>(), groups.single().chapters)
        assertEquals(0, groups.single().headerChapterIndex)
    }

    @Test
    fun splitsVolumePrefixFromRealChapterTitle() {
        val groups = groupListenChapters(listOf(chapter(7, "第二卷 第一章 重逢")))

        assertEquals("第二卷", groups.single().title)
        assertEquals(listOf("第一章 重逢"), groups.single().chapters.map { it.displayTitle })
        assertEquals(7, groups.single().chapters.single().chapter.chapterIndex)
    }

    private fun chapter(index: Int, title: String, charCount: Int = 10) = ChapterEntity(
        bookId = 1,
        chapterIndex = index,
        title = title,
        href = "",
        charCount = charCount
    )
}
