package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.readState

enum class TagMatchMode { ANY, ALL }

data class ShelfFilter(
    val readState: BookReadState? = null,
    /** null = 全部；0 = 未分组；其他值 = 指定分组。 */
    val groupId: Long? = null,
    val ungroupedOnly: Boolean = false,
    val tagIds: Set<Long> = emptySet(),
    val matchMode: TagMatchMode = TagMatchMode.ANY
) {
    val isActive: Boolean
        get() = readState != null || groupId != null || ungroupedOnly || tagIds.isNotEmpty()
}

internal fun ShelfFilter.withExistingGroups(groupIds: Set<Long>): ShelfFilter =
    if (groupId != null && groupId !in groupIds) copy(groupId = null) else this

fun filterShelfBooks(
    books: List<BookEntity>,
    refs: List<BookTagRefEntity>,
    filter: ShelfFilter
): List<BookEntity> {
    val tagsByBook = refs.groupBy(BookTagRefEntity::bookId)
        .mapValues { (_, rows) -> rows.map(BookTagRefEntity::tagId).toSet() }
    return books.filter { book ->
        val bookTags = tagsByBook[book.id].orEmpty()
        val tagsMatch = when {
            filter.tagIds.isEmpty() -> true
            filter.matchMode == TagMatchMode.ANY -> bookTags.any(filter.tagIds::contains)
            else -> bookTags.containsAll(filter.tagIds)
        }
        (filter.readState == null || book.readState() == filter.readState) &&
            (filter.groupId == null || book.groupId == filter.groupId) &&
            (!filter.ungroupedOnly || book.groupId == null) &&
            tagsMatch
    }
}
