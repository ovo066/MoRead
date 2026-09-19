package com.mozhi.reader.feature.reader.engine

/**
 * Resolve CJK prohibition rules inside the measured line, without hanging ink into the margin.
 * Move the preceding text together with closing punctuation to the next line, then let normal
 * justification distribute the remaining space. Offsets and glyph widths are never rewritten.
 */
internal fun fitPunctuationBreak(
    start: Int,
    proposed: Int,
    size: Int,
    textAt: (Int) -> String,
    canBreakAt: (Int) -> Boolean = { true }
): Int {
    if (proposed >= size) return proposed
    for (end in proposed downTo start + 1) {
        val left = textAt(end - 1).lastOrNull()
        val right = textAt(end).firstOrNull()
        if (left !in FORBIDDEN_LINE_END && right !in FORBIDDEN_LINE_START && canBreakAt(end)) return end
    }
    // A very narrow box or a run made only of punctuation can have no legal boundary. Keep the
    // measured hard break so layout still advances and never pulls extra glyphs past the edge.
    return proposed.coerceAtLeast(start + 1)
}

/** Justification must not separate an opening mark, a closing mark, or a Latin word. */
internal fun canExpandTextGap(left: String, right: String): Boolean {
    val before = left.lastOrNull()
    val after = right.firstOrNull()
    if (before in FORBIDDEN_LINE_END || after in FORBIDDEN_LINE_START) return false
    val insideWord = before != null && after != null && before.code < 0x2E80 && after.code < 0x2E80 &&
        before.isLetterOrDigit() && after.isLetterOrDigit()
    return !insideWord
}

private val FORBIDDEN_LINE_START = setOf(
    '，', '。', '、', '；', '：', '！', '？', '）', '》', '】', '〉', '〕',
    '」', '』', '”', '’', '…', '—', ',', '.', ';', ':', '!', '?', ')', ']', '}'
)
private val FORBIDDEN_LINE_END = setOf(
    '（', '《', '【', '〈', '〔', '「', '『', '“', '‘', '(', '[', '{'
)
