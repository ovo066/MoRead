package com.mozhi.reader.feature.reader.engine

import java.text.BreakIterator
import java.util.Locale

/**
 * Text selection over one laid-out page, in content-local coordinates. Pure Kotlin so hit-testing
 * and selection geometry are unit-testable; the pane translates to view coordinates when drawing.
 *
 * Legado's model: a long press hits one cluster, expands to the word around it via BreakIterator,
 * then dragging moves the focus end (both directions supported).
 */
data class TextPos(val lineIndex: Int, val columnIndex: Int) : Comparable<TextPos> {
    override fun compareTo(other: TextPos): Int =
        compareValuesBy(this, other, TextPos::lineIndex, TextPos::columnIndex)
}

data class SelectionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/** The nearest selectable cluster to (x, y), or null when the page has no text there. */
fun TextPage.hitTextPos(x: Float, y: Float, exact: Boolean = false): TextPos? {
    val selectable = lines.withIndex().filter { it.value.columns.isNotEmpty() }
    if (selectable.isEmpty()) return null
    val line = selectable.firstOrNull { y >= it.value.lineTop && y <= it.value.lineBottom }
        ?: if (exact) return null else selectable.minByOrNull {
            val center = (it.value.lineTop + it.value.lineBottom) / 2f
            kotlin.math.abs(center - y)
        }
    line ?: return null
    val columns = line.value.columns
    if (exact && (x < columns.first().start || x > columns.last().end)) return null
    val columnIndex = columns.indices.firstOrNull { index ->
        x < columns[index].end || index == columns.lastIndex
    } ?: columns.lastIndex
    return TextPos(line.index, columnIndex)
}

/**
 * Expands the hit cluster to the word around it. BreakIterator segments CJK into dictionary words
 * on Android's ICU; on plain JVM it degrades to single characters, which is still a valid
 * selection seed.
 */
fun TextPage.wordSelectionAt(pos: TextPos, locale: Locale = Locale.CHINESE): Pair<TextPos, TextPos> {
    val line = lines.getOrNull(pos.lineIndex) ?: return pos to pos
    if (line.columns.isEmpty()) return pos to pos
    // Column index -> char index inside the line text.
    val charStarts = IntArray(line.columns.size)
    var acc = 0
    for (i in line.columns.indices) {
        charStarts[i] = acc
        acc += line.columns[i].charData.length
    }
    val charIndex = charStarts[pos.columnIndex.coerceIn(line.columns.indices)]
    val text = line.columns.joinToString("") { it.charData }
    val iterator = BreakIterator.getWordInstance(locale)
    iterator.setText(text)
    var end = if (iterator.isBoundary(charIndex)) {
        iterator.following(charIndex)
    } else {
        iterator.following(charIndex).takeIf { it != BreakIterator.DONE } ?: text.length
    }
    if (end == BreakIterator.DONE) end = text.length
    var start = iterator.preceding(end.coerceAtMost(text.length))
    if (start == BreakIterator.DONE) start = 0
    val startColumn = charStarts.indexOfLast { it <= start }.coerceAtLeast(0)
    val endColumn = charStarts.indexOfLast { it <= end - 1 }.coerceAtLeast(startColumn)
    return TextPos(pos.lineIndex, startColumn) to TextPos(pos.lineIndex, endColumn)
}

/** One highlight rect per line covered by the (inclusive) selection range. */
fun TextPage.selectionRects(start: TextPos, end: TextPos): List<SelectionRect> {
    val (from, to) = if (start <= end) start to end else end to start
    val rects = ArrayList<SelectionRect>()
    for (lineIndex in from.lineIndex..to.lineIndex) {
        val line = lines.getOrNull(lineIndex) ?: continue
        if (line.columns.isEmpty()) continue
        val firstColumn = if (lineIndex == from.lineIndex) {
            from.columnIndex.coerceIn(line.columns.indices)
        } else {
            0
        }
        val lastColumn = if (lineIndex == to.lineIndex) {
            to.columnIndex.coerceIn(line.columns.indices)
        } else {
            line.columns.lastIndex
        }
        if (firstColumn > lastColumn) continue
        rects.add(
            SelectionRect(
                left = line.columns[firstColumn].start,
                top = line.lineTop,
                right = line.columns[lastColumn].end,
                bottom = line.lineBottom
            )
        )
    }
    return rects
}

/** Body offset of the given position's first character. */
fun TextPage.charOffsetOf(pos: TextPos): Int {
    val line = lines.getOrNull(pos.lineIndex) ?: return chapterPosition
    if (line.columns.isEmpty()) return line.chapterPosition
    var offset = line.chapterPosition
    for (index in 0 until pos.columnIndex.coerceIn(0, line.columns.size)) {
        offset += line.columns[index].charData.length
    }
    return offset
}

/** Body offsets [startInclusive, endExclusive) covered by the ordered selection. */
fun TextPage.selectionBodyRange(start: TextPos, end: TextPos): IntRange {
    val (from, to) = if (start <= end) start to end else end to start
    val startOffset = charOffsetOf(from)
    val endLine = lines.getOrNull(to.lineIndex)
    val endColumnLength = endLine?.columns?.getOrNull(to.columnIndex)?.charData?.length ?: 0
    return startOffset until (charOffsetOf(to) + endColumnLength)
}

/** The selected text, paragraph breaks preserved. */
fun TextPage.selectedText(start: TextPos, end: TextPos): String {
    val (from, to) = if (start <= end) start to end else end to start
    val builder = StringBuilder()
    for (lineIndex in from.lineIndex..to.lineIndex) {
        val line = lines.getOrNull(lineIndex) ?: continue
        if (line.columns.isEmpty()) continue
        val first = if (lineIndex == from.lineIndex) from.columnIndex.coerceIn(line.columns.indices) else 0
        val last = if (lineIndex == to.lineIndex) to.columnIndex.coerceIn(line.columns.indices) else line.columns.lastIndex
        for (column in first..last) builder.append(line.columns[column].charData)
        if (line.isParagraphEnd && lineIndex < to.lineIndex) builder.append('\n')
    }
    return builder.toString()
}

/** Selection state after dragging one handle; handles swap when dragged past the fixed end. */
data class HandleDrag(val start: TextPos, val end: TextPos, val draggingStart: Boolean)

/**
 * Moves one edge of an ordered selection to [hit]. Dragging the start past the end (or vice versa)
 * swaps the edges and hands the drag over to the other handle, like the platform text selection.
 */
fun dragSelectionHandle(
    start: TextPos,
    end: TextPos,
    hit: TextPos,
    draggingStart: Boolean
): HandleDrag = if (draggingStart) {
    if (hit <= end) HandleDrag(hit, end, true) else HandleDrag(end, hit, false)
} else {
    if (hit >= start) HandleDrag(start, hit, false) else HandleDrag(hit, start, true)
}
