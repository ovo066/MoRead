package com.mozhi.reader.core.importer.convert

import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiEpubModelTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sha = "0123456789abcdef".repeat(4)

    @Test fun markdownAndHtmlRemainByteIdenticalToThePreMobiWriter() {
        val books = listOf(
            MarkdownBookConverter().convert(("---\ntitle: 兼容书名\nauthor: 作者\nlanguage: zh\n---\n# 书名\n\n## 第一章\n**粗体**与正文。\n\n## 第二章\n![图](data:image/png;base64,${BookConverterTest.PNG})\n").toByteArray(), "book.md"),
            HtmlBookConverter().convert(("<html lang='zh' class='paper'><head><title>兼容书名</title><style>.note{color:#aa3333}</style></head>" +
                "<body style='color:#333'><h1>书名</h1><h2>第一章</h2><p>正文</p><h2>第二章</h2><p class='note'>结尾</p>" +
                "<img src='data:image/png;base64,${BookConverterTest.PNG}'></body></html>").toByteArray(), "book.html")
        )
        books.forEach { book ->
            assertNull(book.coverHref)
            assertTrue(book.chapters.all { it.inToc })
            val before = ByteArrayOutputStream().also { LegacyDocumentEpubWriter.write(book, sha, it) }.toByteArray()
            val after = ByteArrayOutputStream().also { MinimalEpubWriter.write(book, sha, it) }.toByteArray()
            assertArrayEquals(before, after)
        }
    }

    @Test fun coverMetadataAndHiddenTocPartsDoNotChangeTheSpine() {
        val image = ConvertedResource("images/cover.png", "image/png", java.util.Base64.getDecoder().decode(BookConverterTest.PNG))
        val book = ConvertedBook("书名", chapters = listOf(
            ConvertedChapter("扉页", "<p>扉页</p>", false), ConvertedChapter("第一章", "<p>正文</p>"),
            ConvertedChapter("尾页", "<p>尾页</p>", false)), resources = listOf(image), coverHref = image.href)
        val file = temporary.newFile("cover.epub")
        MinimalEpubWriter.write(book, sha, file)
        ZipFile(file).use { zip ->
            fun xml(path: String) = zip.getInputStream(zip.getEntry("OEBPS/$path")).use { Jsoup.parse(it.readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser()) }
            val opf = xml("content.opf")
            val cover = opf.select("manifest item[properties=cover-image]").single()
            assertEquals(image.href, cover.attr("href"))
            assertEquals(cover.attr("id"), opf.selectFirst("meta[name=cover]")!!.attr("content"))
            assertEquals(3, opf.select("spine itemref").size)
            assertEquals(listOf("第一章"), xml("nav.xhtml").select("nav a").map { it.text() })
            assertEquals("chapter-2.xhtml", xml("nav.xhtml").select("nav a").single().attr("href"))
        }
    }

    @Test fun noVisibleTocFallsBackToBookTitleForEveryPart() {
        val file = temporary.newFile("fallback.epub")
        val book = ConvertedBook("书名", chapters = listOf(ConvertedChapter("一", "<p>甲</p>", false), ConvertedChapter("二", "<p>乙</p>", false)))
        MinimalEpubWriter.write(book, sha, file)
        ZipFile(file).use { zip ->
            val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).use { Jsoup.parse(it.readBytes().toString(Charsets.UTF_8)) }
            assertEquals(listOf("书名", "书名"), nav.select("nav a").map { it.text() })
        }
    }
}
