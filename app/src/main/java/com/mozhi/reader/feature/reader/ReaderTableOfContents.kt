package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity

internal data class ReaderTocItem(
    val key: String,
    val orderIndex: Int,
    val title: String,
    val depth: Int,
    val parentOrderIndex: Int?,
    val chapterIndex: Int?,
    val href: String,
    val hasChildren: Boolean
)

internal fun buildReaderTocItems(
    chapters: List<ChapterEntity>,
    entries: List<BookTocEntryEntity>
): List<ReaderTocItem> = if (entries.isNotEmpty()) {
    entries.map { entry ->
        ReaderTocItem(
            key = "toc-${entry.id}",
            orderIndex = entry.orderIndex,
            title = entry.title,
            depth = entry.depth,
            parentOrderIndex = entry.parentOrderIndex,
            chapterIndex = entry.chapterIndex,
            href = entry.href,
            hasChildren = entry.hasChildren
        )
    }
} else {
    chapters.map { chapter ->
        ReaderTocItem(
            key = "chapter-${chapter.id}",
            orderIndex = chapter.chapterIndex,
            title = chapter.title,
            depth = 0,
            parentOrderIndex = null,
            chapterIndex = chapter.chapterIndex,
            href = chapter.href,
            hasChildren = false
        )
    }
}

internal fun visibleReaderTocItems(
    items: List<ReaderTocItem>,
    collapsedOrderIndices: Set<Int>
): List<ReaderTocItem> = buildList {
    var hiddenBelowDepth: Int? = null
    items.forEach { item ->
        val hiddenDepth = hiddenBelowDepth
        if (hiddenDepth != null && item.depth > hiddenDepth) return@forEach
        if (hiddenDepth != null) hiddenBelowDepth = null
        add(item)
        if (item.hasChildren && item.orderIndex in collapsedOrderIndices) {
            hiddenBelowDepth = item.depth
        }
    }
}

internal fun currentReaderTocOrder(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int? = items
    .filter { it.chapterIndex == currentChapterIndex }
    .maxWithOrNull(compareBy<ReaderTocItem> { it.depth }.thenBy { it.orderIndex })
    ?.orderIndex

internal fun currentReaderTocListIndex(
    allItems: List<ReaderTocItem>,
    visibleItems: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int {
    val itemByOrder = allItems.associateBy(ReaderTocItem::orderIndex)
    var targetOrder = currentReaderTocOrder(allItems, currentChapterIndex)
    val visibleOrders = visibleItems.mapTo(mutableSetOf(), ReaderTocItem::orderIndex)
    while (targetOrder != null && targetOrder !in visibleOrders) {
        targetOrder = itemByOrder[targetOrder]?.parentOrderIndex
    }
    return visibleItems.indexOfFirst { it.orderIndex == targetOrder }.coerceAtLeast(0)
}
