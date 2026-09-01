package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.epub.dom.EpubDomNode
import com.mozhi.reader.core.library.EpubLayoutChapterBundle
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
        val rewritten = rewrite(body, boundaries.toList(), mode)
        return ReaderChapterContent(
            body = rewritten.body,
            epubLayout = layout?.rebase(rewritten.positions, rewritten.body.length, mode),
            inlineImages = images.map { image ->
                image.copy(charOffset = rewritten.positions.getValue(image.charOffset))
            }
        )
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
