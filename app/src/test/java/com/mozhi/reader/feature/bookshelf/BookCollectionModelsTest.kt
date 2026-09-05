package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.datastore.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun readingOrderCanBeDisabledBeforeAnyManualOrder() {
        val read = book(1, importedAt = 1, lastReadAt = 20)
        val imported = book(2, importedAt = 10)

        assertTrue(ReaderSettings().readingOrderAffectsShelf)
        assertEquals(
            listOf(1L, 2L),
            listOf(read, imported).orderedForShelf(emptyList(), true, 0).map(BookEntity::id)
        )
        assertEquals(
            listOf(2L, 1L),
            listOf(read, imported).orderedForShelf(emptyList(), false, 0).map(BookEntity::id)
        )
    }

    @Test
    fun onlyReadingAfterTheManualAnchorMovesAheadOfSavedOrder() {
        val beforeAnchor = book(1, importedAt = 1, lastReadAt = 20)
        val saved = book(2, importedAt = 10)
        val newImport = book(3, importedAt = 30)
        val newlyRead = book(4, importedAt = 4, lastReadAt = 21)

        assertEquals(
            listOf(4L, 3L, 2L, 1L),
            listOf(beforeAnchor, saved, newImport, newlyRead)
                .orderedForShelf(listOf(2, 1, 4), true, readAnchor = 20)
                .map(BookEntity::id)
        )
    }

    @Test
    fun reorderingTreatsACollectionAsOneShelfSlot() {
        val first = ShelfEntry.Book(book(1))
        val collectionEntry = ShelfEntry.Collection(
            collection,
            listOf(book(2, collectionId = 7, order = 0), book(3, collectionId = 7, order = 1))
        )
        val last = ShelfEntry.Book(book(4))
        val entries = listOf(first, collectionEntry, last)

        assertEquals(
            listOf(1L, 4L, 2L, 3L),
            reorderShelfEntries(entries, sourceBookId = 4, targetKey = "collection:7", after = false)
                .flatMap(ShelfEntry::bookIds)
        )
        assertEquals(
            listOf(2L, 3L, 1L, 4L),
            reorderShelfEntries(entries, sourceBookId = 1, targetKey = "collection:7", after = true)
                .flatMap(ShelfEntry::bookIds)
        )
    }

    @Test
    fun filteredReorderOnlyReplacesVisibleGlobalSlots() {
        assertEquals(
            listOf(5L, 2L, 3L, 4L, 1L),
            mergeVisibleShelfOrder(
                allBookIds = listOf(1, 2, 3, 4, 5),
                visibleBookIds = listOf(5, 3, 1)
            )
        )
    }

    @Test
    fun collectionMembersMoveBeforeOrAfterOneAnother() {
        val books = listOf(
            book(1, collectionId = 7, order = 0),
            book(2, collectionId = 7, order = 1),
            book(3, collectionId = 7, order = 2)
        )

        assertEquals(
            listOf(3L, 1L, 2L),
            reorderCollectionMembers(books, sourceBookId = 3, targetBookId = 1, after = false)
                .map(BookEntity::id)
        )
        assertEquals(
            listOf(2L, 3L, 1L),
            reorderCollectionMembers(books, sourceBookId = 1, targetBookId = 3, after = true)
                .map(BookEntity::id)
        )
    }

    private fun book(
        id: Long,
        collectionId: Long? = null,
        order: Int = 0,
        importedAt: Long = id,
        lastReadAt: Long = 0,
        pinnedAt: Long = 0
    ) = BookEntity(
        id = id,
        title = "书$id",
        author = "",
        coverPath = null,
        epubPath = "/$id.epub",
        sourceType = BookSourceType.EPUB,
        importedAt = importedAt,
        totalChapters = 1,
        lastReadAt = lastReadAt,
        pinnedAt = pinnedAt,
        collectionId = collectionId,
        collectionOrder = order
    )
}
