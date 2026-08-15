package com.mozhi.reader.feature.reader

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

/** 一条消息拆出的展示文本与引用列表。 */
data class ParsedCompanionMessage(
    /** 去掉标记后的正文；标记本身是给程序看的，不该出现在读者眼前。 */
    val displayText: String,
    val citations: List<CompanionCitation>
)

/**
 * 从伴读回复里解析「引用了哪段原文」。
 *
 * 主路径是提示词里约定的显式标记 `〔原文 第N章〕「逐字引文」`——工具返回的原文本来就带
 * 章节号，模型只需照抄。兜底再扫裸引号里的长句：模型忘记加标记是常态，
 * 为此让「跳到原文」时有时无，用户只会觉得这个功能坏了。
 *
 * 纯函数，不碰书籍数据；能不能真的定位到由 [com.mozhi.reader.core.library.BookQuoteLocator] 决定。
 */
object CompanionCitationParser {

    /** 与定位器保持一致：太短的引文满篇都是，标出来只会误导。 */
    const val MIN_QUOTE_CHARS = 6

    /** 一条消息最多标 6 条，再多整屏都是胶囊。 */
    const val MAX_CITATIONS = 6

    private val MARKED = Regex(
        """〔\s*原文\s*(?:第\s*(\d{1,4})\s*章)?\s*〕\s*[「“"]([^」“”"]{2,400})[」”"]"""
    )

    /** 裸引号兜底：「…」『…』“…”，只认足够长的。 */
    private val BARE_QUOTE = Regex("""[「『“]([^」』“”]{6,400})[」』”]""")

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

        if (citations.isEmpty()) {
            BARE_QUOTE.findAll(display).forEach { match ->
                val quote = match.groupValues[1].trim()
                if (quote.length >= MIN_QUOTE_CHARS) {
                    citations += CompanionCitation(null, quote)
                }
            }
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
