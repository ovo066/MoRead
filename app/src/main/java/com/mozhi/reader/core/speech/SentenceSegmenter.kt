package com.mozhi.reader.core.speech

/**
 * 听书句子区间：章节正文里的 UTF-16 字符坐标 [start, end)，与批注/书签同轨。
 * 送引擎前要过 [SentenceSegmenter.speakableText] 清洗（去内联图占位符与首尾空白）。
 */
data class SentenceSpan(val start: Int, val end: Int) {
    val length: Int get() = end - start
    operator fun contains(offset: Int): Boolean = offset in start until end
}

/**
 * 连续听书的句子切分：按行切段，段内按终止标点断句（含收尾引号归前句），
 * 超长句在次级停顿（逗号/顿号/冒号/空格）处折分，实在没有停顿再硬切。
 */
object SentenceSegmenter {

    private const val OBJECT_REPLACEMENT = '￼'
    private const val TERMINATORS = "。！？!?…；;"
    private const val CLOSERS = "」』”’\"'）)】]〕〉》"
    private const val SOFT_BREAKS = "，,、：: 　"

    /** 默认句长上限：兼顾 AI TTS 单次计费文本量与系统引擎的顺畅衔接。 */
    const val DEFAULT_MAX_CHARS = 96

    fun segment(body: String, maxChars: Int = DEFAULT_MAX_CHARS): List<SentenceSpan> {
        if (body.isEmpty()) return emptyList()
        val spans = ArrayList<SentenceSpan>()
        var lineStart = 0
        while (lineStart <= body.length) {
            val newline = body.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) body.length else newline
            splitLine(body, lineStart, lineEnd, maxChars, spans)
            if (newline < 0) break
            lineStart = newline + 1
        }
        return spans
    }

    /** 段落粒度：优先保留整段，超长时才退回句边界切分。 */
    fun segmentParagraphs(body: String, maxChars: Int = 400): List<SentenceSpan> {
        require(maxChars > 0) { "maxChars 必须大于 0" }
        if (body.isEmpty()) return emptyList()
        val result = mutableListOf<SentenceSpan>()
        var lineStart = 0
        while (lineStart <= body.length) {
            val newline = body.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) body.length else newline
            var start = lineStart
            var end = lineEnd
            while (start < end && body[start].isIgnorable()) start++
            while (end > start && body[end - 1].isIgnorable()) end--
            if (start < end) {
                if (end - start <= maxChars) {
                    result += SentenceSpan(start, end)
                } else {
                    segment(body.substring(start, end), maxChars).forEach { span ->
                        result += SentenceSpan(start + span.start, start + span.end)
                    }
                }
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        return result
    }

    fun segmentChapter(body: String, maxChars: Int = 2_000): List<SentenceSpan> {
        if (body.isBlank()) return emptyList()
        val paragraphs = segmentParagraphs(body, maxChars)
        if (paragraphs.isEmpty()) return emptyList()
        val result = mutableListOf<SentenceSpan>()
        var start = paragraphs.first().start
        var end = paragraphs.first().end
        paragraphs.drop(1).forEach { paragraph ->
            if (paragraph.end - start <= maxChars) {
                end = paragraph.end
            } else {
                result += SentenceSpan(start, end)
                start = paragraph.start
                end = paragraph.end
            }
        }
        result += SentenceSpan(start, end)
        return result
    }

    /** [spans] 中第一个 end 落在 [offset] 之后的句子；越过全部句子返回 size。 */
    fun indexAt(spans: List<SentenceSpan>, offset: Int): Int {
        val index = spans.indexOfFirst { it.end > offset }
        return if (index < 0) spans.size else index
    }

    /** 送进 TTS 引擎的文本：内联图占位符换成空格，再去首尾空白。 */
    fun speakableText(body: String, start: Int, end: Int): String {
        if (start >= end || start < 0 || end > body.length) return ""
        return body.substring(start, end).replace(OBJECT_REPLACEMENT, ' ').trim()
    }

    private fun splitLine(
        body: String,
        from: Int,
        to: Int,
        maxChars: Int,
        out: MutableList<SentenceSpan>
    ) {
        var start = from
        var i = from
        while (i < to) {
            if (body[i] in TERMINATORS) {
                var end = i + 1
                while (end < to && (body[end] in TERMINATORS || body[end] in CLOSERS)) end++
                addClamped(body, start, end, maxChars, out)
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < to) addClamped(body, start, to, maxChars, out)
    }

    private fun addClamped(
        body: String,
        rawStart: Int,
        rawEnd: Int,
        maxChars: Int,
        out: MutableList<SentenceSpan>
    ) {
        var start = rawStart
        var end = rawEnd
        while (start < end && body[start].isIgnorable()) start++
        while (end > start && body[end - 1].isIgnorable()) end--
        if (start >= end) return
        var cursor = start
        while (end - cursor > maxChars) {
            val windowEnd = cursor + maxChars
            var cut = -1
            // 从窗口尾部向前找次级停顿；切点不早于窗口 1/3 处，避免碎句。
            var k = windowEnd
            val floor = cursor + maxChars / 3
            while (k > floor) {
                if (body[k - 1] in SOFT_BREAKS) {
                    cut = k
                    break
                }
                k--
            }
            if (cut < 0) cut = windowEnd
            emitTrimmed(body, cursor, cut, out)
            cursor = cut
        }
        emitTrimmed(body, cursor, end, out)
    }

    private fun emitTrimmed(body: String, rawStart: Int, rawEnd: Int, out: MutableList<SentenceSpan>) {
        var start = rawStart
        var end = rawEnd
        while (start < end && body[start].isIgnorable()) start++
        while (end > start && body[end - 1].isIgnorable()) end--
        if (start < end) out += SentenceSpan(start, end)
    }

    private fun Char.isIgnorable(): Boolean = isWhitespace() || this == OBJECT_REPLACEMENT
}
