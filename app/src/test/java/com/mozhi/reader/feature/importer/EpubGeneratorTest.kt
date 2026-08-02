package com.mozhi.reader.feature.importer

import java.nio.file.Files
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubGeneratorTest {
    private val generator = EpubGenerator()

    @Test
    fun `generates a minimal valid epub package`() {
        val directory = Files.createTempDirectory("moread-epub-test").toFile()
        val output = directory.resolve("sample.epub")
        val chapters = listOf(
            TxtChapter(0, "第一章 初见", "江南有雨。\u000C\n\n灯火可亲。📖", 0, 12),
            TxtChapter(1, "第二章 同行", "长路漫漫。", 13, 20)
        )

        val result = generator.generate(output, "墨知示例", "墨知", chapters)

        assertTrue(result.file.isFile)
        assertEquals(2, result.chapters.size)
        ZipInputStream(result.file.inputStream()).use { input ->
            assertEquals("mimetype", input.nextEntry.name)
        }
        ZipFile(result.file).use { zip ->
            assertNotNull(zip.getEntry("META-INF/container.xml"))
            assertNotNull(zip.getEntry("OEBPS/content.opf"))
            assertNotNull(zip.getEntry("OEBPS/nav.xhtml"))
            assertNotNull(zip.getEntry("OEBPS/text/chapter-00001.xhtml"))
            val mime = zip.getInputStream(zip.getEntry("mimetype")).bufferedReader().readText()
            assertEquals("application/epub+zip", mime)

            val packageDocument = zip.getInputStream(zip.getEntry("OEBPS/content.opf"))
                .bufferedReader()
                .use { it.readText() }
            assertTrue(
                packageDocument.contains(
                    Regex("""<meta property="dcterms:modified">\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z</meta>""")
                )
            )

            val xmlEntries = listOf(
                "META-INF/container.xml",
                "OEBPS/content.opf",
                "OEBPS/nav.xhtml",
                "OEBPS/toc.ncx",
                "OEBPS/text/chapter-00001.xhtml",
                "OEBPS/text/chapter-00002.xhtml"
            )
            val documentBuilder = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }.newDocumentBuilder()
            xmlEntries.forEach { path ->
                val entry = requireNotNull(zip.getEntry(path))
                zip.getInputStream(entry).use { input ->
                    documentBuilder.parse(input)
                }
                val content = zip.getInputStream(entry).bufferedReader().use { it.readText() }
                assertTrue("$path 必须从 XML 声明开始", content.startsWith("<?xml"))
            }
        }
    }
}
