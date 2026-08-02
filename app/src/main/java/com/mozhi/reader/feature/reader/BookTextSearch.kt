package com.mozhi.reader.feature.reader

/** 书内关键词搜索命中：charOffset 为章内 UTF-16 偏移，与 jumpToChapter 同轨直接跳转。 */
data class BookSearchHit(
    val chapterIndex: Int,
    val chapterTitle: String,
    val charOffset: Int,
    val snippet: String,
    /** 命中词在 snippet 内的起始位置与长度，供高亮。 */
    val matchStartInSnippet: Int,
    val matchLength: Int
)

/**
 * 单章逐词扫描（忽略大小写）；每章命中上限防止「的」之类的词把结果撑爆。
 * 摘要窗口取命中前后各 [SNIPPET_RADIUS] 个字符，换行折叠成空格。
 */
fun searchChapterText(
    chapterIndex: Int,
    chapterTitle: String,
    body: String,
    query: String,
    maxHitsPerChapter: Int = MAX_HITS_PER_CHAPTER
): List<BookSearchHit> {
    val clean = query.trim()
    if (clean.isEmpty() || body.isEmpty()) return emptyList()
    val hits = mutableListOf<BookSearchHit>()
    var from = 0
    while (hits.size < maxHitsPerChapter) {
        val found = body.indexOf(clean, from, ignoreCase = true)
        if (found < 0) break
        val snippetStart = (found - SNIPPET_RADIUS).coerceAtLeast(0)
        val snippetEnd = (found + clean.length + SNIPPET_RADIUS).coerceAtMost(body.length)
        val snippet = body.substring(snippetStart, snippetEnd)
            .replace('\n', ' ')
            .replace('\r', ' ')
        hits += BookSearchHit(
            chapterIndex = chapterIndex,
            chapterTitle = chapterTitle,
            charOffset = found,
            snippet = snippet,
            matchStartInSnippet = found - snippetStart,
            matchLength = clean.length
        )
        from = found + clean.length.coerceAtLeast(1)
    }
    return hits
}

const val SNIPPET_RADIUS = 30
private const val MAX_HITS_PER_CHAPTER = 50
