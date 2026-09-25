package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxHighlighter
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderSyntaxStyleSpan
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.library.EpubComputedStyle
import com.mozhi.reader.core.library.EpubFloat
import com.mozhi.reader.core.library.EpubLayoutBlock
import com.mozhi.reader.core.library.EpubLayoutBlockKind
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.EpubTextAlign
import com.mozhi.reader.core.library.EpubVerticalAlign
import com.mozhi.reader.feature.reader.engine.epub.EpubTypesetterV2
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
    val titleFontSizePx: Float = titleLineStep,
    /** A reader-selected custom paper is the canvas; publisher root backgrounds must not cover it. */
    val preferReaderBackground: Boolean = false,
    val publisherStyleMode: PublisherStyleMode = PublisherStyleMode.SMART,
    val themeBackgroundArgb: Int = 0xFFFFFFFF.toInt(),
    val themeTextArgb: Int = 0xFF202020.toInt(),
    val darkTheme: Boolean = false,
    /** 特殊页拿回普通页为页眉、页脚预留的纵向空间。 */
    val immersiveExtraTopPx: Float = 0f,
    val immersiveExtraBottomPx: Float = 0f,
    val wordGlosses: Map<String, com.mozhi.reader.core.dictionary.WordGloss> = emptyMap(),
    val glossColorArgb: Int = 0xff847561.toInt(),
    val paragraphTranslations: List<com.mozhi.reader.core.dictionary.ParagraphTranslation> = emptyList(),
    val readerTitleAlignment: com.mozhi.reader.core.datastore.ReaderTitleAlignment = com.mozhi.reader.core.datastore.ReaderTitleAlignment.START,
    val readerTitleInset: Float = 0f,
    val readerTitlePadding: Float = 0f,
    val publisherTitleFontSizePx: Float = titleFontSizePx,
    val publisherTitleLineStep: Float = titleLineStep,
    val publisherTitleTopSpacing: Float = titleTopSpacing,
    val publisherTitleBottomSpacing: Float = titleBottomSpacing
)

/** 「段首缩进」滑杆的出厂值，也是把原书缩进折算成用户比例时的基准。 */
const val DEFAULT_FIRST_LINE_INDENT_CHARS = 2f

/**
 * 段首缩进归属。凡是 CSS 写过 `text-indent` 的书（含 `body { text-indent: 0 }`——该属性会继承，
 * 一条声明就让每个段落都算「已声明」），原先一律由原书说了算，用户的滑杆就是死的。
 *
 * - 原书优先：完全按声明排。
 * - 智能（默认）：与行距、段距同一套语义，把原书缩进按用户比例缩放，既跟着滑杆走，
 *   又保留原书「引文比正文缩得多」这类相对差别；原书声明为 0 时无从缩放，直接用用户值。
 * - 接管排版：首行缩进整个交给用户。
 *
 * 悬挂缩进（负值）是结构而非装饰：诗歌、列表、对话体靠它对齐，任何模式都原样保留。
 */
