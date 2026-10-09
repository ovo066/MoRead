package com.mozhi.reader.ai.agent

/** 网页来源：从 web_search / web_scrape 的工具结果里还原，供回复里的〔来源 URL〕脚注显示标题与摘要。 */
data class WebSource(val url: String, val title: String, val snippet: String = "")

object WebSourceParser {
    private val searchItem = Regex("""(?m)^\[(\d{1,2})] (.+)\n(https?://\S+)(?:\n(?!\[\d{1,2}] )([^\n]+))?""")
    private val scrapeTitle = Regex("""(?m)^标题：(.*)$""")
    private val scrapeUrl = Regex("""(?m)^来源：(https?://\S+)$""")

    /** 工具结果正文 → 网页来源；格式不认识时返回空表，不猜测。 */
    fun parse(toolResult: String): List<WebSource> {
        val text = toolResult.replace("\r\n", "\n")
        val sources = searchItem.findAll(text).map { match ->
            WebSource(
                url = match.groupValues[3].trim(),
                title = match.groupValues[2].trim(),
                snippet = match.groupValues[4].trim()
            )
        }.toMutableList()
        val url = scrapeUrl.find(text)?.groupValues?.get(1)?.trim()
        if (url != null && sources.none { it.url == url }) {
            val body = text.substringAfter("来源：$url", "").trim()
            sources += WebSource(url, scrapeTitle.find(text)?.groupValues?.get(1)?.trim().orEmpty(), body.take(SNIPPET_CHARS))
        }
        return sources
    }

    /** 同一网址以最先出现、信息更全的一条为准。 */
    fun index(toolResults: List<String>): Map<String, WebSource> {
        val byUrl = LinkedHashMap<String, WebSource>()
        toolResults.flatMap(::parse).forEach { source ->
            val key = normalize(source.url)
            val previous = byUrl[key]
            if (previous == null || (previous.title.isBlank() && source.title.isNotBlank())) byUrl[key] = source
        }
        return byUrl
    }

    fun normalize(url: String): String = url.trim().trimEnd('/', '.', '。', '，', ',', '）', ')')

    private const val SNIPPET_CHARS = 160
}
