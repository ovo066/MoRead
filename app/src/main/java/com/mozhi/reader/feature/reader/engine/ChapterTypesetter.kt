package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import kotlin.math.max
import kotlin.math.min

/**
 * Layout parameters in pixels, resolved by the render layer from `ReaderSettings` + density.
 */
data class TypesetSpec(
    val visibleWidth: Float,
    val visibleHeight: Float,
    /** Baseline-to-baseline advance for content lines: fontSize × lineHeight setting. */
    val contentLineStep: Float,
    val titleLineStep: Float,
    val paragraphSpacing: Float,
    val titleTopSpacing: Float,
    val titleBottomSpacing: Float,
    val syntaxHighlightRules: List<ReaderSyntaxRule> = emptyList(),
    val indentCharCount: Int = 2,
    val bottomAlign: Boolean = true
)

/**
 * Pure-Kotlin port of Legado's `TextChapterLayout`, reduced to the text-only single-page case.
 *
 * Differences from Legado kept on purpose:
 * - The paragraph indent is applied as a first-line margin instead of injecting U+3000 characters,
 *   so body offsets in the layout match the stored text exactly.
 * - A chapter is laid out atomically; the streaming channel is unnecessary because chapter bodies
 *   are local and small.
 * - The title comes from chapter metadata. When the body's first paragraph repeats it, that
 *   paragraph is styled as the title in place (offsets keep counting); otherwise the title is
 *   synthesized with zero body length.
 */
