package com.mozhi.reader.core.importer.convert

import java.util.Locale

enum class BookDocumentFormat(val extensions: Set<String>, val mimeTypes: Set<String>) {
    MARKDOWN(setOf("md", "markdown"), setOf("text/markdown", "text/x-markdown")),
    HTML(setOf("html", "htm", "xhtml"), setOf("text/html", "application/xhtml+xml")),
    MOBI(setOf("mobi", "azw", "azw3", "prc"), setOf("application/x-mobipocket-ebook",
        "application/vnd.amazon.ebook", "application/vnd.amazon.mobi8-ebook"));

    companion object {
        fun fromFileName(name: String): BookDocumentFormat? {
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return entries.firstOrNull { extension in it.extensions }
        }

        fun fromMime(mime: String): BookDocumentFormat? {
            val normalized = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
            return entries.firstOrNull { normalized in it.mimeTypes }
        }
    }
}

enum class BookImportFormat { TXT, EPUB, MARKDOWN, HTML, MOBI }

/** 所有入口共用：已知后缀优先于文件管理器猜测的 MIME，最后才兼容普通文本。 */
fun resolveBookImportFormat(name: String, mime: String): BookImportFormat? {
    fun BookDocumentFormat.asImportFormat() = when (this) {
        BookDocumentFormat.MARKDOWN -> BookImportFormat.MARKDOWN
        BookDocumentFormat.HTML -> BookImportFormat.HTML
        BookDocumentFormat.MOBI -> BookImportFormat.MOBI
    }
    BookDocumentFormat.fromFileName(name)?.let { return it.asImportFormat() }
    when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "epub" -> return BookImportFormat.EPUB
        "txt" -> return BookImportFormat.TXT
    }
    BookDocumentFormat.fromMime(mime)?.let { return it.asImportFormat() }
    val normalized = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
    return when {
        normalized == "application/epub+zip" -> BookImportFormat.EPUB
        normalized.startsWith("text/") -> BookImportFormat.TXT
        else -> null
    }
}

object BookImportFiles {
    val extensions = setOf("txt", "epub") + BookDocumentFormat.entries.flatMap { it.extensions }
    val pickerMimeTypes = listOf("application/epub+zip", "text/plain", "application/octet-stream") +
        BookDocumentFormat.entries.flatMap { it.mimeTypes }
}
