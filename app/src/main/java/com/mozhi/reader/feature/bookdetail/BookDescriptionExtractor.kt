package com.mozhi.reader.feature.bookdetail

internal object BookDescriptionExtractor {
    private val preferredTitles = listOf("内容简介", "作品简介", "书籍简介", "简介", "导读")
    private val fallbackTitles = listOf("序言", "前言", "楔子", "引言")
    private val inlineHeading = Regex("^(?:内容简介|作品简介|书籍简介|简介)\\s*[:：]?\\s*(.*)$")
    private val noise = Regex("^(?:书名|作者|版权|目录|封面|制作|校对|整理)\\s*[:：]")
    private val nextSection = Regex("^(?:第[一二三四五六七八九十百千万0-9]+[章节回卷部]|序章|正文|目录)$")

    fun extract(chapters: List<Pair<String, String>>, maxLength: Int = 2_000): String {
        if (chapters.isEmpty()) return ""
        val preferred = chapters.firstOrNull { (title, _) ->
            preferredTitles.any(title.trim().replace(" ", "")::contains)
        }
        if (preferred != null) return cleanBody(preferred.second, maxLength)

        chapters.forEach { (_, body) ->
            extractInline(body, maxLength)?.let { return it }
        }

        val fallback = chapters.firstOrNull { (title, _) ->
            fallbackTitles.any(title.trim().replace(" ", "")::contains)
        }
        return cleanBody(fallback?.second ?: chapters.first().second, maxLength)
    }

    private fun extractInline(body: String, maxLength: Int): String? {
        val lines = body.lineSequence().map(String::trim).toList()
        lines.forEachIndexed { index, line ->
            val match = inlineHeading.matchEntire(line) ?: return@forEachIndexed
            val collected = buildList {
                match.groupValues[1].takeIf(String::isNotBlank)?.let(::add)
                for (next in lines.drop(index + 1)) {
                    if (nextSection.matches(next.replace(" ", "")) && isNotEmpty()) break
                    if (next.isNotBlank() && !noise.containsMatchIn(next)) add(next)
                }
            }
            normalize(collected.joinToString("\n\n"), maxLength)
                .takeIf(String::isNotBlank)
                ?.let { return it }
        }
        return null
    }

    private fun cleanBody(body: String, maxLength: Int): String {
        val paragraphs = body
            .replace("\r\n", "\n")
            .split(Regex("\\n\\s*\\n|\\n"))
            .map(String::trim)
            .filter { it.isNotBlank() && !noise.containsMatchIn(it) }
        return normalize(paragraphs.joinToString("\n\n"), maxLength)
    }

    private fun normalize(text: String, maxLength: Int): String {
        val clean = text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        if (clean.length <= maxLength) return clean
        return clean.take(maxLength).trimEnd('，', '。', '；', '、', ' ') + "…"
    }
}