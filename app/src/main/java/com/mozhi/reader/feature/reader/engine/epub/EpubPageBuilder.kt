package com.mozhi.reader.feature.reader.engine.epub

import com.mozhi.reader.core.epub.style.EpubStyle
import com.mozhi.reader.feature.reader.engine.TextChapter
import com.mozhi.reader.feature.reader.engine.TextPage
import kotlin.math.max

/**
 * Splits the continuous-y [FlowOutput] into [TextPage]s. Cuts always land on a line top and honor
 * forced breaks, keep-together ranges, heading keep-with-next and orphans/widows. Decorations are
 * cropped per page with their cut edges closed, matching the previous engine's card behavior.
 */
internal class EpubPageBuilder(private val ctx: EpubLayoutContext) {

    fun build(
        output: FlowOutput,
        chapterIndex: Int,
        title: String,
        bodyStyle: EpubStyle?,
        hideHeaderFirstPage: Boolean
    ): TextChapter {
        val lines = output.lines
        if (lines.isEmpty()) {
            return TextChapter(chapterIndex, title, listOf(emptyPage()), ctx.body.length)
        }
        val cuts = computeCuts(output)
        val pages = ArrayList<TextPage>(cuts.size)
        val forced = output.forcedBreaks.sorted()

        val pageBackground = pageBackgroundColor(bodyStyle)
        val pageBackgroundImage = pageBackgroundImage(bodyStyle)

        cuts.forEachIndexed { pageIndex, cut ->
            ctx.cancellationCheck()
            val capacity = capacity(pageIndex)
            val pageLines = lines.subList(cut.startIndex, cut.endIndex)
            val dy = -cut.startY
            pageLines.forEach { flowLine -> FlowOutput.translateLine(flowLine.line, 0f, dy) }
            if (ctx.spec.bottomAlign) bottomAlign(pageLines, capacity)
            val textLines = pageLines.map { it.line }
            val first = textLines.firstOrNull { it.charLength > 0 } ?: textLines.first()
            val nextStart = cuts.getOrNull(pageIndex + 1)?.startY
            val lastBottomAbsolute = textLines.maxOf { it.lineBottom } + cut.startY
            pages += TextPage(
                index = pageIndex,
                lines = textLines,
                chapterPosition = first.chapterPosition,
                charLength = textLines.sumOf { it.charLength },
                height = textLines.maxOf { it.lineBottom },
                decorations = pageDecorations(output, cut.startY, capacity, nextStart),
                backgroundColorArgb = pageBackground,
                backgroundImagePath = pageBackgroundImage,
                backgroundOpacity = bodyStyle?.opacity ?: 1f,
                immersive = ctx.immersivePage,
                hideHeader = pageIndex == 0 && hideHeaderFirstPage,
                trailingGap = nextStart?.let { (it - lastBottomAbsolute).coerceAtLeast(0f) } ?: 0f
            )
        }
        return TextChapter(chapterIndex, title, pages, ctx.body.length)
    }

    private fun emptyPage() = TextPage(
        index = 0,
        lines = emptyList(),
        chapterPosition = 0,
        charLength = 0,
        height = 0f,
        immersive = ctx.immersivePage
    )

    private class PageCut(val startIndex: Int, val endIndex: Int, val startY: Float)

    private fun computeCuts(output: FlowOutput): List<PageCut> {
        val lines = output.lines
        val forced = output.forcedBreaks.sorted()
        val cuts = ArrayList<PageCut>()
        var pageStartIndex = 0
        var pageStartY = 0f
        var forcedCursor = 0
        // Forced breaks recorded before the first line only shift the page origin, never split.
        while (forcedCursor < forced.size && forced[forcedCursor] <= lines[pageStartIndex].line.lineTop + EPSILON) {
            forcedCursor++
        }
        var index = 1
        while (index < lines.size) {
            ctx.cancellationCheck()
            val capacity = capacity(cuts.size)
            val line = lines[index].line
            var mustCut = false
            while (forcedCursor < forced.size && forced[forcedCursor] <= line.lineTop + EPSILON) {
                if (forced[forcedCursor] > lines[pageStartIndex].line.lineTop + EPSILON) mustCut = true
                forcedCursor++
            }
            if (!mustCut && line.lineBottom - pageStartY <= capacity + EPSILON) {
                index++
                continue
            }
            var cutIndex = adjustCut(output, pageStartIndex, index, mustCut)
            if (cutIndex <= pageStartIndex) cutIndex = index
            cuts += PageCut(pageStartIndex, cutIndex, pageStartY)
            pageStartIndex = cutIndex
            pageStartY = lines[cutIndex].line.lineTop
            index = max(index, cutIndex + 1)
        }
        cuts += PageCut(pageStartIndex, lines.size, pageStartY)
        return cuts
    }

