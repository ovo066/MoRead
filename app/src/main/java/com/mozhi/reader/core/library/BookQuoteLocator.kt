package com.mozhi.reader.core.library

/** 一段原文在书中的位置；坐标是章内 UTF-16 字符偏移，与阅读位置、批注同轨。 */
data class QuoteLocation(
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

/** 定位所需的一章正文。 */
data class QuoteChapter(
    val chapterIndex: Int,
    val body: String
)

/**
 * 在书中逐字定位一段引文。
 *
 * 两个使用者共用它：add_annotation 工具（要求唯一命中，否则拒绝落批注）与聊天页的
 * 「跳到原文」（允许多处命中，取最靠近提示章节的一处）。逻辑只此一份，
 * 两边对「什么算命中」的判断不会走偏。
 */
object BookQuoteLocator {

    /** 短引文满屏都是，定位没有意义；低于这个长度直接不找。 */
    const val MIN_QUOTE_CHARS = 6

    /** 逐字全匹配，返回全部命中位置（按章节、偏移升序）。 */
    fun locateAll(chapters: List<QuoteChapter>, quote: String): List<QuoteLocation> {
        if (quote.isEmpty()) return emptyList()
        return buildList {
            chapters.forEach { chapter ->
                var from = 0
                while (from <= chapter.body.length - quote.length) {
                    val index = chapter.body.indexOf(quote, from)
                    if (index < 0) break
                    add(QuoteLocation(chapter.chapterIndex, index, index + quote.length))
                    from = index + quote.length.coerceAtLeast(1)
                }
            }
        }
    }

    /**
     * 给「跳到原文」用的宽松定位：
     * 1. 先逐字全匹配，多处命中时取离 [preferredChapterIndex] 最近的一处；
     * 2. 全匹配失败再抹掉空白与常见标点重试一次——模型复述时爱改标点，
     *    为一个全角逗号让用户点不动实在说不过去。
     */
    fun locateBest(
        chapters: List<QuoteChapter>,
        quote: String,
        preferredChapterIndex: Int? = null
    ): QuoteLocation? {
        val trimmed = quote.trim()
        if (trimmed.length < MIN_QUOTE_CHARS) return null

        locateAll(chapters, trimmed).pickClosest(preferredChapterIndex)?.let { return it }
        return locateNormalized(chapters, trimmed, preferredChapterIndex)
    }

    private fun List<QuoteLocation>.pickClosest(preferred: Int?): QuoteLocation? = when {
        isEmpty() -> null
        preferred == null -> first()
        else -> minByOrNull { kotlin.math.abs(it.chapterIndex - preferred) }
    }

    /**
     * 归一化匹配：把正文与引文都压成「只留有意义字符」的形式再找，命中后用下标映射
     * 回原文区间。映射表是必需的——压缩后的下标直接拿去当原文偏移会错位。
     */
    private fun locateNormalized(
        chapters: List<QuoteChapter>,
        quote: String,
        preferred: Int?
    ): QuoteLocation? {
        val needle = quote.filterNot(Char::isIgnorableForMatching)
        if (needle.length < MIN_QUOTE_CHARS) return null
        val hits = buildList {
            chapters.forEach { chapter ->
                val compact = StringBuilder(chapter.body.length)
                val offsets = IntArray(chapter.body.length)
                chapter.body.forEachIndexed { index, character ->
                    if (!character.isIgnorableForMatching()) {
                        offsets[compact.length] = index
                        compact.append(character)
                    }
                }
                var from = 0
                val haystack = compact.toString()
                while (from <= haystack.length - needle.length) {
                    val index = haystack.indexOf(needle, from)
                    if (index < 0) break
                    val startOriginal = offsets[index]
                    val endOriginal = offsets[index + needle.length - 1] + 1
                    add(QuoteLocation(chapter.chapterIndex, startOriginal, endOriginal))
                    from = index + needle.length
                }
            }
        }
        return hits.pickClosest(preferred)
    }
}

/**
 * 空白与标点在复述中最容易变形（模型常把全角逗号写成空格、丢掉句末句号），
 * 归一化匹配时一律忽略。这里放宽是安全的：跳转是个只读动作，
 * 宁可多跳成功一次，也不要为一个标点让用户点不动。
 */
private fun Char.isIgnorableForMatching(): Boolean =
    isWhitespace() || this in IGNORED_PUNCTUATION

private val IGNORED_PUNCTUATION = charArrayOf(
    '「', '」', '『', '』', '“', '”', '‘', '’', '"', '\'',
    '，', '。', '、', '；', '：', '！', '？', '—', '…', '·', '～',
    '（', '）', '《', '》', '〈', '〉',
    ',', '.', ';', ':', '!', '?', '(', ')', '-', '~'
)
