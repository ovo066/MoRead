package com.mozhi.reader.feature.importer

data class TxtMetadata(
    val title: String,
    val author: String
)

/** Lightweight metadata inference for TXT files whose names or opening lines carry author data. */
object TxtMetadataDetector {
    private val bracketedTitle = Regex("^《([^》]+)》\\s*(.+)?$")
    private val bracketedAuthor = Regex("^[\\[【（(]([^\\]】）)]+)[\\]】）)]\\s*(.+)$")
    private val bySuffix = Regex("^(.+?)\\s+[Bb][Yy]\\s+(.+)$")
    private val authorLine = Regex("^\\s*(?:作者|著者|作者名|Author)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val titleLine = Regex("^\\s*(?:书名|标题|Title)\\s*[:：]\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE)
    private val nonAuthorLabels = setOf(
        "完结", "全本", "精校", "校对", "修订", "出版", "网络版", "实体版", "全集",
        "简体", "繁体", "txt", "epub", "new"
    )

    fun detect(displayName: String, text: String): TxtMetadata {
        val baseName = displayName.substringBeforeLast('.').trim().ifBlank { "未命名书籍" }
        val fromFile = detectFromFileName(baseName)
        val headerLines = text.lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .take(24)
            .toList()
        val headerTitle = headerLines.firstNotNullOfOrNull { line ->
            titleLine.matchEntire(line)?.groupValues?.getOrNull(1)?.cleanValue()
        }
        val headerAuthor = headerLines.firstNotNullOfOrNull { line ->
            authorLine.matchEntire(line)?.groupValues?.getOrNull(1)?.cleanAuthor()
        }
        return TxtMetadata(
            title = headerTitle.orEmpty().ifBlank { fromFile.title },
            author = headerAuthor.orEmpty().ifBlank { fromFile.author }
        )
    }

    internal fun detectFromFileName(baseName: String): TxtMetadata {
        val trimmed = baseName.trim()
        bracketedTitle.matchEntire(trimmed)?.let { match ->
            val title = match.groupValues[1].cleanValue()
            val author = match.groupValues.getOrNull(2).orEmpty().cleanAuthor()
            return TxtMetadata(title.ifBlank { trimmed }, author)
        }
        bySuffix.matchEntire(trimmed)?.let { match ->
            val title = match.groupValues[1].cleanValue()
            val author = match.groupValues[2].cleanAuthor()
            if (title.isNotBlank() && author.isNotBlank()) return TxtMetadata(title, author)
        }
        bracketedAuthor.matchEntire(trimmed)?.let { match ->
            val author = match.groupValues[1].cleanAuthor()
            val title = match.groupValues[2].cleanValue()
            if (author.isNotBlank() && title.isNotBlank()) return TxtMetadata(title, author)
        }
        return TxtMetadata(trimmed.cleanValue().ifBlank { "未命名书籍" }, "")
    }

    private fun String.cleanValue(): String = trim()
        .trim('-', '_', '—', '–', '·', ' ')
        .replace(Regex("\\s{2,}"), " ")
        .take(200)

    private fun String.cleanAuthor(): String {
        val value = cleanValue()
            .removePrefix("作者：")
            .removePrefix("作者:")
            .removeSuffix("著")
            .trim()
            .take(120)
        if (value.isBlank() || value.lowercase() in nonAuthorLabels) return ""
        return value
    }
}
