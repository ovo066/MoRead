package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.WebSource
import com.mozhi.reader.ai.agent.WebSourceParser
import com.mozhi.reader.core.database.entity.MessageEntity

/** 一条脚注指向的东西：书中原文，或一个网页。 */
sealed interface FootnoteTarget {
    val key: String

    data class Book(
        override val key: String,
        val quote: String,
        val chapterNumber: Int?,
        val bookId: Long? = null
    ) : FootnoteTarget

    data class Web(override val key: String, val url: String) : FootnoteTarget
}

data class Footnote(val number: Int, val target: FootnoteTarget)

/**
 * 回复里的引用标记 → 脚注。
 *
 * 引文后面接一个上标编号（渲染成 `moread-cite:n` 链接，由气泡拦截），网页标记只留编号。
 * 编号按整条消息里首次出现的先后分配：多气泡拆开显示时同一处引用编号一致，
 * 流式生成时新引用只会排在后面，已出现的编号不跳动。
 */
object CompanionFootnotes {
    const val SCHEME = "moread-cite:"
    const val MAX_FOOTNOTES = 12
    /** 与书内引用定位器一致：太短的引文满篇都是，不编号。 */
    const val MIN_QUOTE_CHARS = 6

    enum class Dialect { READER, LIBRARY }

    private val readerMark = Regex("""〔\s*原文\s*(?:第\s*(\d{1,4})\s*章)?\s*〕\s*「([^」]{2,400})」""")
    private val libraryMark = Regex("""〔\s*书籍#(\d{1,19})\s+第\s*(\d{1,7})\s*章\s*〕\s*「([^」]{2,600})」""")
    private val webMark = Regex("""〔\s*来源\s*[:：]?\s*(https?://[^\s〕]+)\s*〕""")

    private class Mark(val range: IntRange, val target: FootnoteTarget?, val quote: String?)

    private fun marks(raw: String, dialect: Dialect): List<Mark> {
        val book = when (dialect) {
            Dialect.READER -> readerMark.findAll(raw).map { match ->
                val quote = match.groupValues[2].trim()
                val target = quote.takeIf { it.length >= MIN_QUOTE_CHARS }?.let {
                    FootnoteTarget.Book("book:$it", it, match.groupValues[1].toIntOrNull())
                }
                Mark(match.range, target, quote)
            }
            Dialect.LIBRARY -> libraryMark.findAll(raw).map { match ->
                val quote = match.groupValues[3].trim()
                val bookId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
                val chapter = match.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
                val target = if (bookId != null && chapter != null && quote.length >= MIN_QUOTE_CHARS) {
                    FootnoteTarget.Book("book:$bookId:$quote", quote, chapter, bookId)
                } else null
                Mark(match.range, target, quote)
            }
        }
        val web = webMark.findAll(raw).map { match ->
            val url = WebSourceParser.normalize(match.groupValues[1])
            Mark(match.range, FootnoteTarget.Web("web:$url", url), null)
        }
        // 两类标记按位置合流；重叠的（理论上不会出现）以先出现的为准。
        val merged = (book + web).sortedBy { it.range.first }
        val kept = mutableListOf<Mark>()
        merged.forEach { mark -> if (kept.lastOrNull()?.range?.let { mark.range.first <= it.last } != true) kept += mark }
        return kept
    }

    fun footnotes(raw: String, dialect: Dialect): List<Footnote> =
        marks(raw, dialect).mapNotNull { it.target }
            .distinctBy { it.key }
            .take(MAX_FOOTNOTES)
            .mapIndexed { index, target -> Footnote(index + 1, target) }

    /** 把标记换成可读文字 + 上标编号链接；不在脚注表里的引文只保留引号。 */
    fun render(raw: String, footnotes: List<Footnote>, dialect: Dialect): String {
        val numbers = footnotes.associate { it.target.key to it.number }
        val list = marks(raw, dialect)
        if (list.isEmpty()) return raw
        return buildString {
            var last = 0
            list.forEach { mark ->
                append(raw, last, mark.range.first)
                val number = mark.target?.let { numbers[it.key] }
                mark.quote?.let { append('「').append(it).append('」') }
                if (number != null) append("[").append(superscript(number)).append("](").append(SCHEME).append(number).append(")")
                last = mark.range.last + 1
            }
            append(raw, last, raw.length)
        }
    }

    fun numberOf(uri: String): Int? = uri.takeIf { it.startsWith(SCHEME) }?.removePrefix(SCHEME)?.toIntOrNull()

    fun superscript(number: Int): String = number.toString().map { SUPERSCRIPTS[it - '0'] }.joinToString("")

    private const val SUPERSCRIPTS = "⁰¹²³⁴⁵⁶⁷⁸⁹"
}

/** 会话里所有网页工具结果，按网址索引，用来给网页脚注补标题与摘要。 */
fun companionWebSources(messages: List<MessageEntity>): Map<String, WebSource> =
    WebSourceParser.index(
        messages.filter { it.role == "tool" && (it.content.startsWith("互联网搜索结果") || it.content.startsWith("网页正文")) }
            .map { it.content }
    )
