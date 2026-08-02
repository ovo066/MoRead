package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.ChapterDraft
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class GeneratedEpub(
    val file: File,
    val chapters: List<ChapterDraft>
)

class EpubGenerator @Inject constructor() {
    fun generate(
        outputFile: File,
        title: String,
        author: String,
        chapters: List<TxtChapter>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): GeneratedEpub {
        require(chapters.isNotEmpty()) { "没有可导出的章节" }
        outputFile.parentFile?.mkdirs()

        val identifier = "urn:uuid:${UUID.randomUUID()}"
        val drafts = chapters.mapIndexed { index, chapter ->
            ChapterDraft(
                index = index,
                title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                href = chapterHref(index),
                charCount = chapter.charCount
            )
        }

        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            writeMimetype(zip)
            zip.writeText(
                "META-INF/container.xml",
                containerXml()
            )
            zip.writeText("OEBPS/styles.css", stylesheet())
            zip.writeText(
                "OEBPS/nav.xhtml",
                navigationDocument(title, drafts)
            )
            zip.writeText(
                "OEBPS/toc.ncx",
                ncxDocument(identifier, title, drafts)
            )
            zip.writeText(
                "OEBPS/content.opf",
                packageDocument(identifier, title, author, drafts)
            )

            val progressStep = (chapters.size / 100).coerceAtLeast(1)
            chapters.forEachIndexed { index, chapter ->
                zip.writeText(
                    "OEBPS/${chapterHref(index)}",
                    chapterDocument(
                        title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                        content = chapter.content
                    )
                )
                val completed = index + 1
                if (completed == chapters.size || completed % progressStep == 0) {
                    onProgress(completed, chapters.size)
                }
            }
        }

        return GeneratedEpub(outputFile, drafts)
    }

    private fun writeMimetype(zip: ZipOutputStream) {
        val bytes = "application/epub+zip".toByteArray(StandardCharsets.US_ASCII)
        val crc = CRC32().apply { update(bytes) }
        val entry = ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            this.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun ZipOutputStream.writeText(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(StandardCharsets.UTF_8))
        closeEntry()
    }

    private fun containerXml(): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent().trimStart()

    private fun packageDocument(
        identifier: String,
        title: String,
        author: String,
        chapters: List<ChapterDraft>
    ): String {
        val manifestItems = chapters.joinToString("\n") { chapter ->
            "<item id=\"chapter-${chapter.index}\" href=\"${chapter.href}\" media-type=\"application/xhtml+xml\"/>"
        }
        val spineItems = chapters.joinToString("\n") { chapter ->
            "<itemref idref=\"chapter-${chapter.index}\"/>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id" xml:lang="zh-CN">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">${identifier.escapeXml()}</dc:identifier>
                <dc:title>${title.escapeXml()}</dc:title>
                <dc:creator>${author.ifBlank { "未知作者" }.escapeXml()}</dc:creator>
                <dc:language>zh-CN</dc:language>
                <meta property="dcterms:modified">${Instant.now().truncatedTo(ChronoUnit.SECONDS)}</meta>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="css" href="styles.css" media-type="text/css"/>
                $manifestItems
              </manifest>
              <spine toc="ncx">
                $spineItems
              </spine>
            </package>
        """.trimIndent().trimStart()
    }

    private fun navigationDocument(title: String, chapters: List<ChapterDraft>): String {
        val items = chapters.joinToString("\n") { chapter ->
            "<li><a href=\"${chapter.href}\">${chapter.title.escapeXml()}</a></li>"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="zh-CN">
              <head><title>${title.escapeXml()} - 目录</title></head>
              <body>
                <nav epub:type="toc" id="toc">
                  <h1>目录</h1>
                  <ol>$items</ol>
                </nav>
              </body>
            </html>
        """.trimIndent().trimStart()
    }

    private fun ncxDocument(
        identifier: String,
        title: String,
        chapters: List<ChapterDraft>
    ): String {
        val points = chapters.joinToString("\n") { chapter ->
            """
                <navPoint id="nav-${chapter.index}" playOrder="${chapter.index + 1}">
                  <navLabel><text>${chapter.title.escapeXml()}</text></navLabel>
                  <content src="${chapter.href}"/>
                </navPoint>
            """.trimIndent()
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="${identifier.escapeXml()}"/></head>
              <docTitle><text>${title.escapeXml()}</text></docTitle>
              <navMap>$points</navMap>
            </ncx>
        """.trimIndent().trimStart()
    }

    private fun chapterDocument(title: String, content: String): String {
        val paragraphs = content
            .split(Regex("\\n\\s*\\n"))
            .flatMap { block ->
                if ('\n' in block) block.lines() else listOf(block)
            }
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString("\n") { paragraph -> "<p>${paragraph.escapeXml()}</p>" }

        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml" lang="zh-CN">
              <head>
                <title>${title.escapeXml()}</title>
                <link rel="stylesheet" type="text/css" href="../styles.css"/>
              </head>
              <body>
                <section epub:type="chapter" xmlns:epub="http://www.idpf.org/2007/ops">
                  <h1>${title.escapeXml()}</h1>
                  $paragraphs
                </section>
              </body>
            </html>
        """.trimIndent().trimStart()
    }

    private fun stylesheet(): String = """
        html { -webkit-text-size-adjust: 100%; }
        body { line-height: 1.65; margin: 0 5%; text-align: justify; }
        h1 { font-size: 1.45em; margin: 1.8em 0 1.2em; text-align: left; }
        p { margin: 0.8em 0; text-indent: 2em; }
    """.trimIndent()

    private fun chapterHref(index: Int): String =
        "text/chapter-${(index + 1).toString().padStart(5, '0')}.xhtml"

    private fun String.escapeXml(): String = buildString(length) {
        var index = 0
        while (index < this@escapeXml.length) {
            val codePoint = this@escapeXml.codePointAt(index)
            if (codePoint.isValidXmlCodePoint()) {
                when (codePoint) {
                    '&'.code -> append("&amp;")
                    '<'.code -> append("&lt;")
                    '>'.code -> append("&gt;")
                    '"'.code -> append("&quot;")
                    '\''.code -> append("&apos;")
                    else -> appendCodePoint(codePoint)
                }
            }
            index += Character.charCount(codePoint)
        }
    }

    private fun Int.isValidXmlCodePoint(): Boolean =
        this == 0x9 || this == 0xA || this == 0xD ||
            this in 0x20..0xD7FF ||
            this in 0xE000..0xFFFD ||
            this in 0x10000..0x10FFFF
}
