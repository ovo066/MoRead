package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import org.junit.Assert.assertEquals
import org.junit.Test

class BookCollectionModelsTest {
    private val collection = BookCollectionEntity(7, "长篇系列", 100)

    @Test
    fun collectionUsesOneSlotAtItsFirstVisibleMember() {
        val first = book(1, collectionId = 7, order = 1)
        val middle = book(2)
        val second = book(3, collectionId = 7, order = 0)

        val entries = buildShelfEntries(
            visibleBooks = listOf(first, middle, second),
            allBooks = listOf(first, middle, second),
            collections = listOf(collection)
        )

        assertEquals(listOf("collection:7", "book:2"), entries.map(ShelfEntry::key))
    }

    @Test
    fun filteredCollectionStillOpensEveryMemberInCollectionOrder() {
        val first = book(1, collectionId = 7, order = 1)
        val second = book(3, collectionId = 7, order = 0)

        val entry = buildShelfEntries(
            visibleBooks = listOf(first),
            allBooks = listOf(first, second),
            collections = listOf(collection)
        ).single() as ShelfEntry.Collection

        assertEquals(listOf(3L, 1L), entry.books.map(BookEntity::id))
        assertEquals(setOf(1L, 3L), entry.bookIds)
    }

    @Test
    fun inconsistentRoomSnapshotsKeepEveryBookVisible() {
        val assignmentArrivedFirst = listOf(
            book(1, collectionId = 7),
            book(3, collectionId = 7)
        )
        val collectionArrivedFirst = book(2)

        assertEquals(
            listOf("book:1", "book:3"),
            buildShelfEntries(
                visibleBooks = assignmentArrivedFirst,
                allBooks = assignmentArrivedFirst,
                collections = emptyList()
            ).map(ShelfEntry::key)
        )
        assertEquals(
            listOf("book:2"),
            buildShelfEntries(
                visibleBooks = listOf(collectionArrivedFirst),
                allBooks = listOf(collectionArrivedFirst),
                collections = listOf(collection)
            ).map(ShelfEntry::key)
        )
    }

    private fun book(id: Long, collectionId: Long? = null, order: Int = 0) = BookEntity(
        id = id,
        title = "书$id",
        author = "",
        coverPath = null,
        epubPath = "/$id.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = id,
        totalChapters = 1,
        collectionId = collectionId,
        collectionOrder = order
    )
}
