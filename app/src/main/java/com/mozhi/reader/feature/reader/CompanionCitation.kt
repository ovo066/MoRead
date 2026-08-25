package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.QuoteChapter

/**
 * 一条可跳回正文的引用。
 *
 * @param chapterNumber 1 起的章节号；模型没给就为 null，定位时全书搜。
 * @param quote 逐字引文，用来在正文里找位置。
 */
data class CompanionCitation(
    val chapterNumber: Int?,
    val quote: String
)

/** 已确认能在正文中定位的引用；UI 只为这种引用展示跳转入口。 */
data class LocatedCompanionCitation(
    val citation: CompanionCitation,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int
)

/** 一条消息拆出的展示文本与引用列表。 */
data class ParsedCompanionMessage(
    /** 去掉标记后的正文；标记本身是给程序看的，不该出现在读者眼前。 */
    val displayText: String,
    val citations: List<CompanionCitation>
)

/**
 * 从伴读回复里解析「引用了哪段原文」。
 *
 * 只识别提示词约定的显式标记 `〔原文 第N章〕「逐字引文」`。普通引号可能只是强调、
 * 对话或书名，不能据此推断为原文引用。
 *
 * 纯函数，不碰书籍数据；能不能真的定位到由 [com.mozhi.reader.core.library.BookQuoteLocator] 决定。
 */
object CompanionCitationParser {

    /** 与定位器保持一致：太短的引文满篇都是，标出来只会误导。 */
    const val MIN_QUOTE_CHARS = 6

    /** 一条消息最多标 6 条，再多整屏都是胶囊。 */
    const val MAX_CITATIONS = 6

    private val MARKED = Regex(
        """〔\s*原文\s*(?:第\s*(\d{1,4})\s*章)?\s*〕\s*「([^」]{2,400})」"""
    )

    fun parse(raw: String): ParsedCompanionMessage {
        if (raw.isBlank()) return ParsedCompanionMessage(raw, emptyList())

        val citations = mutableListOf<CompanionCitation>()
        // 标记整体替换为「引文」：正文读起来仍是通顺的引用句，只是不再带程序标记。
        val display = MARKED.replace(raw) { match ->
            val chapter = match.groupValues[1].toIntOrNull()
            val quote = match.groupValues[2].trim()
            if (quote.length >= MIN_QUOTE_CHARS) {
                citations += CompanionCitation(chapter, quote)
            }
            "「$quote」"
        }

        return ParsedCompanionMessage(
            displayText = display,
            citations = citations
                .distinctBy { it.quote }
                .take(MAX_CITATIONS)
        )
    }

    /** 胶囊上的短标签：章节号（若有）+ 截断的引文。 */
    fun label(citation: CompanionCitation): String = buildString {
        citation.chapterNumber?.let { append("第").append(it).append("章 · ") }
        append(citation.quote.take(LABEL_QUOTE_CHARS))
        if (citation.quote.length > LABEL_QUOTE_CHARS) append('…')
    }

    private const val LABEL_QUOTE_CHARS = 14
}

/** 把格式正确的候选引用再次与真实正文核对，未命中的候选不会进入 UI。 */
object CompanionCitationVerifier {
    fun locate(
        citations: List<CompanionCitation>,
        chapters: List<QuoteChapter>
    ): List<LocatedCompanionCitation> = citations.mapNotNull { citation ->
        val preferredChapterIndex = citation.chapterNumber?.minus(1)
        val location = BookQuoteLocator.locateAll(chapters, citation.quote.trim()).let { matches ->
            if (preferredChapterIndex == null) {
                matches.firstOrNull()
            } else {
                matches.minByOrNull { kotlin.math.abs(it.chapterIndex - preferredChapterIndex) }
            }
        }
        location?.let {
            LocatedCompanionCitation(
                citation = citation.copy(chapterNumber = it.chapterIndex + 1),
                chapterIndex = it.chapterIndex,
                startCharOffset = it.startCharOffset,
                endCharOffset = it.endCharOffset
            )
        }
    }
}
