package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class ShelfFilterTest {
    private val books = listOf(
        book(1, groupId = 10),
        book(2, groupId = 10),
        book(3, groupId = null)
    )
    private val refs = listOf(
        BookTagRefEntity(1, 1),
        BookTagRefEntity(1, 2),
        BookTagRefEntity(2, 2),
        BookTagRefEntity(3, 3)
    )

    @Test
    fun `分组与任一标签组合`() {
        val result = filterShelfBooks(
            books,
            refs,
            ShelfFilter(groupId = 10, tagIds = setOf(1, 3), matchMode = TagMatchMode.ANY)
        )
        assertEquals(listOf(1L), result.map(BookEntity::id))
    }

    @Test
    fun `全部标签要求同时命中`() {
        val result = filterShelfBooks(
            books,
            refs,
            ShelfFilter(tagIds = setOf(1, 2), matchMode = TagMatchMode.ALL)
        )
        assertEquals(listOf(1L), result.map(BookEntity::id))
    }

    @Test
    fun `删除当前分组后自动回到全部`() {
        val filter = ShelfFilter(groupId = 10, tagIds = setOf(2))

        assertEquals(filter, filter.withExistingGroups(setOf(10, 20)))
        assertEquals(
            ShelfFilter(tagIds = setOf(2)),
            filter.withExistingGroups(setOf(20))
        )
    }

    @Test
    fun `未分组筛选独立表达`() {
        assertEquals(
            listOf(3L),
            filterShelfBooks(books, refs, ShelfFilter(ungroupedOnly = true)).map(BookEntity::id)
        )
    }

    private fun book(id: Long, groupId: Long?) = BookEntity(
        id = id,
        title = "书$id",
        author = "",
        coverPath = null,
        epubPath = "/$id.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = id,
        totalChapters = 1,
        groupId = groupId
    )
}
