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
            body = rewritten.body,
            epubLayout = layout?.rebase(rewritten.positions, rewritten.body.length, mode),
            inlineImages = images.map { image ->
                image.copy(charOffset = rewritten.positions.getValue(image.charOffset))
            }
        )
    }

    fun resolveSourcePoint(
        body: String,
        layout: EpubLayoutChapterBundle?,
        images: List<InlineImageSource>,
        displayedAnchor: ReaderTextAnchor
    ): Int? {
        if (displayedAnchor.mode == ChineseConversionMode.OFF) {
            return ReaderTextAnchors.resolve(
                body,
                displayedAnchor,
                ChineseConversionMode.OFF,
                converter
            )?.start
        }
        val boundaries = presentationBoundaries(body, layout, images)
        val rewritten = rewrite(body, boundaries, displayedAnchor.mode)
        val displayedPoint = ReaderTextAnchors.resolve(
            rewritten.body,
            displayedAnchor,
            displayedAnchor.mode,
            converter
        )?.start ?: return null
        boundaries.firstOrNull { rewritten.positions.getValue(it) == displayedPoint }
            ?.let { return it }
        val (sourceStart, sourceEnd) = boundaries.zipWithNext().firstOrNull { (start, end) ->
            displayedPoint > rewritten.positions.getValue(start) &&
                displayedPoint < rewritten.positions.getValue(end)
        } ?: return null
        val displayStart = rewritten.positions.getValue(sourceStart)
        val displayEnd = rewritten.positions.getValue(sourceEnd)
        val localPoint = displayedPoint - displayStart
        val displayedLeaf = rewritten.body.substring(displayStart, displayEnd)
        val localAnchor = ReaderTextAnchors.create(
            displayedLeaf,
            localPoint,
            localPoint,
            displayedAnchor.mode
        )
        return ReaderTextAnchors.resolveSourcePoint(
            sourceBody = body.substring(sourceStart, sourceEnd),
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
        val start = sourceStart.coerceIn(0, body.length)
        val end = sourceEnd.coerceIn(start, body.length)
        if (mode == ChineseConversionMode.OFF) return ResolvedTextAnchor(start, end)
        val boundaries = presentationBoundaries(body, layout, images)
        val rewritten = rewrite(body, boundaries, mode)
        val displayStart = resolveDisplayedBoundary(body, boundaries, rewritten, start, mode)
            ?: return null
        val displayEnd = resolveDisplayedBoundary(body, boundaries, rewritten, end, mode)
            ?: return null
        return ResolvedTextAnchor(displayStart, displayEnd)
    }

    private fun resolveDisplayedBoundary(
        body: String,
        boundaries: List<Int>,
        rewritten: RewrittenText,
        sourcePoint: Int,
        mode: ChineseConversionMode
    ): Int? {
        rewritten.positions[sourcePoint]?.let { return it }
        val (sourceStart, sourceEnd) = boundaries.zipWithNext().firstOrNull { (start, end) ->
            sourcePoint > start && sourcePoint < end
        } ?: return null
        val displayStart = rewritten.positions.getValue(sourceStart)
        val displayEnd = rewritten.positions.getValue(sourceEnd)
        val sourceLeaf = body.substring(sourceStart, sourceEnd)
        val localPoint = sourcePoint - sourceStart
        val localAnchor = ReaderTextAnchors.create(
            sourceLeaf,
            localPoint,
            localPoint,
            ChineseConversionMode.OFF
        )
        return ReaderTextAnchors.resolve(
            rewritten.body.substring(displayStart, displayEnd),
            localAnchor,
            mode,
            converter
        )?.start?.plus(displayStart)
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

    private data class RewrittenText(
        val body: String,
        val positions: Map<Int, Int>
    )

    private fun rewrite(
        source: String,
        boundaries: List<Int>,
        mode: ChineseConversionMode
    ): RewrittenText {
        val output = StringBuilder(source.length)
        val positions = HashMap<Int, Int>(boundaries.size)
        positions[0] = 0
        boundaries.zipWithNext().forEach { (start, end) ->
            positions[start] = output.length
            output.append(converter.convert(source.substring(start, end), mode))
            positions[end] = output.length
        }
        return RewrittenText(output.toString(), positions)
    }

    private fun MutableSet<Int>.addValid(value: Int, length: Int) {
        if (value in 0..length) add(value)
    }

    private fun EpubDomNode.collectBoundaries(target: MutableSet<Int>, length: Int) {
        target.addValid(textStart, length)
        target.addValid(textEnd, length)
        children.forEach { it.collectBoundaries(target, length) }
    }

    private fun EpubDomNode.rebase(positions: Map<Int, Int>): EpubDomNode = copy(
        textStart = textStart.takeIf { it >= 0 }?.let(positions::getValue) ?: -1,
        textEnd = textEnd.takeIf { it >= 0 }?.let(positions::getValue) ?: -1,
        children = children.map { it.rebase(positions) }
    )

    private fun EpubLayoutChapterBundle.rebase(
        positions: Map<Int, Int>,
        newLength: Int,
        mode: ChineseConversionMode
    ): EpubLayoutChapterBundle = copy(
        document = document.copy(
            documentTitle = document.documentTitle?.let { converter.convert(it, mode) },
            blocks = document.blocks.map { block ->
                block.copy(
                    textStart = positions.getValue(block.textStart),
                    textEnd = positions.getValue(block.textEnd),
                    spans = block.spans.map { span ->
                        span.copy(
                            textStart = positions.getValue(span.textStart),
                            textEnd = positions.getValue(span.textEnd),
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
                bodyNode = source.bodyNode.rebase(positions),
                textLength = newLength
            )
        }
    )
}
