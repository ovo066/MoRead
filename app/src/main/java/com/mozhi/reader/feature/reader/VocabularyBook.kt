package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.dictionary.VocabularyWord
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** 生词本的三个视图：概览卡上的三格同时就是筛选器。 */
internal enum class VocabularyFilter { ALL, LEARNING, LEARNED }

internal data class VocabularyStats(val total: Int, val learning: Int, val learned: Int) {
    val masteredFraction: Float get() = if (total == 0) 0f else learned.toFloat() / total
    fun count(filter: VocabularyFilter) = when (filter) {
        VocabularyFilter.ALL -> total
        VocabularyFilter.LEARNING -> learning
        VocabularyFilter.LEARNED -> learned
    }
}

/** 收藏时间分段：近处按天，一周以外按月，列表读起来像一本按日期翻的笔记。 */
internal sealed interface VocabularyPeriod {
    data object Today : VocabularyPeriod
    data object Yesterday : VocabularyPeriod
    data object PastWeek : VocabularyPeriod
    data class Month(val year: Int, val month: Int) : VocabularyPeriod
}

internal data class VocabularyGroup(val period: VocabularyPeriod, val words: List<VocabularyWord>)

internal fun vocabularyStats(words: List<VocabularyWord>): VocabularyStats {
    val learned = words.count { it.learned }
    return VocabularyStats(words.size, words.size - learned, learned)
}

internal fun VocabularyFilter.accepts(word: VocabularyWord) = when (this) {
    VocabularyFilter.ALL -> true
    VocabularyFilter.LEARNING -> !word.learned
    VocabularyFilter.LEARNED -> word.learned
}

/**
 * 筛选 → 按收藏时间倒序 → 分段。分段保持首次出现的顺序，因此永远是由近到远。
 * 时间戳晚于 [now]（换过时区或改过系统时间）一律算作今天，不会凭空多出一个「未来」分组。
 */
internal fun vocabularyGroups(
    words: List<VocabularyWord>,
    query: String,
    filter: VocabularyFilter,
    now: Long,
    zone: ZoneId
): List<VocabularyGroup> {
    val needle = query.trim()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return words.asSequence()
        .filter(filter::accepts)
        .filter {
            needle.isEmpty() || it.word.contains(needle, true) || it.gloss.contains(needle, true) ||
                it.definition.contains(needle, true)
        }
        .sortedByDescending { it.createdAt }
        .groupBy { word ->
            val date = Instant.ofEpochMilli(word.createdAt).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(date, today)
            when {
                days <= 0 -> VocabularyPeriod.Today
                days == 1L -> VocabularyPeriod.Yesterday
                days < 7 -> VocabularyPeriod.PastWeek
                else -> VocabularyPeriod.Month(date.year, date.monthValue)
            }
        }
        .map { (period, grouped) -> VocabularyGroup(period, grouped) }
}

/**
 * 卡片上的释义摘要：去掉 Markdown 记号、开头重复的词头，以及与词下短释义完全相同的内容。
 * 完整释义仍在点开后的词典面板里，这里只求一眼能读。
 */
internal fun vocabularyPreview(word: String, definition: String, gloss: String, maxChars: Int = 200): String {
    val lines = definition.lineSequence()
        .map { it.trim().replace(MarkdownLinePrefix, "").replace(MarkdownInline, "").trim() }
        .filter { it.isNotEmpty() }
        .toList()
    val body = if (lines.firstOrNull()?.equals(word.trim(), ignoreCase = true) == true) lines.drop(1) else lines
    val plain = body.joinToString(" ").replace(Whitespace, " ").trim()
    return if (plain == gloss.trim()) "" else plain.take(maxChars)
}

/** 语境句里第一次出现该词的位置（忽略大小写），用来把它加粗。 */
internal fun vocabularyContextHighlight(context: String, word: String): IntRange? {
    val needle = word.trim()
    if (needle.isEmpty()) return null
    val start = context.indexOf(needle, ignoreCase = true)
    return if (start < 0) null else start until start + needle.length
}

private val MarkdownLinePrefix = Regex("""^(#{1,6}\s+|>\s*|[-*+]\s+|\d+[.)]\s+)""")
private val MarkdownInline = Regex("""\*\*|__|`|~~""")
private val Whitespace = Regex("""\s+""")
