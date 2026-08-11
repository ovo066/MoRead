package com.mozhi.reader.feature.reader.engine

import kotlin.math.max

/**
 * 章内连续条带：把分页布局无损重建为一根可连续滚动的长列。
 *
 * 排版器切页时会重置 durY，并吞掉页首间距（addSpacing 对空页不生效），因此不能把页
 * 直接首尾相接。这里按切缝前最后一行的自然推进（行距/标题距/图片高）加上被吞掉的
 * 段距把缝隙补回来，使跨页行距与页内行距完全一致 —— 同一份 TextChapter 既喂翻页
 * 模式也喂滚动模式，两套渲染共享布局与批注/选区几何。
 */
class ChapterStrip(
    val chapter: TextChapter,
    private val spec: TypesetSpec
) {
    /** 每页内容原点（页内 lineTop = 0 处）的条带 Y。 */
    val pageTops: FloatArray
    val totalHeight: Float

    init {
        val tops = FloatArray(chapter.pages.size.coerceAtLeast(1))
        var cursor = 0f
        chapter.pages.forEachIndexed { index, page ->
            tops[index] = cursor
            cursor += pageExtent(page)
        }
        pageTops = tops
        // 空章也占一屏，滚动流里始终有实体可停留。
        totalHeight = max(cursor, spec.visibleHeight)
    }

    /** 页的条带占高 = 最后一行的基线推进 + 被切缝吞掉的段距/标题距。 */
    private fun pageExtent(page: TextPage): Float {
        val last = page.lines.lastOrNull() ?: return spec.visibleHeight
        val naturalStep = when {
            last.inlineImage != null -> last.lineBottom - last.lineTop
            last.isTitle -> spec.titleLineStep
            else -> spec.contentLineStep
        }
        val advance = last.lineTop + max(naturalStep, last.lineBottom - last.lineTop)
        // 排版器把切缝处已应用的间隙记在 trailingGap 上：空行分段的书这里是段距的两倍，
        // 靠 isParagraphEnd 反推猜不出来。
        return advance + page.trailingGap
    }

    /** 覆盖 [stripY] 的页：pageTops 中最后一个 <= stripY 的页。 */
    fun pageIndexAt(stripY: Float): Int {
        if (pageTops.isEmpty()) return 0
        var low = 0
        var high = pageTops.lastIndex
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (pageTops[mid] <= stripY) low = mid else high = mid - 1
        }
        return low
    }

    /** 条带 Y 处（或其后最近）的正文字符偏移，用于把滚动位置写回 (chapter, charOffset)。 */
    fun charOffsetAt(stripY: Float): Int {
        val pages = chapter.pages
        if (pages.isEmpty()) return 0
        var pageIndex = pageIndexAt(stripY)
        val localY = stripY - pageTops[pageIndex]
        pages[pageIndex].lines
            .firstOrNull { it.charLength > 0 && it.lineBottom > localY }
            ?.let { return it.chapterPosition }
        // 落在页尾缝隙或纯合成行上：取其后第一处正文。
        while (++pageIndex < pages.size) {
            pages[pageIndex].lines.firstOrNull { it.charLength > 0 }
                ?.let { return it.chapterPosition }
        }
        return pages[pageIndexAt(stripY)].chapterPosition
    }

    /** 字符偏移所在行的条带 Y（行顶），用于开书/跳转/换排版后的重锚。 */
    fun stripYOf(charOffset: Int): Float {
        val pages = chapter.pages
        if (pages.isEmpty()) return 0f
        val pageIndex = chapter.pageIndexAt(charOffset)
        val page = pages[pageIndex]
        val line = page.lines.lastOrNull { it.charLength > 0 && it.chapterPosition <= charOffset }
            ?: page.lines.firstOrNull()
        return pageTops[pageIndex] + (line?.lineTop ?: 0f)
    }

    /** 最后一章的锚点上限：让末页底边贴住视口底。 */
    fun maxAnchor(viewportHeight: Float): Float = max(0f, totalHeight - viewportHeight)
}
