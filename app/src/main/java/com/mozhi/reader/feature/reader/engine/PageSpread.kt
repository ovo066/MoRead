package com.mozhi.reader.feature.reader.engine

/** Page pairing is chapter-local; the focus offset remains independent of this render address. */
data class SpreadRef(val chapterIndex: Int, val leftPageIndex: Int)

fun spreadFor(pageIndex: Int): Int = pageIndex.coerceAtLeast(0) and 1.inv()
fun spreadCountOf(pageCount: Int): Int = (pageCount.coerceAtLeast(0) + 1) / 2

fun rightPageOrBlank(chapter: TextChapter, left: Int): RenderPage =
    chapter.page(left + 1)?.let {
        RenderPage.Laid(chapter.chapterIndex, chapter.title, it.index, chapter.pageCount, it)
    } ?: RenderPage.Blank(chapter.chapterIndex, chapter.title)

fun spreadPageLabel(pageIndex: Int, pageCount: Int): String {
    val left = spreadFor(pageIndex) + 1
    val right = (left + 1).coerceAtMost(pageCount)
    return if (left == right) "$left / $pageCount" else "$left–$right / $pageCount"
}

/** Leaf footer labels never assign a logical page number to chapter-end paper. */
fun leafPageLabel(page: RenderPage): String = when (page) {
    is RenderPage.Laid -> "${page.pageIndex + 1} / ${page.pageCount} 页"
    is RenderPage.Blank -> "本章完"
    is RenderPage.Placeholder -> "加载中"
}
