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
    val hasChildren: Boolean,
    val displayNumber: Int?
)

internal fun buildReaderTocItems(
    chapters: List<ChapterEntity>,
    entries: List<BookTocEntryEntity>
): List<ReaderTocItem> = if (entries.isNotEmpty()) {
    val leafDestinations = entries.filter { it.chapterIndex != null && !it.hasChildren }
    val explicitChapters = leafDestinations.filter { it.title.isExplicitChapterTitle() }
    val logicalChapters = if (
        explicitChapters.size >= MIN_EXPLICIT_CHAPTERS &&
        explicitChapters.size * 2 >= leafDestinations.size
    ) {
        explicitChapters
    } else {
        leafDestinations.ifEmpty { entries.filter { it.chapterIndex != null } }
    }
    val numberedOrders = logicalChapters.mapTo(hashSetOf(), BookTocEntryEntity::orderIndex)
    var nextDisplayNumber = 0
    entries.map { entry ->
        ReaderTocItem(
            key = "toc-${entry.id}",
            orderIndex = entry.orderIndex,
            title = entry.title,
            depth = entry.depth,
            parentOrderIndex = entry.parentOrderIndex,
            chapterIndex = entry.chapterIndex,
            href = entry.href,
            hasChildren = entry.hasChildren,
            displayNumber = if (entry.orderIndex in numberedOrders) ++nextDisplayNumber else null
        )
    }
} else {
    chapters.mapIndexed { displayIndex, chapter ->
        ReaderTocItem(
            key = "chapter-${chapter.id}",
            orderIndex = chapter.chapterIndex,
            title = chapter.title,
            depth = 0,
            parentOrderIndex = null,
            chapterIndex = chapter.chapterIndex,
            href = chapter.href,
            hasChildren = false,
            displayNumber = displayIndex + 1
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
): Int? = nearestReaderTocItem(items, currentChapterIndex)?.orderIndex

internal fun readerTocDisplayCount(items: List<ReaderTocItem>): Int =
    items.count { it.displayNumber != null }

internal fun readerTocSupplementaryCount(items: List<ReaderTocItem>): Int =
    items.count { it.chapterIndex != null && !it.hasChildren && it.displayNumber == null }

internal fun currentReaderTocDisplayNumber(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): Int? = nearestReaderTocItem(
    items = items.filter { it.displayNumber != null },
    currentChapterIndex = currentChapterIndex
)
    ?.takeIf { requireNotNull(it.chapterIndex) <= currentChapterIndex }
    ?.displayNumber

/**
 * EPUB 的一个逻辑章节可能被排版工具拆成多个连续 spine 文档。目录通常只链接第一个
 * 文档，因此读到后续正文文档时，也应继续把最近的前置目录项视为当前章节。
 */
private fun nearestReaderTocItem(
    items: List<ReaderTocItem>,
    currentChapterIndex: Int
): ReaderTocItem? {
    val linkedItems = items.filter { it.chapterIndex != null }
    return linkedItems
        .filter { requireNotNull(it.chapterIndex) <= currentChapterIndex }
        .maxWithOrNull(
            compareBy<ReaderTocItem> { it.chapterIndex }
                .thenBy { it.depth }
                .thenBy { it.orderIndex }
        )
        ?: linkedItems.minWithOrNull(
            compareBy<ReaderTocItem> { it.chapterIndex }
                .thenByDescending { it.depth }
                .thenBy { it.orderIndex }
        )
}

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


private const val MIN_EXPLICIT_CHAPTERS = 2
private val EXPLICIT_CHAPTER_TITLE = Regex(
    pattern = """^\s*(?:第\s*[0-9０-９〇零一二三四五六七八九十百千万两廿卅卌]+\s*[章节回篇卷部]|(?:chapter|chapitre|kapitel|cap[ií]tulo)\s+[0-9ivxlcdm]+\b)""",
    option = RegexOption.IGNORE_CASE
)

private fun String.isExplicitChapterTitle(): Boolean = EXPLICIT_CHAPTER_TITLE.containsMatchIn(this)
