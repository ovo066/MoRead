package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.BookTocEntryDraft
import com.mozhi.reader.core.library.ChapterDraft

internal data class EpubNavigationNode(
    val title: String?,
    val href: String?,
    val children: List<EpubNavigationNode> = emptyList()
)

internal data class EpubReadingOrderItem(
    val title: String?,
    val href: String
)

internal data class EpubImportStructure(
    val chapters: List<ChapterDraft>,
    val tocEntries: List<BookTocEntryDraft>
)

/** Keeps the EPUB navigation tree instead of reducing it to a flat href-to-title map. */
internal fun buildEpubImportStructure(
    readingOrder: List<EpubReadingOrderItem>,
    tableOfContents: List<EpubNavigationNode>
): EpubImportStructure {
    val flattenedToc = flattenToc(tableOfContents)
    val chapterIndexByHref = readingOrder
        .mapIndexed { index, item -> normalizeEpubHref(item.href) to index }
        .toMap()
    val tocByHref = flattenedToc
        .filter { it.normalizedHref.isNotEmpty() }
        .groupBy(FlattenedTocNode::normalizedHref)

    val chapters = readingOrder.mapIndexed { index, item ->
        val titleFromToc = tocByHref[normalizeEpubHref(item.href)]
            .orEmpty()
            .filter { it.title.isNotBlank() }
            .maxWithOrNull(
                compareBy<FlattenedTocNode> { it.depth }
                    .thenBy { if (it.hasChildren) 0 else 1 }
                    .thenBy { it.orderIndex }
            )
            ?.title
        ChapterDraft(
            index = index,
            title = titleFromToc
                ?: item.title?.trim()?.takeIf(String::isNotEmpty)
                ?: "第 ${index + 1} 章",
            href = item.href,
            charCount = 0
        )
    }

    val tocEntries = if (flattenedToc.isEmpty()) {
        chapters.map { chapter ->
            BookTocEntryDraft(
                orderIndex = chapter.index,
                title = chapter.title,
                href = chapter.href,
                depth = 0,
                parentOrderIndex = null,
                chapterIndex = chapter.index,
                hasChildren = false
            )
        }
    } else {
        flattenedToc.map { node ->
            val chapterIndex = chapterIndexByHref[node.normalizedHref]
            BookTocEntryDraft(
                orderIndex = node.orderIndex,
                title = node.title.ifBlank {
                    chapterIndex?.let { chapters[it].title } ?: "未命名目录"
                },
                href = node.href,
                depth = node.depth,
                parentOrderIndex = node.parentOrderIndex,
                chapterIndex = chapterIndex,
                hasChildren = node.hasChildren
            )
        }
    }
    return EpubImportStructure(chapters = chapters, tocEntries = tocEntries)
}

private data class FlattenedTocNode(
    val orderIndex: Int,
    val title: String,
    val href: String,
    val normalizedHref: String,
    val depth: Int,
    val parentOrderIndex: Int?,
    val hasChildren: Boolean
)

private fun flattenToc(nodes: List<EpubNavigationNode>): List<FlattenedTocNode> = buildList {
    fun append(items: List<EpubNavigationNode>, depth: Int, parentOrderIndex: Int?) {
        items.forEach { node ->
            val orderIndex = size
            val href = node.href.orEmpty()
            add(
                FlattenedTocNode(
                    orderIndex = orderIndex,
                    title = node.title.orEmpty().trim(),
                    href = href,
                    normalizedHref = normalizeEpubHref(href),
                    depth = depth,
                    parentOrderIndex = parentOrderIndex,
                    hasChildren = node.children.isNotEmpty()
                )
            )
            append(node.children, depth + 1, orderIndex)
        }
    }
    append(nodes, depth = 0, parentOrderIndex = null)
}

private fun normalizeEpubHref(href: String): String = href
    .substringBefore('#')
    .replace('\\', '/')
    .removePrefix("./")
