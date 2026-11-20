package com.mozhi.reader.core.importer.convert

import java.io.File
import java.io.OutputStream
import java.util.GregorianCalendar
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MinimalEpubWriter {
    fun write(book: ConvertedBook, sourceSha256: String, target: File, checkActive: () -> Unit = {}) {
        try {
            target.outputStream().buffered().use { write(book, sourceSha256, it, checkActive) }
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun write(book: ConvertedBook, sourceSha256: String, output: OutputStream, checkActive: () -> Unit = {}) {
        require(sourceSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Expected a source SHA-256" }
        if (book.chapters.isEmpty()) throw BookConversionException(BookConversionException.Reason.EMPTY_DOCUMENT)
        // 非核心图片格式保留原数据，同时提供 EPUB 3 必需的核心格式回退资源。
        val foreignImages = book.resources.filter { it.mediaType.startsWith("image/") && it.mediaType !in CORE_IMAGES }.map { it.href }.toSet()
        val fallback = ConvertedResource("image-fallback.svg", "image/svg+xml",
            """<svg xmlns="http://www.w3.org/2000/svg" width="1" height="1" viewBox="0 0 1 1"><title>Image</title></svg>""".toByteArray())
        val resources = (book.resources + if (foreignImages.isNotEmpty()) listOf(fallback) else emptyList()).sortedBy { it.href }
        val coverIndex = book.coverHref?.let { href -> resources.indexOfFirst { it.href == href && it.mediaType.startsWith("image/") } }
        require(coverIndex == null || coverIndex >= 0) { "Cover must reference an image resource" }
        val fallbackToc = book.chapters.none { it.inToc }
        val reserved = setOf("content.opf", "nav.xhtml", "base.css") + book.chapters.indices.map(::chapterHref)
        require(resources.map { it.href }.toSet().size == resources.size && resources.all {
            it.href !in reserved && it.href.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.[A-Za-z0-9]+"))
        }) { "Invalid or duplicate resource path" }
        ZipOutputStream(output).use { zip ->
            fun entry(path: String, bytes: ByteArray, stored: Boolean = false) {
                checkActive()
                val entry = ZipEntry(path).apply {
                    // DOS 时间使用本地日历字段：在任意时区均写入同一个 2000-01-01 时间，且不生成扩展时间戳。
                    time = GregorianCalendar().apply { clear(); set(2000, 0, 1, 0, 0, 0) }.timeInMillis
                    if (stored) {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                var offset = 0
                while (offset < bytes.size) {
                    checkActive()
                    val length = minOf(64 * 1024, bytes.size - offset)
                    zip.write(bytes, offset, length)
                    offset += length
                }
                zip.closeEntry()
            }
            fun xml(path: String, content: String) = entry(path, ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + content).toByteArray(Charsets.UTF_8))
            entry("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), stored = true)
            xml("META-INF/container.xml", """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            xml("OEBPS/content.opf", buildString {
                append("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                append("<dc:identifier id=\"book-id\">urn:sha256:${sourceSha256.lowercase()}</dc:identifier>")
                append("<dc:title>${escape(book.title)}</dc:title><dc:language>${escape(book.language)}</dc:language>")
                if (book.author.isNotBlank()) append("<dc:creator>${escape(book.author)}</dc:creator>")
                if (coverIndex != null) append("<meta name=\"cover\" content=\"r$coverIndex\"/>")
                append("""<meta property="dcterms:modified">2000-01-01T00:00:00Z</meta></metadata><manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="base" href="base.css" media-type="text/css"/>""")
                book.chapters.indices.forEach { append("<item id=\"c$it\" href=\"${chapterHref(it)}\" media-type=\"application/xhtml+xml\"/>") }
                resources.forEachIndexed { index, resource ->
                    val fallbackAttribute = if (resource.href in foreignImages) " fallback=\"r${resources.indexOf(fallback)}\"" else ""
                    val coverAttribute = if (index == coverIndex) " properties=\"cover-image\"" else ""
                    append("<item id=\"r$index\" href=\"${escape(resource.href)}\" media-type=\"${escape(resource.mediaType)}\"$fallbackAttribute$coverAttribute/>")
                }
                append("</manifest><spine>")
                book.chapters.indices.forEach { append("<itemref idref=\"c$it\"/>") }
                append("</spine></package>")
            })
            xml("OEBPS/nav.xhtml", """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>${escape(book.title)}</title></head><body><nav epub:type="toc" id="toc"><ol>""" +
                book.chapters.mapIndexedNotNull { index, chapter ->
                    if (chapter.inToc || fallbackToc) "<li><a href=\"${chapterHref(index)}\">${escape(if (fallbackToc) book.title else chapter.title)}</a></li>" else null
                }.joinToString("") + "</ol></nav></body></html>")
            entry("OEBPS/base.css", "body{line-height:1.5}img{max-width:100%;height:auto}pre{white-space:pre-wrap}table{border-collapse:collapse}th,td{padding:0.25em}del,s{text-decoration:line-through}".toByteArray())
            val cssLinks = resources.filter { it.mediaType == "text/css" }.joinToString("") {
                "<link rel=\"stylesheet\" type=\"text/css\" href=\"${escape(it.href)}\"/>"
            }
            book.chapters.forEachIndexed { index, chapter ->
                xml("OEBPS/${chapterHref(index)}", """<html xmlns="http://www.w3.org/1999/xhtml" lang="${escape(book.language)}" xml:lang="${escape(book.language)}"${rootAttributes(book.htmlAttributes)}><head><title>${escape(chapter.title)}</title><link rel="stylesheet" type="text/css" href="base.css"/>$cssLinks</head><body${rootAttributes(book.bodyAttributes)}>${chapter.xhtmlBody}</body></html>""")
            }
            resources.forEach { entry("OEBPS/${it.href}", it.bytes) }
        }
    }

    private fun chapterHref(index: Int) = "chapter-${index + 1}.xhtml"
    private val CORE_IMAGES = setOf("image/png", "image/jpeg", "image/gif", "image/svg+xml")
    private fun rootAttributes(attributes: Map<String, String>) = attributes.entries
        .filter { it.key in setOf("id", "class", "style", "dir", "title") }.sortedBy { it.key }
        .joinToString("") { " ${it.key}=\"${escape(it.value)}\"" }
    private fun escape(value: String) = xmlCharacters(value).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
