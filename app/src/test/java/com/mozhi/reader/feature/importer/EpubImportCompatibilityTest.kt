package com.mozhi.reader.feature.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.readium.ReadiumServices
import com.mozhi.reader.core.readium.EpubUriContainer
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.readium.r2.shared.util.use
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Local files are opt-in; book contents never become repository fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class EpubImportCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `colon and asterisk resource paths retain chapters and navigation`() = runBlocking {
        val file = temporary.newFile("special-paths.epub")
        val files = mapOf(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to """<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            "OPS/package.opf" to """<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="id">test-special-paths</dc:identifier><dc:title>Path compatibility</dc:title><dc:language>zh</dc:language></metadata><manifest><item id="chapter" href="Text/part:*one.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/></manifest><spine><itemref idref="chapter"/></spine></package>""",
            "OPS/nav.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>目录</title></head><body><nav epub:type="toc"><ol><li><a href="Text/part:*one.xhtml#first">第一章</a></li></ol></nav></body></html>""",
            "OPS/Text/part:*one.xhtml" to """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>第一章</title></head><body><p id="first">这是测试正文。</p></body></html>"""
        )
        ZipOutputStream(file.outputStream()).use { zip ->
            files.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        val original = file.readBytes()
        val publication = ReadiumServices(ApplicationProvider.getApplicationContext()).open(file)
        try {
            assertEquals(1, publication.readingOrder.size)
            assertEquals(1, publication.tableOfContents.size)
            val chapter = publication.get(publication.readingOrder.single())!!.use { it.read().getOrNull()!! }
            assertEquals("这是测试正文。", EpubTextExtractor().extract(chapter))
            assertTrue(publication.tableOfContents.single().href.toString().contains("%3A", ignoreCase = true))
        } finally {
            publication.close()
        }
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `URI compatibility preserves external URLs and encoded local references`() {
        listOf("https://example.org:8443/book#part:one", "data:image/png;base64,AAAA", "//example.org:8443/book",
            "Text/already%3Aencoded.xhtml#part%3Aone").forEach { href ->
            assertEquals(href, EpubUriContainer.escapeReference(href))
        }
        assertEquals("../Text/part%3Aone.xhtml#part%3Atwo",
            EpubUriContainer.escapeReference("../Text/part:one.xhtml#part:two"))
        assertEquals("#part%3Atwo", EpubUriContainer.escapeReference("#part:two"))
    }

    @Test
    fun `local epub opens and every reading order resource is readable`() = runBlocking {
        val paths = System.getenv("MOREAD_EPUB_FIXTURES") ?: System.getenv("MOREAD_EPUB_FIXTURE").orEmpty()
        assumeTrue(paths.isNotBlank())
        paths.split(File.pathSeparator).filter(String::isNotBlank).forEach { path ->
            val file = File(path)
            assertTrue("Configured EPUB fixture does not exist", file.isFile)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val publication = ReadiumServices(context).open(file)
            try {
                val layoutPackage = EpubPackageInspector().inspect(file)
                assertEquals(file.name, layoutPackage.spine.count { it.linear }, publication.readingOrder.size)
                assertTrue(file.name, publication.readingOrder.isNotEmpty())
                publication.readingOrder.forEach { link ->
                    val bytes = publication.get(link)?.use { it.read().getOrNull() }
                    assertTrue("Unreadable chapter: ${link.href}", bytes != null && bytes.isNotEmpty())
                }
            } finally {
                publication.close()
            }
        }
    }
}