    /** Moves a proposed cut index earlier until every pagination constraint is satisfied. */
    private fun adjustCut(output: FlowOutput, pageStart: Int, proposed: Int, forcedBreak: Boolean): Int {
        val lines = output.lines
        var cut = proposed
        var guard = 0
        while (guard++ < 200 && cut > pageStart) {
            var moved = false
            val cutY = lines[cut].line.lineTop
            if (!forcedBreak) {
                // Keep-together ranges (page-break-inside:avoid, bubbles, table rows) that fit one page.
                output.keepRanges.forEach { range ->
                    val height = range.endInclusive - range.start
                    if (height <= capacity(0) && cutY > range.start + EPSILON && cutY < range.endInclusive - EPSILON) {
                        while (cut > pageStart && lines[cut - 1].line.lineTop >= range.start - EPSILON &&
                            lines[cut - 1].line.lineBottom <= range.endInclusive + EPSILON
                        ) {
                            cut--
                            moved = true
                        }
                        if (moved) return@forEach
                    }
                }
            }
            // A straddling line (usually a float) forbids this cut; retreat to its top.
            if (!moved) {
                for (k in pageStart until cut) {
                    if (lines[k].line.lineBottom > lines[cut].line.lineTop + EPSILON) {
                        cut = k
                        moved = true
                        break
                    }
                }
            }
            if (!moved && !forcedBreak) {
                val flowLine = output.lines[cut]
                // Widows: too few paragraph lines after the cut pull more lines with them.
                val after = flowLine.paragraphLineCount - flowLine.indexInParagraph
                if (flowLine.indexInParagraph > 0 && after in 1 until flowLine.widows &&
                    flowLine.paragraphLineCount > flowLine.widows
                ) {
                    val target = cut - (flowLine.widows - after)
                    if (target > pageStart) {
                        cut = target
                        moved = true
                    }
                }
                // Orphans: too few paragraph lines before the cut push the whole paragraph over.
                if (!moved && flowLine.indexInParagraph in 1 until flowLine.orphans) {
                    val target = cut - flowLine.indexInParagraph
                    if (target > pageStart) {
                        cut = target
                        moved = true
                    }
                }
            }
            // A heading (or avoid-break-after block) never ends a page.
            if (!moved && cut > pageStart && lines[cut - 1].keepWithNext) {
                cut--
                moved = true
            }
            if (!moved) return cut
        }
        return cut
    }

    private fun pageDecorations(
        output: FlowOutput,
        pageStartY: Float,
        capacity: Float,
        nextStartY: Float?
    ) = output.decorations.mapNotNull { entry ->
        ctx.cancellationCheck()
        if (entry.isCanvas && ctx.spec.preferReaderBackground) return@mapNotNull null
        val decoration = entry.decoration
        if (decoration.right <= decoration.left || decoration.bottom <= decoration.top) return@mapNotNull null
        val pageEndY = nextStartY ?: Float.MAX_VALUE
        if (decoration.bottom <= pageStartY + EPSILON || decoration.top >= pageEndY - EPSILON) return@mapNotNull null
        val top = (decoration.top - pageStartY).coerceAtLeast(0f)
        val bottom = (decoration.bottom - pageStartY).coerceAtMost(capacity)
        if (bottom <= top) return@mapNotNull null
        // 分页片段在断口补齐上下描边，避免卡片像被屏幕硬生生裁断。
        decoration.copy(top = top, bottom = bottom)
    }

    private fun pageBackgroundColor(bodyStyle: EpubStyle?): Int? {
        val color = bodyStyle?.background?.colorArgb ?: return null
        // 用户自定义纸张 > 出版物根画布：仅在用户未选自定义纸张时铺出版底色。
        if (ctx.spec.preferReaderBackground) return null
        return ctx.mappedBackground(color)
    }

    private fun pageBackgroundImage(bodyStyle: EpubStyle?): String? {
        val href = bodyStyle?.background?.imageHref ?: return null
        if (ctx.spec.preferReaderBackground && !ctx.immersivePage) return null
        return ctx.bundle.resourcePaths[href]
    }

    private fun capacity(pageIndex: Int): Float = ctx.spec.visibleHeight + if (pageIndex == 0) {
        (if (ctx.immersivePage || firstPageExtraTop) ctx.spec.immersiveExtraTopPx else 0f) +
            (if (ctx.immersivePage) ctx.spec.immersiveExtraBottomPx else 0f)
    } else {
        0f
    }

    var firstPageExtraTop: Boolean = false

    private fun bottomAlign(lines: List<FlowLine>, capacity: Float) {
        if (lines.size < 2) return
        val surplus = capacity - lines.last().line.lineBottom
        if (surplus <= 0f || surplus >= ctx.spec.contentLineStep) return
        val step = surplus / (lines.size - 1)
        lines.forEachIndexed { index, flowLine ->
            FlowOutput.translateLine(flowLine.line, 0f, step * index)
        }
    }

    private companion object {
        const val EPSILON = 0.5f
    }
}