fun resolveFirstLineIndent(
    publisherIndentPx: Float?,
    userIndentChars: Float,
    indentColumnWidthPx: Float,
    publisherStyleMode: PublisherStyleMode,
    isHeading: Boolean = false
): Float {
    // 标题没有「段首缩进」这回事：用户滑杆只管正文，标题只可能保留原书自己的缩进。
    val userIndentPx = if (isHeading) 0f else userIndentChars * indentColumnWidthPx
    return when {
        publisherIndentPx == null -> userIndentPx
        publisherIndentPx < 0f -> publisherIndentPx
        publisherStyleMode == PublisherStyleMode.RESPECT -> publisherIndentPx
        publisherStyleMode == PublisherStyleMode.TAKE_OVER || publisherIndentPx == 0f -> userIndentPx
        else -> publisherIndentPx * (userIndentChars / DEFAULT_FIRST_LINE_INDENT_CHARS)
    }
}

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
        epubLayout: EpubLayoutChapterBundle? = null,
        cancellationCheck: () -> Unit = {},
        translations: List<com.mozhi.reader.core.dictionary.ParagraphTranslation> = emptyList()
    ): TextChapter {
        if (translations.isNotEmpty()) return ChapterTypesetter(spec.copy(paragraphTranslations = translations.filter { !it.hidden && it.matches(body) }), measure)
            .typeset(chapterIndex, title, body, inlineImages, inlineMarkers, epubLayout, cancellationCheck)
        cancellationCheck()
        if (epubLayout != null && epubLayout.document.textLength == body.length) {
            val publisherMeasure = (measure as? AndroidTextMeasure)?.forPublisherHeadings() ?: measure
            val publisherSpec = spec.copy(titleFontSizePx = spec.publisherTitleFontSizePx,
                titleLineStep = spec.publisherTitleLineStep,
                titleTopSpacing = spec.publisherTitleTopSpacing, titleBottomSpacing = spec.publisherTitleBottomSpacing)
            val hasDomPath = epubLayout.dom != null &&
                (epubLayout.stylesheets.isNotEmpty() || epubLayout.dom.embeddedStylesheets.isNotEmpty() ||
                    epubLayout.dom.bodyNode.hasInlineStyles())
            val hasLegacyBlocks = epubLayout.document.blocks.any { it.kind != EpubLayoutBlockKind.CONTAINER }
            if (hasDomPath) {
                return EpubTypesetterV2(publisherSpec, publisherMeasure, cancellationCheck).typeset(
                    chapterIndex, title, body, inlineImages, inlineMarkers, epubLayout
                )
            }
            if (hasLegacyBlocks) {
                return EpubBoxLayoutBackend(publisherSpec, publisherMeasure, cancellationCheck).typeset(
                    chapterIndex, title, body, inlineImages, inlineMarkers, epubLayout
                )
            }
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
            cancellationCheck()
            val newline = body.indexOf('\n', cursor)
            val end = if (newline >= 0) newline else body.length
            val paragraph = body.substring(cursor, end)
            val isLastParagraph = newline < 0

            if (firstParagraph) {
                firstParagraph = false
                state.addSpacing(spec.titleTopSpacing, atPageTop = true)
                if (trimmedTitle.isNotEmpty() && paragraph.trim() == trimmedTitle) {
                    layoutParagraph(
                        state, paragraph, cursor, isTitle = true, synthetic = false,
                        syntax = syntax, inlineMarkers = inlineMarkers, cancellationCheck = cancellationCheck
                    )
                    layoutTranslation(state, cursor, end, cancellationCheck)
                    pendingGap = spec.titleBottomSpacing
                    cursor = end + 1
                    if (isLastParagraph) break
                    continue
                }
                if (trimmedTitle.isNotEmpty()) {
                    layoutParagraph(
                        state, trimmedTitle, cursor, isTitle = true, synthetic = true,
                        syntax = syntax, inlineMarkers = emptyList(), cancellationCheck = cancellationCheck
                    )
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
                    layoutParagraph(
                        state, paragraph, cursor, isTitle = false, synthetic = false,
                        syntax = syntax, inlineMarkers = inlineMarkers, cancellationCheck = cancellationCheck
                    )
                    layoutTranslation(state, cursor, end, cancellationCheck)
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

    private fun layoutTranslation(state: LayoutState, start: Int, end: Int, cancellationCheck: () -> Unit) {
        spec.paragraphTranslations.firstOrNull { it.start == start && it.end == end }?.let { translation ->
            state.addSpacing(spec.paragraphSpacing * 0.4f)
            translationLines(translation, 0f, spec.visibleWidth, spec, measure).forEach { line ->
                cancellationCheck()
                state.prepareForLine(line.lineBottom)
                line.moveTranslationTo(state.durY)
                state.addLine(line, line.lineBottom - line.lineTop)
            }
        }
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
        inlineMarkers: List<InlineMarkerReservation>,
        cancellationCheck: () -> Unit
    ) {
        cancellationCheck()
        val metrics = if (isTitle) titleMetrics else contentMetrics
        val lineStep = if (isTitle) spec.titleLineStep else spec.contentLineStep
        val indent = if (isTitle) 0f else indentWidth
        val layoutText = if (synthetic) LayoutText.identity(text) else buildLayoutText(text, bodyOffset, inlineMarkers)
        val widths = measure.charWidths(layoutText.text, isTitle)
        // 高亮规则换了字体/字重的片段按渲染时的字体重测，并让断行看到同样的宽度。
        val styledRuns = if (synthetic) emptyList() else syntaxMeasureRuns(layoutText, bodyOffset, isTitle, syntax)
        styledRuns.forEach { run ->
            measure.charWidths(layoutText.text.substring(run.start, run.end), run.style)
                .copyInto(widths, destinationOffset = run.start)
        }
        val titleInset = if (isTitle) (spec.readerTitleInset + spec.readerTitlePadding).coerceAtMost(spec.visibleWidth * 0.4f) else 0f
        val availableWidth = spec.visibleWidth - titleInset * 2f
        val lineStarts = measure.breakLines(layoutText.text, isTitle, availableWidth, indent, styledRuns)
        if (isTitle) state.addSpacing(spec.readerTitlePadding, atPageTop = true)

        for (lineIndex in lineStarts.indices) {
            cancellationCheck()
            val lineStart = lineStarts[lineIndex]
            val lineEnd = if (lineIndex + 1 < lineStarts.size) lineStarts[lineIndex + 1] else layoutText.text.length
            if (lineStart >= lineEnd) continue
            val glossBand = spec.wordGlossBand(layoutText.text.substring(lineStart, lineEnd), isTitle)
            state.prepareForLine(metrics.textHeight + glossBand)

            val clusters = layoutText.clusters(widths, lineStart, lineEnd, bodyOffset, synthetic)
            // StaticLayout keeps the trailing space of a broken line; it must not push
            // justification, so trailing whitespace is measured at zero width.
            while (clusters.isNotEmpty() && clusters.last().text.isBlank() && clusters.last().marker == null) {
                clusters.removeAt(clusters.lastIndex)
            }
            if (clusters.isEmpty()) continue

            val startX = if (isTitle) titleInset + (availableWidth - clusters.sumOf { it.width.toDouble() }.toFloat()).coerceAtLeast(0f) * when (spec.readerTitleAlignment) {
                com.mozhi.reader.core.datastore.ReaderTitleAlignment.START -> 0f
                com.mozhi.reader.core.datastore.ReaderTitleAlignment.CENTER -> 0.5f
                com.mozhi.reader.core.datastore.ReaderTitleAlignment.END -> 1f
            } else if (lineIndex == 0) indent else 0f
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
                    isReaderTitle = isTitle,
                    isParagraphEnd = isLastLine,
                    chapterPosition = if (synthetic) bodyOffset else bodyOffset + layoutText.sourceBoundary[lineStart],
                    charLength = if (synthetic) 0 else layoutText.sourceBoundary[lineEnd] - layoutText.sourceBoundary[lineStart],
                    justifyGapExtra = columns.second
                ).also { addWordGlosses(it, spec, measure) },
                lineStep = lineStep
            )
        }
        if (isTitle) state.addSpacing(spec.readerTitlePadding, atPageTop = false)
    }

    /**
     * Ranges of [layoutText] whose syntax rule changes glyph advances (font, bold, italic).
     * Colour-only rules measure like the body text and produce no run.
     */
    private fun syntaxMeasureRuns(
        layoutText: LayoutText,
        bodyOffset: Int,
        isTitle: Boolean,
        syntax: SyntaxStyleMap
    ): List<StyledTextRun> {
        val runs = ArrayList<StyledTextRun>()
        var runStart = -1
        var runStyle: MeasuredTextStyle? = null
        for (index in 0..layoutText.text.length) {
            val style = if (index < layoutText.text.length && index !in layoutText.markersByIndex) {
                syntax.at(bodyOffset + layoutText.sourceBoundary[index])
                    ?.takeIf { it.font != ReaderSyntaxFont.INHERIT || it.bold || it.italic }
                    ?.let { span ->
                        MeasuredTextStyle(
                            isTitle = isTitle,
                            bold = span.bold,
                            italic = span.italic,
                            syntaxFont = span.font,
                            syntaxFontAssetId = span.fontAssetId
                        )
                    }
            } else {
                null
            }
            if (style == runStyle) continue
            runStyle?.let { runs += StyledTextRun(runStart, index, it) }
            runStart = index
            runStyle = style
        }
        return runs
    }

    /**
     * Legado's `addCharsToLineMiddle`: full justification distributes the residual width over
     * space clusters when the line has any (Latin/mixed text), otherwise over every inter-cluster
     * eligible gap (pure CJK). Small platform measurement overruns are compressed inside the margin.
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
        val expandable = BooleanArray(clusters.size) { index ->
            index < clusters.lastIndex && clusters[index + 1].marker == null &&
                canExpandTextGap(clusters[index].text, clusters[index + 1].text)
        }
        if (justify && residual > 0f && clusters.size > 1 && residual <= spec.visibleWidth * MAX_JUSTIFY_FRACTION) {
            val spaceCount = clusters.count { it.text == " " && it.marker == null }
            if (spaceCount > 0) {
                spaceExtra = residual / spaceCount
            } else {
                val gaps = expandable.count { it }
                if (gaps > 0) gapExtra = residual / gaps
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
            if (gapExtra > 0f && expandable[index]) {
                width += gapExtra
            }
            val style = cluster.sourceOffset.takeIf { it >= 0 }?.let(syntax::at)
            columns.add(
                TextColumn(
                    start = x,
                    end = x + cluster.width,
                    charData = cluster.text,
                    syntaxPaintSpan = style?.paintSpan,
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
                        syntaxPaintSpan = column.syntaxPaintSpan,
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
            if (pendingLines.isEmpty()) durY = durY.coerceAtMost((spec.visibleHeight - textHeight).coerceAtLeast(0f))
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
                lines[index].rubyPlacements = lines[index].rubyPlacements.map { it.copy(baseline = it.baseline + shift) }
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
