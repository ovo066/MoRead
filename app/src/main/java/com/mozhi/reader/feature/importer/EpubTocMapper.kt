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
    val href: String,
    val textSample: String? = null
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

    // XHTML 自带的 <head><title>：真章名的第一手来源，但排版工具常把书名写满每个文档。
    // 被两个以上文档共用的标题一律视为模板噪声（拆页的「标题页 + 正文页」正好是两个）。
    val documentTitleCounts = readingOrder
        .mapNotNull { it.title.meaningfulDocumentTitle() }
        .groupingBy { it }
        .eachCount()

    // 排版工具常把一回拆成「标题页 + 正文页」多个 spine 文档，而目录只链接第一个。
    // 未被目录引用的后续文档是同一章的延续，沿用最近一条目录标题；目录之前的
    // 卷首文档（封面/版权页）按 href 语义命名，不再冒出「第 6 章」这类假章名。
    var inheritedTitle: String? = null
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
        val documentTitle = item.title.meaningfulDocumentTitle()
            ?.takeIf { (documentTitleCounts[it] ?: 0) <= MAX_SHARED_DOCUMENT_TITLE }
        // 作者写下的 <title> 强于按文件名猜结构页；而「整页就是一份目录」是内容证据，
        // 比两者都硬（导航页的 <title> 往往是「未知」或干脆写着书名）。
        val title = titleFromToc
            ?: contentsPageTitle(item.textSample)
            ?: documentTitle
            ?: structuralPageTitle(item.href)
            ?: inheritedTitle?.takeIf { flattenedToc.isNotEmpty() }
            ?: "卷首".takeIf { flattenedToc.isNotEmpty() }
            ?: "第 ${index + 1} 章"
        if (titleFromToc != null) inheritedTitle = titleFromToc
        ChapterDraft(
            index = index,
            title = title,
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

/**
 * 用库里的目录树修补占位章名：返回 (chapterIndex, 新标题)。逻辑与导入期一致——
 * 沿用最近一条在前的目录标题，目录之前的卷首文档按 href 语义命名。
 */
internal fun repairPlaceholderChapterTitles(
    chapters: List<ChapterDraft>,
    tocTitles: List<Pair<Int, String>>,
    chapterTextHints: Map<Int, String> = emptyMap()
): List<Pair<Int, String>> {
    val sortedToc = tocTitles.sortedBy { it.first }
    return chapters.mapNotNull { chapter ->
        if (!PLACEHOLDER_CHAPTER_TITLE.matches(chapter.title)) return@mapNotNull null
        val current = sortedToc.lastOrNull { it.first <= chapter.index }
        val title = contentsPageTitle(chapterTextHints[chapter.index])
            ?: structuralPageTitle(chapter.href)
            ?: current?.second
            ?: "卷首"
        if (title != chapter.title) chapter.index to title else null
    }
}

/** 正文以「目录」开头的导航页；kindlegen 生成的尾部目录页正是这种。 */
private fun contentsPageTitle(textSample: String?): String? = "目录".takeIf {
    CONTENTS_TEXT_PREFIX.containsMatchIn(textSample.orEmpty().trim().replace(WHITESPACE_RUN, " "))
}

/** 按 EPUB 惯用文件名认结构页；只在没有目录标题与文档标题时才轮到它。 */
private fun structuralPageTitle(href: String): String? {
    val name = normalizeEpubHref(href).substringAfterLast('/').substringBeforeLast('.').lowercase()
    return when {
        name.contains("cover") -> "封面"
        name.contains("title") -> "扉页"
        name.contains("copyright") || name.contains("colophon") -> "版权页"
        name == "toc" || name.startsWith("toc_") || name.startsWith("toc-") ||
            name.contains("contents") || name.contains("navigation") || name == "nav" -> "目录"
        else -> null
    }
}

private fun String?.meaningfulDocumentTitle(): String? = this
    ?.trim()
    ?.takeIf(String::isNotEmpty)
    ?.takeUnless { it.lowercase() in MEANINGLESS_DOCUMENT_TITLES }

private const val MAX_SHARED_DOCUMENT_TITLE = 2
private val WHITESPACE_RUN = Regex("""\s+""")
private val PLACEHOLDER_CHAPTER_TITLE = Regex("""^第 \d+ 章$""")
private val CONTENTS_TEXT_PREFIX = Regex("""^(?:未知\s*)?目录(?:\s|$)""", RegexOption.IGNORE_CASE)
private val MEANINGLESS_DOCUMENT_TITLES = setOf("未知", "unknown", "untitled", "无标题")

private fun normalizeEpubHref(href: String): String = href
    .substringBefore('#')
    .replace('\\', '/')
    .removePrefix("./")
