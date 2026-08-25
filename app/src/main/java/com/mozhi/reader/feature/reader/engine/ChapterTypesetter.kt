package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderSyntaxStyleSpan
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubFloat
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.library.EpubVerticalAlign
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
    /**
     * 源文空行贡献的段间隙，与 [paragraphSpacing] 取较大者结算。空行不再占一整行正文
     * 高度——否则「空行分段」的 TXT 会有一个拿不掉的地板（整行 + 两次段距），用户拖
     * 段距滑杆几乎看不出变化。取 max 也让连续空行天然折叠成一份。
     */
    val blankLineSpacing: Float,
    val titleTopSpacing: Float,
    val titleBottomSpacing: Float,
    val syntaxHighlightRules: List<ReaderSyntaxRule> = emptyList(),
    val indentCharCount: Float = 2f,
    val justifyContent: Boolean = true,
    val bottomAlign: Boolean = true,
    val contentFontSizePx: Float = contentLineStep,
    val titleFontSizePx: Float = titleLineStep
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
        inlineImages: List<InlineImageSource> = emptyList(),
        inlineMarkers: List<InlineMarkerReservation> = emptyList(),
        epubLayout: EpubLayoutChapterBundle? = null
    ): TextChapter {
        if (epubLayout != null && epubLayout.document.textLength == body.length &&
            epubLayout.document.blocks.any { it.kind != EpubLayoutBlockKind.CONTAINER }
        ) {
            return EpubNativeTypesetter(spec, measure).typeset(
                chapterIndex,
                title,
                body,
                inlineImages,
                inlineMarkers,
                epubLayout
            )
        }
        val state = LayoutState()
        val syntax = SyntaxStyleMap(body, spec.syntaxHighlightRules)
        val imagesByOffset = inlineImages.associateBy(InlineImageSource::charOffset)
        val trimmedTitle = title.trim()
        var cursor = 0
        var firstParagraph = true
        // 段间隙在「下一段之前」结算，而不是段后立即加：空行只抬高这个待结算值，
        // 于是连续空行天然折叠，页顶折叠也仍由 addSpacing 自己负责。
        var pendingGap = 0f

        while (cursor <= body.length) {
            val newline = body.indexOf('\n', cursor)
            val end = if (newline >= 0) newline else body.length
            val paragraph = body.substring(cursor, end)
            val isLastParagraph = newline < 0

            if (firstParagraph) {
                firstParagraph = false
                state.addSpacing(spec.titleTopSpacing, atPageTop = true)
                if (trimmedTitle.isNotEmpty() && paragraph.trim() == trimmedTitle) {
                    layoutParagraph(state, paragraph, cursor, isTitle = true, synthetic = false, syntax = syntax, inlineMarkers = inlineMarkers)
                    pendingGap = spec.titleBottomSpacing
                    cursor = end + 1
                    if (isLastParagraph) break
                    continue
                }
                if (trimmedTitle.isNotEmpty()) {
                    layoutParagraph(state, trimmedTitle, cursor, isTitle = true, synthetic = true, syntax = syntax, inlineMarkers = emptyList())
                    pendingGap = spec.titleBottomSpacing
                }
            }

            val isImageToken = paragraph == IMAGE_PLACEHOLDER ||
                (paragraph.length == 1 && paragraph[0] == INLINE_IMAGE_CHAR)
            val inlineImage = if (isImageToken) imagesByOffset[cursor] else null
            when {
                inlineImage != null -> {
                    state.addSpacing(pendingGap)
                    layoutInlineImage(state, inlineImage, cursor, paragraph.length)
                    pendingGap = spec.paragraphSpacing
                }
                paragraph.isNotEmpty() -> {
                    state.addSpacing(pendingGap)
                    layoutParagraph(state, paragraph, cursor, isTitle = false, synthetic = false, syntax = syntax, inlineMarkers = inlineMarkers)
                    pendingGap = spec.paragraphSpacing
                }
                // 空行只是分段信号：抬高待结算间隙，不再占一整行正文高度。
                else -> pendingGap = max(pendingGap, spec.blankLineSpacing)
            }

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

    private fun layoutParagraph(
        state: LayoutState,
        text: String,
        bodyOffset: Int,
        isTitle: Boolean,
        synthetic: Boolean,
        syntax: SyntaxStyleMap,
        inlineMarkers: List<InlineMarkerReservation>
    ) {
        val metrics = if (isTitle) titleMetrics else contentMetrics
        val lineStep = if (isTitle) spec.titleLineStep else spec.contentLineStep
        val indent = if (isTitle) 0f else indentWidth
        val layoutText = if (synthetic) LayoutText.identity(text) else buildLayoutText(text, bodyOffset, inlineMarkers)
        val widths = measure.charWidths(layoutText.text, isTitle)
        val lineStarts = measure.breakLines(layoutText.text, isTitle, spec.visibleWidth, indent)

        for (lineIndex in lineStarts.indices) {
            val lineStart = lineStarts[lineIndex]
            val lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] else layoutText.text.length
            if (lineStart >= lineEnd) continue
            state.prepareForLine(metrics.textHeight)

            val clusters = layoutText.clusters(widths, lineStart, lineEnd, bodyOffset, synthetic)
            // StaticLayout keeps the trailing space of a broken line; it must not push
            // justification, so trailing whitespace is measured at zero width.
            while (clusters.isNotEmpty() && clusters.last().text.isBlank() && clusters.last().marker == null) {
                clusters.removeAt(clusters.lastIndex)
            }
            if (clusters.isEmpty()) continue

            val startX = if (lineIndex == 0) indent else 0f
            val isLastLine = lineIndex == lineStarts.lastIndex
            val justify = spec.justifyContent && !isTitle && !isLastLine
            val columns = placeClusters(
                clusters = clusters,
                startX = startX,
                justify = justify,
                syntax = syntax
            )

            val lineTop = state.durY
            val lineBottom = lineTop + metrics.textHeight
            state.addLine(
                TextLine(
                    text = text.substring(layoutText.sourceBoundary[lineStart], layoutText.sourceBoundary[lineEnd]),
                    columns = columns.first,
                    lineTop = lineTop,
                    lineBase = lineBottom - metrics.descent,
                    lineBottom = lineBottom,
                    startX = startX,
                    isTitle = isTitle,
                    isParagraphEnd = isLastLine,
                    chapterPosition = if (synthetic) bodyOffset else bodyOffset + layoutText.sourceBoundary[lineStart],
                    charLength = if (synthetic) 0 else layoutText.sourceBoundary[lineEnd] - layoutText.sourceBoundary[lineStart],
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
        clusters: List<LayoutCluster>,
        startX: Float,
        justify: Boolean,
        syntax: SyntaxStyleMap
    ): Pair<List<TextColumn>, Float> {
        val desired = clusters.sumOf { it.width.toDouble() }.toFloat()
        val residual = spec.visibleWidth - startX - desired
        var spaceExtra = 0f
        var gapExtra = 0f
        if (justify && residual > 0f && clusters.size > 1 && residual <= spec.visibleWidth * MAX_JUSTIFY_FRACTION) {
            val spaceCount = clusters.count { it.text == " " && it.marker == null }
            if (spaceCount > 0) {
                spaceExtra = residual / spaceCount
            } else {
                gapExtra = residual / (clusters.size - 1)
            }
        }

        val columns = ArrayList<TextColumn>(clusters.size)
        var x = startX
        for (index in clusters.indices) {
            val cluster = clusters[index]
            var width = cluster.width
            if (spaceExtra > 0f && cluster.text == " " && cluster.marker == null && index != clusters.lastIndex) {
                width += spaceExtra
            }
            if (gapExtra > 0f && index != clusters.lastIndex) {
                width += gapExtra
            }
            val style = cluster.sourceOffset.takeIf { it >= 0 }?.let(syntax::at)
            columns.add(
                TextColumn(
                    start = x,
                    end = x + cluster.width,
                    charData = cluster.text,
                    syntaxColorArgb = style?.colorArgb,
                    syntaxBackgroundArgb = style?.backgroundArgb,
                    syntaxUnderline = style?.underline ?: false,
                    syntaxFont = style?.font
                        ?: com.mozhi.reader.core.datastore.ReaderSyntaxFont.INHERIT,
                    syntaxFontAssetId = style?.fontAssetId,
                    syntaxBold = style?.bold ?: false,
                    syntaxItalic = style?.italic ?: false,
                    syntaxStrikethrough = style?.strikethrough ?: false,
                    sourceLength = cluster.sourceLength,
                    inlineMarkerKind = cluster.marker?.kind,
                    inlineMarkerOffset = cluster.marker?.charOffset
                )
            )
            x += width
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
                        start = column.start - shift,
                        end = column.end - shift,
                        charData = column.charData,
                        syntaxColorArgb = column.syntaxColorArgb,
                        syntaxBackgroundArgb = column.syntaxBackgroundArgb,
                        syntaxUnderline = column.syntaxUnderline,
                        syntaxFont = column.syntaxFont,
                        syntaxFontAssetId = column.syntaxFontAssetId,
                        syntaxBold = column.syntaxBold,
                        syntaxItalic = column.syntaxItalic,
                        syntaxStrikethrough = column.syntaxStrikethrough,
                        textSizeScale = column.textSizeScale,
                        fontFilePath = column.fontFilePath,
                        fontFamily = column.fontFamily,
                        baselineShiftPx = column.baselineShiftPx,
                        opacity = column.opacity,
                        sourceLength = column.sourceLength,
                        inlineMarkerKind = column.inlineMarkerKind,
                        inlineMarkerOffset = column.inlineMarkerOffset
                    )
                }
            }
        }
        return columns to gapExtra
    }

    private data class LayoutCluster(
        val text: String,
        val width: Float,
        val sourceOffset: Int,
        val sourceLength: Int,
        val marker: InlineMarkerReservation?
    )

    private data class LayoutText(
        val text: String,
        val sourceBoundary: IntArray,
        val markersByIndex: Map<Int, InlineMarkerReservation>
    ) {
        fun clusters(
            widths: FloatArray,
            from: Int,
            until: Int,
            bodyOffset: Int,
            synthetic: Boolean
        ): ArrayList<LayoutCluster> {
            val result = ArrayList<LayoutCluster>()
            var index = from
            while (index < until) {
                var end = index + 1
                while (end < until && widths[end] == 0f && text[end].code !in ZERO_WIDTH_CODES_LOCAL) end++
                val marker = markersByIndex[index]
                val sourceStart = sourceBoundary[index]
                val sourceEnd = sourceBoundary[end]
                result += LayoutCluster(
                    text = if (marker == null) text.substring(index, end) else "",
                    width = widths[index],
                    sourceOffset = if (synthetic || marker != null) -1 else bodyOffset + sourceStart,
                    sourceLength = sourceEnd - sourceStart,
                    marker = marker
                )
                index = end
            }
            return result
        }

        companion object {
            fun identity(text: String) = LayoutText(text, IntArray(text.length + 1) { it }, emptyMap())
        }
    }

    private fun buildLayoutText(
        text: String,
        bodyOffset: Int,
        markers: List<InlineMarkerReservation>
    ): LayoutText {
        val byLocalOffset = markers
            .filter { it.charOffset in (bodyOffset + 1)..(bodyOffset + text.length) }
            .distinctBy { it.charOffset to it.kind }
            .groupBy { it.charOffset - bodyOffset }
        if (byLocalOffset.isEmpty()) return LayoutText.identity(text)
        val builder = StringBuilder(text.length + byLocalOffset.size)
        val boundaries = ArrayList<Int>(text.length + byLocalOffset.size + 1).apply { add(0) }
        val markerMap = mutableMapOf<Int, InlineMarkerReservation>()
        text.forEachIndexed { index, char ->
            builder.append(char)
            boundaries += index + 1
            byLocalOffset[index + 1].orEmpty().sortedBy { it.kind.ordinal }.forEach { marker ->
                markerMap[builder.length] = marker
                builder.append(MARKER_PLACEHOLDER)
                boundaries += index + 1
            }
        }
        return LayoutText(builder.toString(), boundaries.toIntArray(), markerMap)
    }

    private inner class LayoutState {
        val pages = ArrayList<TextPage>()
        val pendingLines = ArrayList<TextLine>()
        var durY = 0f

        /** 已应用、还没有行「跟上」的间隙；页在这里切开时要记进 [TextPage.trailingGap]。 */
        private var openGap = 0f

        /** Spacing collapses at the top of a page: Legado resets `durY` on page break too. */
        fun addSpacing(spacing: Float, atPageTop: Boolean = false) {
            if (spacing <= 0f) return
            if (pendingLines.isNotEmpty() || atPageTop) {
                durY += spacing
                openGap += spacing
            }
        }

        fun prepareForLine(textHeight: Float) {
            if (pendingLines.isNotEmpty() && durY + textHeight > spec.visibleHeight + HEIGHT_EPSILON) {
                closePage(force = false)
            }
        }

        fun addLine(line: TextLine, lineStep: Float) {
            pendingLines.add(line)
            durY = line.lineTop + max(lineStep, line.lineBottom - line.lineTop)
            openGap = 0f
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
                    height = pendingLines.last().lineBottom,
                    trailingGap = openGap
                )
            )
            pendingLines.clear()
            durY = 0f
            openGap = 0f
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
        const val MARKER_PLACEHOLDER = '\u3000'
        val ZERO_WIDTH_CODES_LOCAL = intArrayOf(8203, 8204, 8205, 8288)
        const val MAX_IMAGE_HEIGHT_FRACTION = 0.72f
        const val MIN_IMAGE_ASPECT = 0.2f
        const val MAX_IMAGE_ASPECT = 5f
        /** A line whose residual exceeds this fraction is a stub (e.g. forced break) — leave it ragged. */
        const val MAX_JUSTIFY_FRACTION = 0.35f
    }

    private class SyntaxStyleMap(text: String, rules: List<ReaderSyntaxRule>) {
        private val styles = arrayOfNulls<ReaderSyntaxStyleSpan>(text.length)

        init {
            ReaderSyntaxHighlighter.spans(text, rules).forEach { span ->
                for (index in span.start until span.endExclusive.coerceAtMost(text.length)) {
                    styles[index] = span
                }
            }
        }

        fun at(index: Int): ReaderSyntaxStyleSpan? = styles.getOrNull(index)
    }
}
