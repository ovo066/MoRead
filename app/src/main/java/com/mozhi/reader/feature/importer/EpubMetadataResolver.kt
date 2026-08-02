package com.mozhi.reader.feature.importer

/**
 * 从 EPUB 元数据里挑出可用的书名与作者。
 *
 * 现实里的 EPUB 常带垃圾元数据：WPS / Calibre 导出的文件会写
 * `<dc:title>Unknown</dc:title>` 和 `<dc:creator>WPS_1532705572</dc:creator>`。
 * 解析器忠实读出这些值，所以清洗必须在这一层做——纯函数，便于单测。
 */
object EpubMetadataResolver {
    /** 与书名/作者都无关的占位词，比对前先 trim + 小写。 */
    private val PLACEHOLDER_VALUES = setOf(
        "unknown",
        "untitled",
        "no title",
        "notitle",
        "none",
        "null",
        "n/a",
        "undefined",
        "未知",
        "未知书名",
        "未知标题",
        "无标题",
        "无题",
        "未命名"
    )

    /** 工具自动生成的作者名：WPS 的用户 id、Calibre 的自我署名。 */
    private val GENERATED_AUTHOR_PATTERNS = listOf(
        Regex("""^wps[_\-\s]?\d+$""", RegexOption.IGNORE_CASE),
        Regex("""^calibre\b.*""", RegexOption.IGNORE_CASE),
        Regex("""^user[_\-\s]?\d+$""", RegexOption.IGNORE_CASE),
        Regex("""^admin$""", RegexOption.IGNORE_CASE),
        // 纯 UUID / 纯数字串不可能是人名
        Regex("""^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$""", RegexOption.IGNORE_CASE),
        Regex("""^urn:.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d{6,}$""")
    )

    /** 文件名里的分享噪声前缀/后缀，例如 `(NEW)千钧雪`、`【完结】xxx`、`[精校]xxx`。 */
    private val FILENAME_NOISE = listOf(
        Regex("""^[(（\[【{]\s*[^)）\]】}]{0,12}\s*[)）\]】}]\s*"""),
        Regex("""\s*[(（\[【{]\s*[^)）\]】}]{0,12}\s*[)）\]】}]\s*$""")
    )

    data class Resolved(val title: String, val author: String)

    /**
     * @param rawTitle `publication.metadata.title`
     * @param rawAuthor 第一作者名
     * @param identifier `dc:identifier`，用来识别「书名/作者被写成了 id」的情况
     * @param navTitle 导航文档或 NCX 的 docTitle，作为次选
     * @param displayName 用户选中的文件名（含扩展名），最后的兜底
     */
    fun resolve(
        rawTitle: String?,
        rawAuthor: String?,
        identifier: String? = null,
        navTitle: String? = null,
        displayName: String = ""
    ): Resolved {
        val fallback = titleFromFileName(displayName)
        val title = cleanTitle(rawTitle, identifier)
            ?: cleanTitle(navTitle, identifier)
            ?: fallback
            ?: "未命名书籍"
        return Resolved(title = title, author = cleanAuthor(rawAuthor, identifier).orEmpty())
    }

    /** 清洗后可用则返回，否则 null —— 交给调用方走下一级回退。 */
    fun cleanTitle(value: String?, identifier: String? = null): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (trimmed.isPlaceholder()) return null
        if (identifier != null && trimmed.equals(identifier.trim(), ignoreCase = true)) return null
        // 纯 UUID / urn 形式的「书名」同样是 id 泄漏
        if (GENERATED_AUTHOR_PATTERNS.any { it.matches(trimmed) }) return null
        return trimmed
    }

    fun cleanAuthor(value: String?, identifier: String? = null): String? {
        val trimmed = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (trimmed.isPlaceholder()) return null
        if (identifier != null && trimmed.equals(identifier.trim(), ignoreCase = true)) return null
        if (GENERATED_AUTHOR_PATTERNS.any { it.matches(trimmed) }) return null
        return trimmed
    }

    /** 去扩展名并剥掉分享噪声；剥完为空就退回未剥的原名，别把书名整个吃掉。 */
    fun titleFromFileName(displayName: String): String? {
        val base = displayName.trim()
            .substringBeforeLast('.')
            .trim()
            .takeIf(String::isNotEmpty)
            ?: return null
        var stripped = base
        FILENAME_NOISE.forEach { noise -> stripped = noise.replace(stripped, "") }
        return stripped.trim().takeIf(String::isNotEmpty) ?: base
    }

    private fun String.isPlaceholder(): Boolean = lowercase() in PLACEHOLDER_VALUES
}
