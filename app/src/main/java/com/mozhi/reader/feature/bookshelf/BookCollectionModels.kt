package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookEntity

sealed interface ShelfEntry {
    val key: String
    val bookIds: Set<Long>

    data class Book(val book: BookEntity) : ShelfEntry {
        override val key = "book:${book.id}"
        override val bookIds = setOf(book.id)
    }

    data class Collection(
        val collection: BookCollectionEntity,
        val books: List<BookEntity>
    ) : ShelfEntry {
        override val key = "collection:${collection.id}"
        override val bookIds = books.mapTo(linkedSetOf(), BookEntity::id)
    }
}

fun buildShelfEntries(
    visibleBooks: List<BookEntity>,
    allBooks: List<BookEntity>,
    collections: List<BookCollectionEntity>
): List<ShelfEntry> {
    val collectionsById = collections.associateBy(BookCollectionEntity::id)
    val membersByCollection = allBooks
        .filter { it.collectionId != null }
        .groupBy { requireNotNull(it.collectionId) }
        .mapValues { (_, books) -> books.sortedWith(compareBy(BookEntity::collectionOrder).thenBy(BookEntity::id)) }
    val emitted = mutableSetOf<Long>()
    return buildList {
        visibleBooks.forEach { book ->
            val collectionId = book.collectionId
            if (collectionId == null) add(ShelfEntry.Book(book))
            else if (emitted.add(collectionId)) {
                add(ShelfEntry.Collection(collectionsById.getValue(collectionId), membersByCollection.getValue(collectionId)))
            }
        }
    }
}
