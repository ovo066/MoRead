package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.library.ReaderTextAnchor
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.core.text.ChineseTextConverter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChineseChapterPresenter @Inject constructor(
    private val converter: ChineseTextConverter
) {
    fun present(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>,
        mode: ChineseConversionMode
    ): ReaderChapterContent {
        if (mode == ChineseConversionMode.OFF) {
            return ReaderChapterContent(body, layout, images)
        }
        val boundaries = presentationBoundaries(body, layout, images)
        val rewritten = rewrite(body, boundaries, mode)
        return ReaderChapterContent(
            body = rewritten.displayedBody,
            epubLayout = layout?.rebase(rewritten.positions, body.length, rewritten.displayedBody.length, mode),
            inlineImages = images.map { image ->
                image.copy(charOffset = rewritten.positions.mappedOffset(image.charOffset, body.length))
            },
            source = rewritten
        )
    }

    fun resolveSourcePoint(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>,
        displayedAnchor: ReaderTextAnchor
    ): Int? {
        val source = if (displayedAnchor.mode == ChineseConversionMode.OFF) {
            ReaderChapterSource(body)
        } else {
            rewrite(body, presentationBoundaries(body, layout, images), displayedAnchor.mode)
        }
        return resolveSourcePoint(source, displayedAnchor)
    }

    fun resolveSourcePoint(source: ReaderChapterSource, displayedAnchor: ReaderTextAnchor): Int? {
        if (source.mode == ChineseConversionMode.OFF) {
            return ReaderTextAnchors.resolve(
                source.body,
                displayedAnchor,
                ChineseConversionMode.OFF,
                converter
            )?.start
        }
        val displayedPoint = ReaderTextAnchors.resolve(
            source.displayedBody,
            displayedAnchor,
            source.mode,
            converter
        )?.start ?: return null
        source.boundaries.firstOrNull { source.positions.getValue(it) == displayedPoint }
            ?.let { return it }
        val (sourceStart, sourceEnd) = source.boundaries.zipWithNext().firstOrNull { (start, end) ->
            displayedPoint > source.positions.getValue(start) &&
                displayedPoint < source.positions.getValue(end)
        } ?: return null
        val displayStart = source.positions.getValue(sourceStart)
        val displayEnd = source.positions.getValue(sourceEnd)
        val localPoint = displayedPoint - displayStart
        val displayedLeaf = source.displayedBody.substring(displayStart, displayEnd)
        val localAnchor = ReaderTextAnchors.create(
            displayedLeaf,
            localPoint,
            localPoint,
            source.mode
        )
        return ReaderTextAnchors.resolveSourcePoint(
            sourceBody = source.body.substring(sourceStart, sourceEnd),
            displayedBody = displayedLeaf,
            displayedAnchor = localAnchor,
            converter = converter
        )?.start?.plus(sourceStart)
    }

    fun resolveDisplayedPoint(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>,
        sourceOffset: Int,
        mode: ChineseConversionMode
    ): Int? = resolveDisplayedRange(
        body,
        layout,
        images,
        sourceOffset,
        sourceOffset,
        mode
    )?.start

    fun resolveDisplayedRange(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>,
        sourceStart: Int,
        sourceEnd: Int,
        mode: ChineseConversionMode
    ): ResolvedTextAnchor? {
        val source = if (mode == ChineseConversionMode.OFF) {
            ReaderChapterSource(body)
        } else {
            rewrite(body, presentationBoundaries(body, layout, images), mode)
        }
        return resolveDisplayedRange(source, sourceStart, sourceEnd)
    }

    /** Uses the immutable map produced at load time; rendering marks never rewrites the chapter. */
    fun resolveDisplayedRange(
        source: ReaderChapterSource,
        sourceStart: Int,
        sourceEnd: Int
    ): ResolvedTextAnchor? {
        val start = sourceStart.coerceIn(0, source.body.length)
        val end = sourceEnd.coerceIn(start, source.body.length)
        if (source.mode == ChineseConversionMode.OFF) return ResolvedTextAnchor(start, end)
        val displayStart = resolveDisplayedBoundary(source, start)
            ?: return null
        val displayEnd = resolveDisplayedBoundary(source, end)
            ?: return null
        return ResolvedTextAnchor(displayStart, displayEnd)
    }

    private fun resolveDisplayedBoundary(
        source: ReaderChapterSource,
        sourcePoint: Int
    ): Int? {
        source.positions[sourcePoint]?.let { return it }
        val (sourceStart, sourceEnd) = source.boundaries.zipWithNext().firstOrNull { (start, end) ->
            sourcePoint > start && sourcePoint < end
        } ?: return null
        val displayStart = source.positions.getValue(sourceStart)
        val displayEnd = source.positions.getValue(sourceEnd)
        val sourceLeaf = source.body.substring(sourceStart, sourceEnd)
        val localPoint = sourcePoint - sourceStart
        val localAnchor = ReaderTextAnchors.create(
            sourceLeaf,
            localPoint,
            localPoint,
            ChineseConversionMode.OFF
        )
        val displayedLeaf = source.displayedBody.substring(displayStart, displayEnd)
        val localDisplayPoint = ReaderTextAnchors.resolveTextMatch(
            displayedLeaf,
            localAnchor,
            source.mode,
            converter
        )?.start ?: ReaderTextAnchors.convertedBoundary(
            sourceLeaf,
            displayedLeaf,
            localPoint,
            ChineseConversionMode.OFF,
            source.mode,
            converter
        )
        return localDisplayPoint + displayStart
    }

    private fun presentationBoundaries(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>
    ): List<Int> {
        val boundaries = sortedSetOf(0, body.length)
        layout?.document?.blocks.orEmpty().forEach { block ->
            boundaries.addValid(block.textStart, body.length)
            boundaries.addValid(block.textEnd, body.length)
            block.spans.forEach { span ->
                boundaries.addValid(span.textStart, body.length)
                boundaries.addValid(span.textEnd, body.length)
            }
        }
        layout?.dom?.bodyNode?.collectBoundaries(boundaries, body.length)
        images.forEach { image ->
            boundaries.addValid(image.charOffset, body.length)
            boundaries.addValid(image.charOffset + 1, body.length)
        }
        return boundaries.toList()
    }

    private fun rewrite(
        source: String,
        boundaries: List<Int>,
        mode: ChineseConversionMode
    ): ReaderChapterSource {
        val output = StringBuilder(source.length)
        val positions = HashMap<Int, Int>(boundaries.size)
        positions[0] = 0
        boundaries.zipWithNext().forEach { (start, end) ->
            positions[start] = output.length
            output.append(converter.convert(source.substring(start, end), mode))
            positions[end] = output.length
        }
        return ReaderChapterSource(source, output.toString(), mode, boundaries, positions)
    }

    private fun MutableSet<Int>.addValid(value: Int, length: Int) {
        if (value in 0..length) add(value)
    }

    private fun EpubDomNode.collectBoundaries(target: MutableSet<Int>, length: Int) {
        target.addValid(textStart, length)
        target.addValid(textEnd, length)
        children.forEach { it.collectBoundaries(target, length) }
    }

    private fun Map<Int, Int>.mappedOffset(offset: Int, sourceLength: Int): Int =
        getValue(offset.coerceIn(0, sourceLength))

    private fun EpubDomNode.rebase(positions: Map<Int, Int>, sourceLength: Int): EpubDomNode = copy(
        textStart = textStart.takeIf { it >= 0 }?.let { positions.mappedOffset(it, sourceLength) } ?: -1,
        textEnd = textEnd.takeIf { it >= 0 }?.let { positions.mappedOffset(it, sourceLength) } ?: -1,
        children = children.map { it.rebase(positions, sourceLength) }
    )

    private fun EpubLayoutChapterBundle.rebase(
        positions: Map<Int, Int>,
        sourceLength: Int,
        newLength: Int,
        mode: ChineseConversionMode
    ): EpubLayoutChapterBundle = copy(
        document = document.copy(
            documentTitle = document.documentTitle?.let { converter.convert(it, mode) },
            blocks = document.blocks.map { block ->
                block.copy(
                    textStart = positions.mappedOffset(block.textStart, sourceLength),
                    textEnd = positions.mappedOffset(block.textEnd, sourceLength),
                    spans = block.spans.map { span ->
                        span.copy(
                            textStart = positions.mappedOffset(span.textStart, sourceLength),
                            textEnd = positions.mappedOffset(span.textEnd, sourceLength),
                            rubyText = span.rubyText?.let { converter.convert(it, mode) }
                        )
                    }
                )
            },
            textLength = newLength
        ),
        dom = dom?.let { source ->
            source.copy(
                documentTitle = source.documentTitle?.let { converter.convert(it, mode) },
                bodyNode = source.bodyNode.rebase(positions, sourceLength),
                textLength = newLength
            )
        }
    )
}
