package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.dictionary.EnglishWords
import kotlin.math.max

/** Reserve a second/third baseline before pagination. English source coordinates never change. */
internal fun TypesetSpec.wordGlossBand(text: String, isTitle: Boolean): Float {
    if (isTitle || wordGlosses.isEmpty()) return 0f
    return if (EnglishWords.pattern.findAll(text).any { EnglishWords.normalize(it.value) in wordGlosses }) contentFontSizePx * 1.2f else 0f
}

internal fun addWordGlosses(line: TextLine, spec: TypesetSpec, measure: TextMeasure) {
    if (line.isTitle || line.charLength == 0 || spec.wordGlosses.isEmpty()) return
    val visible = line.columns.joinToString("") { if (it.sourceLength > 0) it.charData else "" }
    val columnStarts = mutableListOf<Pair<Int, TextColumn>>()
    var offset = 0
    line.columns.forEach { column -> if (column.sourceLength > 0) { columnStarts += offset to column; offset += column.charData.length } }
    data class Candidate(val start: Float, val end: Float, val meaning: String, val phonetic: String)
    val candidates = EnglishWords.pattern.findAll(visible).mapNotNull { match ->
        val gloss = spec.wordGlosses[EnglishWords.normalize(match.value)] ?: return@mapNotNull null
        val first = columnStarts.lastOrNull { it.first <= match.range.first }?.second ?: return@mapNotNull null
        val last = columnStarts.lastOrNull { it.first <= match.range.last }?.second ?: return@mapNotNull null
        Candidate(first.start, last.end, gloss.meaning, gloss.phonetic)
    }.toList()
    if (candidates.isEmpty()) return
    val originalBottom = line.lineBottom
    val annotations = mutableListOf<TextRubyPlacement>()
    candidates.forEachIndexed { index, word ->
        val leftLimit = if (index == 0) 0f else (candidates[index - 1].end + word.start) / 2f + 2f
        val rightLimit = if (index == candidates.lastIndex) spec.visibleWidth else (word.end + candidates[index + 1].start) / 2f - 2f
        val center = (word.start + word.end) / 2f
        val width = (2 * minOf(center - leftLimit, rightLimit - center)).coerceAtLeast(word.end - word.start)
        fun append(text: String, baseline: Float) {
            if (text.isBlank()) return
            val scale = 0.46f
            val style = MeasuredTextStyle(false, textSizeScale = scale)
            var fitted = text.take(40)
            if (measure.charWidths(fitted, style).sum() > width) {
                while (fitted.isNotEmpty() && measure.charWidths("$fitted…", style).sum() > width) fitted = fitted.dropLast(1)
                fitted = "$fitted…"
            }
            annotations += TextRubyPlacement(fitted, center - width / 2, center + width / 2, baseline, scale, colorArgb = spec.glossColorArgb)
        }
        append(word.meaning, originalBottom + spec.contentFontSizePx * 0.48f)
        append(word.phonetic, originalBottom + spec.contentFontSizePx * 1.02f)
    }
    line.rubyPlacements = line.rubyPlacements + annotations
    line.lineBottom = max(line.lineBottom, originalBottom + spec.contentFontSizePx * 1.2f)
}