class ChapterTypesetter(
    private val spec: TypesetSpec,
    private val measure: TextMeasure
) {
    private val contentMetrics = measure.metrics(isTitle = false)
    private val titleMetrics = measure.metrics(isTitle = true)
    private val indentWidth = measure.indentColumnWidth() * spec.indentCharCount

    fun typeset(
        chapterIndex: Int,
        title: String,
        body: String,
        inlineImages: List<InlineImageSource> = emptyList()
    ): TextChapter {
        val state = LayoutState()
        val syntax = SyntaxStyleMap(body, spec.syntaxHighlightRules)
        val imagesByOffset = inlineImages.associateBy(InlineImageSource::charOffset)
        val trimmedTitle = title.trim()
        var cursor = 0
        var firstParagraph = true

        while (cursor <= body.length) {
            val newline = body.indexOf('\n', cursor)
            val end = if (newline >= 0) newline else body.length
            val paragraph = body.substring(cursor, end)
            val isLastParagraph = newline < 0

            if (firstParagraph) {
                firstParagraph = false
                state.addSpacing(spec.titleTopSpacing, atPageTop = true)
                if (trimmedTitle.isNotEmpty() && paragraph.trim() == trimmedTitle) {
                    layoutParagraph(state, paragraph, cursor, isTitle = true, synthetic = false, syntax = syntax)
                    state.addSpacing(spec.titleBottomSpacing)
                    cursor = end + 1
                    if (isLastParagraph) break
                    continue
                }
                if (trimmedTitle.isNotEmpty()) {
                    layoutParagraph(state, trimmedTitle, cursor, isTitle = true, synthetic = true, syntax = syntax)
                    state.addSpacing(spec.titleBottomSpacing)
                }
            }

            val isImageToken = paragraph == IMAGE_PLACEHOLDER ||
                (paragraph.length == 1 && paragraph[0] == INLINE_IMAGE_CHAR)
            val inlineImage = if (isImageToken) imagesByOffset[cursor] else null
            if (inlineImage != null) {
                layoutInlineImage(state, inlineImage, cursor, paragraph.length)
            } else if (paragraph.isNotEmpty()) {
                layoutParagraph(state, paragraph, cursor, isTitle = false, synthetic = false, syntax = syntax)
            } else {
                // A blank source line keeps a full line of height, like Legado laying out "" —
                // TXT scene separators must stay visibly wider than an ordinary paragraph gap.
                blankLine(state, cursor)
            }
            state.addSpacing(spec.paragraphSpacing)

            cursor = end + 1
            if (isLastParagraph) break
        }

        state.closePage(force = true)
        return TextChapter(
            chapterIndex = chapterIndex,
            title = title,
            pages = state.pages,
            bodyLength = body.length
        )
    }

    private fun layoutInlineImage(
        state: LayoutState,
        source: InlineImageSource,
        bodyOffset: Int,
        bodyLength: Int
    ) {
        val aspect = (source.pixelWidth.toFloat() / source.pixelHeight.coerceAtLeast(1))
            .coerceIn(MIN_IMAGE_ASPECT, MAX_IMAGE_ASPECT)
        var width = min(spec.visibleWidth, source.pixelWidth.coerceAtLeast(1).toFloat())
        var height = width / aspect
        val maxHeight = spec.visibleHeight * MAX_IMAGE_HEIGHT_FRACTION
        if (height > maxHeight) {
            height = maxHeight
            width = height * aspect
        }
        state.prepareForLine(height)
        val lineTop = state.durY
        val lineBottom = lineTop + height
        state.addLine(
            TextLine(
                text = if (bodyLength == 1) INLINE_IMAGE_CHAR.toString() else IMAGE_PLACEHOLDER,
                columns = emptyList(),
                lineTop = lineTop,
                lineBase = lineBottom,
                lineBottom = lineBottom,
                startX = (spec.visibleWidth - width) / 2f,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = bodyOffset,
                charLength = bodyLength,
                inlineImage = InlineImagePlacement(
                    imagePath = source.imagePath,
                    width = width,
                    height = height,
                    altText = source.altText
                )
            ),
            lineStep = height
        )
    }

    private fun blankLine(state: LayoutState, bodyOffset: Int) {
        if (state.pendingLines.isEmpty() && state.pages.isEmpty()) return
        state.prepareForLine(contentMetrics.textHeight)
        if (state.pendingLines.isEmpty()) return // never open a page with a blank line
        val lineTop = state.durY
        val lineBottom = lineTop + contentMetrics.textHeight
        state.addLine(
            TextLine(
                text = "",
                columns = emptyList(),
                lineTop = lineTop,
                lineBase = lineBottom - contentMetrics.descent,
                lineBottom = lineBottom,
                startX = 0f,
                isTitle = false,
                isParagraphEnd = true,
                chapterPosition = bodyOffset,
                charLength = 0
            ),
            lineStep = spec.contentLineStep
        )
    }

    private fun layoutParagraph(
        state: LayoutState,
        text: String,
        bodyOffset: Int,
        isTitle: Boolean,
        synthetic: Boolean,
        syntax: SyntaxStyleMap
    ) {
        val metrics = if (isTitle) titleMetrics else contentMetrics
        val lineStep = if (isTitle) spec.titleLineStep else spec.contentLineStep
        val indent = if (isTitle) 0f else indentWidth
        val widths = measure.charWidths(text, isTitle)
        val lineStarts = measure.breakLines(text, isTitle, spec.visibleWidth, indent)

        for (lineIndex in lineStarts.indices) {
            val lineStart = lineStarts[lineIndex]
            val lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] else text.length
            if (lineStart >= lineEnd) continue
            state.prepareForLine(metrics.textHeight)

            val clusters = ArrayList<String>()
            val clusterWidths = ArrayList<Float>()
            clusterText(text, widths, lineStart, lineEnd, clusters, clusterWidths)
            // StaticLayout keeps the trailing space of a broken line; it must not push
            // justification, so trailing whitespace is measured at zero width.
            while (clusters.isNotEmpty() && clusters.last().isBlank()) {
                clusters.removeAt(clusters.lastIndex)
                clusterWidths.removeAt(clusterWidths.lastIndex)
            }
            if (clusters.isEmpty()) continue

            val startX = if (lineIndex == 0) indent else 0f
            val isLastLine = lineIndex == lineStarts.lastIndex
            val justify = !isTitle && !isLastLine
            val columns = placeClusters(
                clusters = clusters,
                widths = clusterWidths,
                startX = startX,
                justify = justify,
                absoluteOffset = if (synthetic) -1 else bodyOffset + lineStart,
                syntax = syntax
            )

            val lineTop = state.durY
            val lineBottom = lineTop + metrics.textHeight
            state.addLine(
                TextLine(
                    text = text.substring(lineStart, lineEnd),
                    columns = columns.first,
                    lineTop = lineTop,
                    lineBase = lineBottom - metrics.descent,
                    lineBottom = lineBottom,
                    startX = startX,
                    isTitle = isTitle,
                    isParagraphEnd = isLastLine,
                    chapterPosition = if (synthetic) bodyOffset else bodyOffset + lineStart,
                    charLength = if (synthetic) 0 else lineEnd - lineStart,
                    justifyGapExtra = columns.second
                ),
                lineStep = lineStep
            )
        }
    }

    /**
     * Legado's `addCharsToLineMiddle`: full justification distributes the residual width over
     * space clusters when the line has any (Latin/mixed text), otherwise over every inter-cluster
     * gap (pure CJK). Overflowing lines are compressed back inside the margin (`exceed`).
     */
    private fun placeClusters(
        clusters: List<String>,
        widths: List<Float>,
        startX: Float,
        justify: Boolean,
        absoluteOffset: Int,
        syntax: SyntaxStyleMap
    ): Pair<List<TextColumn>, Float> {
        val desired = widths.sum()
        val residual = spec.visibleWidth - startX - desired
        var spaceExtra = 0f
        var gapExtra = 0f
        if (justify && residual > 0f && clusters.size > 1 && residual <= spec.visibleWidth * MAX_JUSTIFY_FRACTION) {
            val spaceCount = clusters.count { it == " " }
            if (spaceCount > 0) {
                spaceExtra = residual / spaceCount
            } else {
                gapExtra = residual / (clusters.size - 1)
            }
        }

        val columns = ArrayList<TextColumn>(clusters.size)
        var x = startX
        var textOffset = absoluteOffset
        for (index in clusters.indices) {
            var width = widths[index]
            if (spaceExtra > 0f && clusters[index] == " " && index != clusters.lastIndex) {
                width += spaceExtra
            }
            if (gapExtra > 0f && index != clusters.lastIndex) {
                width += gapExtra
            }
            val style = syntax.at(textOffset)
            columns.add(
                TextColumn(
                    start = x,
                    end = x + widths[index],
                    charData = clusters[index],
                    syntaxColorArgb = style?.first,
                    syntaxUnderline = style?.second ?: false
                )
            )
            x += width
            if (textOffset >= 0) textOffset += clusters[index].length
        }

        // Compression fallback for lines that still overrun the right margin.
        val overrun = (columns.lastOrNull()?.end ?: 0f) - spec.visibleWidth
        if (overrun > 0.5f && columns.size > 1) {
            val perGap = overrun / (columns.size - 1)
            for (index in columns.indices) {
                val shift = perGap * index
                if (shift > 0f) {
                    val column = columns[index]
                    columns[index] = TextColumn(
                        column.start - shift,
                        column.end - shift,
                        column.charData,
                        column.syntaxColorArgb,
                        column.syntaxUnderline
                    )
                }
            }
        }
        return columns to gapExtra
    }

    private inner class LayoutState {
        val pages = ArrayList<TextPage>()
        val pendingLines = ArrayList<TextLine>()
        var durY = 0f

        /** Spacing collapses at the top of a page: Legado resets `durY` on page break too. */
        fun addSpacing(spacing: Float, atPageTop: Boolean = false) {
            if (spacing <= 0f) return
            if (pendingLines.isNotEmpty() || atPageTop) durY += spacing
        }

        fun prepareForLine(textHeight: Float) {
            if (pendingLines.isNotEmpty() && durY + textHeight > spec.visibleHeight + HEIGHT_EPSILON) {
                closePage(force = false)
            }
        }

        fun addLine(line: TextLine, lineStep: Float) {
            pendingLines.add(line)
            durY = line.lineTop + max(lineStep, line.lineBottom - line.lineTop)
        }

        fun closePage(force: Boolean) {
            if (pendingLines.isEmpty()) {
                if (force && pages.isEmpty()) {
                    pages.add(TextPage(0, emptyList(), 0, 0, 0f))
                }
                return
            }
            if (spec.bottomAlign) bottomAlign(pendingLines)
            val first = pendingLines.firstOrNull { it.charLength > 0 }
            val start = first?.chapterPosition ?: pendingLines.first().chapterPosition
            val length = pendingLines.sumOf(TextLine::charLength)
            pages.add(
                TextPage(
                    index = pages.size,
                    lines = ArrayList(pendingLines),
                    chapterPosition = start,
                    charLength = length,
                    height = pendingLines.last().lineBottom
                )
            )
            pendingLines.clear()
            durY = 0f
        }

        /**
         * Legado's `upLinesPosition`: when the page is essentially full, stretch the line gaps so
         * the last baseline sits on the bottom margin. Runs only for full pages, so the final short
         * page of a chapter keeps its natural rhythm.
         */
        private fun bottomAlign(lines: List<TextLine>) {
            if (lines.size < 2) return
            val surplus = spec.visibleHeight - lines.last().lineBottom
            if (surplus <= 0f || surplus >= spec.contentLineStep) return
            val step = surplus / (lines.size - 1)
            for (index in lines.indices) {
                val shift = step * index
                lines[index].lineTop += shift
                lines[index].lineBase += shift
                lines[index].lineBottom += shift
            }
        }
    }

    private companion object {
        const val HEIGHT_EPSILON = 0.5f
        const val INLINE_IMAGE_CHAR = '\uFFFC'
        const val IMAGE_PLACEHOLDER = "［图片］"
        const val MAX_IMAGE_HEIGHT_FRACTION = 0.72f
        const val MIN_IMAGE_ASPECT = 0.2f
        const val MAX_IMAGE_ASPECT = 5f
        /** A line whose residual exceeds this fraction is a stub (e.g. forced break) — leave it ragged. */
        const val MAX_JUSTIFY_FRACTION = 0.35f
    }

    private class SyntaxStyleMap(text: String, rules: List<ReaderSyntaxRule>) {
        private val colors = IntArray(text.length)
        private val styled = BooleanArray(text.length)
        private val underlines = BooleanArray(text.length)

        init {
            ReaderSyntaxHighlighter.spans(text, rules).forEach { span ->
                for (index in span.start until span.endExclusive.coerceAtMost(text.length)) {
                    colors[index] = span.colorArgb
                    styled[index] = true
                    underlines[index] = span.underline
                }
            }
        }

        fun at(index: Int): Pair<Int, Boolean>? =
            if (index in styled.indices && styled[index]) colors[index] to underlines[index] else null
    }
}
