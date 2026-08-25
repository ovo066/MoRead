package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTableOfContentsTest {

    @Test
    fun `collapsed section hides all descendants until the next peer`() {
        val items = buildReaderTocItems(
            chapters = emptyList(),
            entries = listOf(
                entry(order = 0, title = "卷一", depth = 0, parent = null, chapter = 0, children = true),
                entry(order = 1, title = "第一章", depth = 1, parent = 0, chapter = 1),
                entry(order = 2, title = "小节", depth = 2, parent = 1, chapter = 2),
                entry(order = 3, title = "卷二", depth = 0, parent = null, chapter = 3, children = true)
            )
        )

        assertEquals(
            listOf("卷一", "卷二"),
            visibleReaderTocItems(items, setOf(0)).map { it.title }
        )
    }

    @Test
    fun `current entry prefers deepest node when href maps twice`() {
        val items = buildReaderTocItems(
            chapters = emptyList(),
            entries = listOf(
                entry(order = 0, title = "卷一", depth = 0, parent = null, chapter = 0, children = true),
                entry(order = 1, title = "第一章", depth = 1, parent = 0, chapter = 0)
            )
        )

        assertEquals(1, currentReaderTocOrder(items, currentChapterIndex = 0))
    }

    @Test
    fun `locate falls back to visible parent of collapsed current chapter`() {
        val items = buildReaderTocItems(
            chapters = emptyList(),
            entries = listOf(
                entry(order = 0, title = "卷一", depth = 0, parent = null, chapter = 0, children = true),
                entry(order = 1, title = "第一章", depth = 1, parent = 0, chapter = 1),
                entry(order = 2, title = "卷二", depth = 0, parent = null, chapter = 2, children = true)
            )
        )
        val visible = visibleReaderTocItems(items, setOf(0))

        assertEquals(0, currentReaderTocListIndex(items, visible, currentChapterIndex = 1))
    }

    private fun entry(
        order: Int,
        title: String,
        depth: Int,
        parent: Int?,
        chapter: Int?,
        children: Boolean = false
    ) = BookTocEntryEntity(
        id = order + 1L,
        bookId = 1,
        orderIndex = order,
        title = title,
        href = "Text/$order.xhtml",
        depth = depth,
        parentOrderIndex = parent,
        chapterIndex = chapter,
        hasChildren = children
    )
}
