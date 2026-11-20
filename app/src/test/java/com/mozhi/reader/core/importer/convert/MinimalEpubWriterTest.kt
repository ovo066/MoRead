package com.mozhi.reader.core.importer.convert

import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import com.mozhi.reader.core.importer.SourceFingerprints
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MinimalEpubWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun outputIsByteIdenticalIncludingIdentifierModifiedDateAndEntryTimes() {
        val source = "# 一本书\n\n## 第一章\n正文。\n\n## 第二章\n继续。".toByteArray()
        val sha = SourceFingerprints.of(source).sha256
        fun generate(): ByteArray = ByteArrayOutputStream().also {
            MinimalEpubWriter.write(MarkdownBookConverter().convert(source, "book.md"), sha, it)
        }.toByteArray()
        val originalZone = TimeZone.getDefault()
        val first: ByteArray
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            first = generate()
            assertArrayEquals(first, generate())
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertArrayEquals(first, generate())
        } finally {
            TimeZone.setDefault(originalZone)
        }
        ZipInputStream(ByteArrayInputStream(first)).use { zip ->
            val mime = zip.nextEntry
            assertEquals("mimetype", mime.name)
            assertEquals(ZipEntry.STORED, mime.method)
            assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.US_ASCII))
            assertEquals(20L, mime.size)
            assertTrue(mime.extra == null || mime.extra.isEmpty())
        }
        val file = temporary.newFile("deterministic.epub").apply { writeBytes(first) }
        ZipFile(file).use { zip ->
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).use { it.readBytes().toString(Charsets.UTF_8) }
            assertTrue(opf.contains("urn:sha256:$sha"))
            assertTrue(opf.contains("2000-01-01T00:00:00Z"))
            val entries = zip.entries().asSequence().toList()
            assertEquals(1, entries.map { it.time }.distinct().size)
            assertEquals(entries.size, entries.map { it.name }.distinct().size)
        }
    }

    @Test fun generatedEpubIsWellFormedAndExistingPackageAndTextReadersCanReadItBack() {
        val source = """
            <html lang="zh-CN" class="root"><head><title>回读书名 &amp; 副题</title><meta name="author" content="作者">
            <style>.paper{color:#333}</style></head><body style="color:#222"><h1>书名</h1>
            <section class="paper"><h2>第一章 相遇</h2><p>甲正文。</p><img alt="插图" src="data:image/png;base64,${BookConverterTest.PNG}">
            <div><h2>第二章 告别</h2><p>乙正文。</p></div></section></body></html>
        """.trimIndent().toByteArray()
        val book = HtmlBookConverter().convert(source, "source.html")
        val file = temporary.newFile("roundtrip.epub")
        MinimalEpubWriter.write(book, SourceFingerprints.of(source).sha256, file)
        val inspector = EpubPackageInspector()
        val layout = inspector.inspect(file)
        assertEquals("3.0", layout.epubVersion)
        assertEquals(2, layout.spine.size)
        assertFalse(layout.diagnostics.any { it.code == "missing-resource" })
        val texts = mutableListOf<String>()
        ZipFile(file).use { zip ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            zip.entries().asSequence().filter { it.name.endsWith(".xhtml") || it.name.endsWith(".opf") || it.name.endsWith(".xml") }
                .forEach { entry -> zip.getInputStream(entry).use { factory.newDocumentBuilder().parse(it) } }
            val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).use {
                Jsoup.parse(it.readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
            }
            assertEquals(book.chapters.map { it.title }, nav.select("nav li a").map { it.text() })
            layout.spine.forEach { spine ->
                val href = requireNotNull(spine.href)
                val chapter = zip.getInputStream(zip.getEntry(href)).use { it.readBytes() }
                val dom = Jsoup.parse(chapter.toString(Charsets.UTF_8))
                assertEquals("root", dom.selectFirst("html")!!.className())
                assertEquals("color:#222", dom.body().attr("style"))
                val extracted = EpubTextExtractor().extractWithImages(chapter, href)
                texts += extracted.text
                extracted.images.forEach { assertNotNull(zip.getEntry(it.href)) }
            }
        }
        assertTrue(texts[0].contains("甲正文"))
        assertTrue(texts[1].contains("乙正文"))
        assertFalse(inspector.readStylesheets(file, layout).isEmpty())
    }

    @Test fun cancelledWriterRemovesThePartialPackage() {
        val source = "普通正文。".toByteArray()
        val book = MarkdownBookConverter().convert(source, "a.md")
        val target = temporary.newFile("partial.epub")
        var checks = 0
        try {
            MinimalEpubWriter.write(book, SourceFingerprints.of(source).sha256, target) {
                if (++checks == 4) throw CancellationException("cancel")
            }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertFalse(target.exists())
        }
    }

    @Test fun nonCoreImagesHaveAResolvableCoreImageFallback() {
        val source = "<p>图片正文</p><img src='data:image/webp;base64,AQIDBA=='>".toByteArray()
        val book = HtmlBookConverter().convert(source, "image.html")
        assertEquals("image/webp", book.resources.single().mediaType)
        val target = temporary.newFile("foreign-image.epub")
        MinimalEpubWriter.write(book, SourceFingerprints.of(source).sha256, target)
        ZipFile(target).use { zip ->
            val opf = zip.getInputStream(zip.getEntry("OEBPS/content.opf")).use {
                Jsoup.parse(it.readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
            }
            val foreign = opf.select("manifest item").single { it.attr("media-type") == "image/webp" }
            val fallback = opf.select("manifest item").single { it.attr("id") == foreign.attr("fallback") }
            assertEquals("image/svg+xml", fallback.attr("media-type"))
            assertNotNull(zip.getEntry("OEBPS/${fallback.attr("href")}"))
        }
    }
}
