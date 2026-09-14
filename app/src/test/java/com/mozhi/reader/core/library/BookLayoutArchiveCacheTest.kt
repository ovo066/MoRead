package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.feature.importer.EpubLayoutDocumentParser
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookLayoutArchiveCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val context by lazy { mockk<Context>().also {
        every { it.filesDir } returns temporary.newFolder("files")
        every { it.cacheDir } returns temporary.newFolder("cache")
    } }
    private val parser = EpubLayoutDocumentParser()
    private val href = "OPS/chapter.xhtml"
    private val html = "<html><head><title>第一章</title></head><body><h1>第一章</h1><p>雨夜里的灯塔，照亮了归途。🙂</p></body></html>".toByteArray()
    private val parsed get() = parser.parseWithText(html, 0, href, emptyMap())
    private fun archive() = File(context.filesDir, "books/book.epub").apply {
        parentFile.mkdirs()
        ZipOutputStream(outputStream()).use { zip -> zip.putNextEntry(ZipEntry(href)); zip.write(html); zip.closeEntry() }
    }
    private fun pkg() = EpubLayoutPackage(packageDocumentPath = "OPS/content.opf",
        resources = listOf(EpubLayoutResource("chapter", href, href, "application/xhtml+xml", kind = EpubLayoutResourceKind.DOCUMENT, sizeBytes = html.size.toLong())))
    private fun cache(id: Long = 1) = File(context.cacheDir, "book-dom/$id")

    @Test fun importWritesOnlyAnIndexAndEvictedChaptersAreRebuiltFromTheArchive() = runTest {
        val store = BookLayoutStore(context)
        val value = parsed
        store.initialize(1, archive(), pkg(), listOf(EpubLayoutChapterInput(0, href, value.document)))
        val root = File(context.filesDir, "book-layout/1")
        assertFalse(File(root, "chapters").exists())
        assertFalse(File(root, "legacy-v9").exists())
        assertFalse(cache().exists())
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        val first = store.readChapter(1, 0, value.text)!!
        assertEquals(value.dom, first.dom)
        assertEquals(value.document, first.document)
        assertTrue(cache().listFiles()!!.single().name.endsWith(".gz"))
        cache().deleteRecursively()
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        assertEquals(first, BookLayoutStore(context).readChapter(1, 0, value.text))
    }

    @Test fun parserRevisionInvalidatesOnlyTheCacheAndContentMismatchFallsBackWithoutMovingAnchors() = runTest {
        val store = BookLayoutStore(context)
        val value = parsed
        store.initialize(1, archive(), pkg(), listOf(EpubLayoutChapterInput(0, href, value.document)))
        assertNotNull(store.readChapter(1, 0, value.text))
        val cached = cache().listFiles()!!.single()
        GzipTextFiles.replaceWithGzip(cached, GzipTextFiles.readText(cached).replace("\"parserRevision\":1", "\"parserRevision\":0"))
        assertEquals(value.dom, store.readChapter(1, 0, value.text)!!.dom)
        assertTrue(GzipTextFiles.readText(cached).contains("\"parserRevision\":1"))
        assertNull(store.readChapter(1, 0, "错".repeat(value.text.length)))
        assertTrue(store.hasCurrentLayout(1, listOf(value.text.length)))
        assertNotNull(store.readChapter(1, 0, value.text))
    }

    @Test fun oldMaterializedLayoutsBecomeOneIndexWithoutRebuildingText() = runTest {
        val store = BookLayoutStore(context)
        val epub = archive()
        val value = parsed
        store.replace(1, epub, pkg(), listOf(EpubLayoutChapterInput(0, href, value.document, value.dom)))
        assertTrue(File(context.filesDir, "book-layout/1/legacy-v9").isDirectory)
        assertTrue(store.adoptArchive(1, epub, listOf(value.text.length)))
        assertFalse(File(context.filesDir, "book-layout/1/legacy-v9").exists())
        assertEquals(value.dom, store.readChapter(1, 0, value.text)!!.dom)
    }

    @Test fun editedCanonicalTextUpdatesOnlyTheCoordinateIndexAndNeverRestoresTheOriginalBody() = runTest {
        val store = BookLayoutStore(context)
        val epub = archive()
        val value = parsed
        store.replace(1, epub, pkg(), listOf(EpubLayoutChapterInput(0, href, value.document, value.dom)))
        assertNotNull(store.readChapter(1, 0, value.text))
        val edited = value.text + "修改后的段落。"
        assertTrue(store.adoptArchive(1, epub, listOf(edited.length)))
        assertTrue(store.hasCurrentLayout(1, listOf(edited.length)))
        assertNull(store.readChapter(1, 0, edited))
        assertTrue(store.adoptArchive(1, epub, listOf(edited.length + 2)))
        assertTrue(store.hasCurrentLayout(1, listOf(edited.length + 2)))
        assertNull(store.readChapter(1, 0, edited + "🙂"))
        assertTrue(epub.isFile)
    }

    @Test fun cacheEnforcesPerBookAndGlobalByteBudgetsEvenWhenAnEntryDoesNotFit() = runTest {
        val value = parsed
        val epub = archive()
        val pkg = pkg().copy(sourceArchive = "books/book.epub")
        val cache = BookLayoutCache(context, parser, bookBudget = 300, totalBudget = 450)
        repeat(5) { index ->
            assertNotNull(cache.read(index + 1L, pkg, EpubLayoutChapterRef(0, href, value.text.length, ""), value.text))
            assertTrue(this@BookLayoutArchiveCacheTest.cache(index + 1L).walkTopDown().filter(File::isFile).sumOf(File::length) <= 300L)
            assertTrue(File(context.cacheDir, "book-dom").walkTopDown().filter(File::isFile).sumOf(File::length) <= 450L)
        }
        assertTrue(epub.isFile)
    }

    @Test fun missingSchemaCannotBeMistakenForTheCurrentVersion() {
        val json = "{\"chapterIndex\":0,\"href\":\"chapter.xhtml\",\"bodyNode\":{\"tag\":\"body\"},\"textLength\":0}"
        assertThrows(SerializationException::class.java) { Json.decodeFromString<EpubDomChapter>(json) }
        assertThrows(SerializationException::class.java) { Json.decodeFromString<EpubLayoutPackage>("{\"packageDocumentPath\":\"content.opf\"}") }
    }
}
