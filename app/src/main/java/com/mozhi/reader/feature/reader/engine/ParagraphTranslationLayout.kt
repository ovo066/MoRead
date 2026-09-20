package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.dictionary.ParagraphTranslation

/** Zero-source-length columns keep selection, bookmarks, TTS and progress in original coordinates. */
internal fun translationLines(
    translation: ParagraphTranslation, left: Float, width: Float, spec: TypesetSpec, measure: TextMeasure
): List<TextLine> {
    val scale = 0.86f
    val style = MeasuredTextStyle(false, textSizeScale = scale)
    val metrics = measure.metrics(style)
    val step = maxOf(metrics.textHeight, spec.contentLineStep * scale)
    val result = mutableListOf<TextLine>()
    translation.chinese.lineSequence().filter { it.isNotBlank() }.forEach { paragraph ->
        val starts = measure.breakLines(paragraph, false, width / scale, 0f)
        val widths = measure.charWidths(paragraph, style)
        starts.forEachIndexed { index, start ->
            val end = starts.getOrElse(index + 1) { paragraph.length }
            val clusters = mutableListOf<String>()
            val advances = mutableListOf<Float>()
            clusterText(paragraph, widths, start, end, clusters, advances)
            var x = left
            val columns = clusters.mapIndexed { i, text ->
                TextColumn(x, x + advances[i], text, syntaxColorArgb = spec.glossColorArgb,
                    textSizeScale = scale, sourceLength = 0).also { x += advances[i] }
            }
            result += TextLine(paragraph.substring(start, end), columns, 0f, metrics.textHeight - metrics.descent,
                step, left, false, index == starts.lastIndex, translation.end, 0,
                paragraphTranslation = translation)
        }
    }
    return result
}

data class ReaderParagraphTranslation(val chapterIndex: Int, val translation: ParagraphTranslation)

/** Exact hit testing avoids selecting neighbouring English text when holding a translated row. */
fun TextPage.translationAt(x: Float, y: Float, chapterIndex: Int): ReaderParagraphTranslation? =
    lines.firstOrNull { line ->
        line.paragraphTranslation != null && y >= line.lineTop && y < line.lineBottom &&
            line.columns.any { x >= it.start && x < it.end }
    }?.paragraphTranslation?.let { ReaderParagraphTranslation(chapterIndex, it) }

internal fun TextLine.moveTranslationTo(top: Float) {
    val delta = top - lineTop
    lineTop += delta; lineBase += delta; lineBottom += delta
}
