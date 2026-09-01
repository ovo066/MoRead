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
            val collection = collectionId?.let(collectionsById::get)
            if (collection == null) add(ShelfEntry.Book(book))
            else if (emitted.add(collection.id)) {
                add(ShelfEntry.Collection(collection, membersByCollection.getValue(collection.id)))
            }
        }
    }
}

internal fun List<BookEntity>.orderedForShelf(
    savedOrder: List<Long>,
    readingOrderAffectsShelf: Boolean,
    readAnchor: Long
): List<BookEntity> {
    val fallback = sortedWith(
        compareByDescending<BookEntity> { it.pinnedAt }
            .thenByDescending { if (readingOrderAffectsShelf) it.lastReadAt else 0L }
            .thenByDescending { it.importedAt }
    )
    if (savedOrder.isEmpty()) return fallback
    val byId = associateBy(BookEntity::id)
    val savedIds = savedOrder.toHashSet()
    val base = fallback.filterNot { it.id in savedIds } + savedOrder.mapNotNull(byId::get)
    val pinned = base.filter { it.pinnedAt > 0L }
    val unpinned = base.filter { it.pinnedAt == 0L }
    if (!readingOrderAffectsShelf) return pinned + unpinned
    val newlyRead = unpinned.filter { it.lastReadAt > readAnchor }
        .sortedByDescending(BookEntity::lastReadAt)
    val newlyReadIds = newlyRead.mapTo(hashSetOf(), BookEntity::id)
    return pinned + newlyRead + unpinned.filterNot { it.id in newlyReadIds }
}

internal fun reorderShelfEntries(
    entries: List<ShelfEntry>,
    sourceBookId: Long,
    targetKey: String,
    after: Boolean
): List<ShelfEntry> {
    val sourceIndex = entries.indexOfFirst {
        it is ShelfEntry.Book && it.book.id == sourceBookId
    }
    if (sourceIndex < 0) return entries
    val moved = entries[sourceIndex]
    val reordered = entries.toMutableList().apply { removeAt(sourceIndex) }
    val targetIndex = reordered.indexOfFirst { it.key == targetKey }
    if (targetIndex < 0) return entries
    reordered.add(targetIndex + if (after) 1 else 0, moved)
    return reordered
}

internal fun mergeVisibleShelfOrder(
    allBookIds: List<Long>,
    visibleBookIds: List<Long>
): List<Long> {
    val visible = visibleBookIds.toHashSet()
    val reordered = visibleBookIds.iterator()
    return allBookIds.map { id -> if (id in visible) reordered.next() else id }
}

internal fun reorderCollectionMembers(
    books: List<BookEntity>,
    sourceBookId: Long,
    targetBookId: Long,
    after: Boolean
): List<BookEntity> {
    val sourceIndex = books.indexOfFirst { it.id == sourceBookId }
    if (sourceIndex < 0) return books
    val moved = books[sourceIndex]
    val reordered = books.toMutableList().apply { removeAt(sourceIndex) }
    val targetIndex = reordered.indexOfFirst { it.id == targetBookId }
    if (targetIndex < 0) return books
    reordered.add(targetIndex + if (after) 1 else 0, moved)
    return reordered
}
