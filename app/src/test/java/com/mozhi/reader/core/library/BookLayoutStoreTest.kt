@file:Suppress("DEPRECATION")

package com.mozhi.reader.core.library

import android.content.Context
import com.mozhi.reader.core.epub.dom.EpubDomChapter
import com.mozhi.reader.core.epub.dom.EpubDomNode
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookLayoutStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private val context: Context by lazy { mockk<Context>().also { every { it.filesDir } returns temporary.root } }
    private val store by lazy { BookLayoutStore(context) }
    private val layoutRoot get() = File(temporary.root, "book-layout/1")
    private val domFile get() = File(layoutRoot, "chapters/ch-00000.json")
    private val legacyFile get() = File(layoutRoot, "legacy-v9/ch-00000.json")
    private val indexFile get() = File(layoutRoot, BookLayoutStore.INDEX_FILE)
    private val marker get() = File(layoutRoot, BookLayoutStore.COMPACT_MARKER)
    private val dom = EpubDomChapter(
        chapterIndex = 0, href = "OPS/chapter.xhtml", textLength = 4,
        bodyNode = EpubDomNode("body", textStart = 0, textEnd = 4,
            children = listOf(EpubDomNode("p", textStart = 0, textEnd = 4)))
    )
    private val legacy = EpubLayoutChapter(
        chapterIndex = 0, href = dom.href, textLength = dom.textLength,
        documentTitle = "Chapter", immersivePage = true,
        blocks = listOf(EpubLayoutBlock(0, EpubLayoutBlockKind.PARAGRAPH, 0, 4, EpubElementRef("p")))
    )

    @Test
    fun compactionPreservesIndexPathsAndReaderCompatibility() = runTest {
        seedOldLayout(styled = true)
        val indexBefore = indexFile.readBytes()
        assertEquals(dom, store.readChapter(1, 0)!!.dom)
        assertTrue(store.compact(1))
        assertArrayEquals(indexBefore, indexFile.readBytes())
        assertTrue(domFile.isFile)
        assertFalse(File(domFile.path + ".gz").exists())
        assertTrue(GzipTextFiles.isGzip(domFile))
        assertTrue(GzipTextFiles.isGzip(legacyFile))
        assertTrue(marker.isFile)
        assertTrue(store.hasCurrentLayout(1, listOf(4)))
        val loaded = store.readChapter(1, 0)!!
        assertEquals(dom, loaded.dom)
        assertEquals(legacy.copy(blocks = emptyList()), loaded.document)
        assertEquals(loaded, BookLayoutStore(context).readChapter(1, 0))
        assertFalse(store.compact(1))
        assertEquals(listOf("ch-00000.json"), domFile.parentFile!!.list()!!.toList())
    }

    @Test
    fun booksWithoutStylesheetsRetainLegacyBlocks() = runTest {
        seedOldLayout(styled = false)
        val legacyBefore = legacyFile.readBytes()
        assertTrue(store.compact(1))
        assertArrayEquals(legacyBefore, legacyFile.readBytes())
        assertEquals(legacy, store.readChapter(1, 0)!!.document)
        assertTrue(GzipTextFiles.isGzip(domFile))
        assertTrue(marker.isFile)
    }

    @Test
    fun missingChapterDoesNotMarkMigrationCompleteAndCanBeRetried() = runTest {
        seedOldLayout(styled = true)
        assertTrue(domFile.delete())
        assertFalse(store.compact(1))
        assertFalse(marker.exists())
        assertFalse(store.hasCurrentLayout(1, listOf(4)))
        domFile.writeText(json.encodeToString(dom))
        assertTrue(store.compact(1))
        assertTrue(marker.isFile)
        assertEquals(dom, store.readChapter(1, 0)!!.dom)
    }

    @Test
    fun invalidDomIsRetryableAndDoesNotDiscardLegacyBlocks() = runTest {
        seedOldLayout(styled = true)
        val legacyBefore = legacyFile.readBytes()
        domFile.writeText("{invalid JSON")
        assertFalse(store.compact(1))
        assertFalse(marker.exists())
        assertArrayEquals(legacyBefore, legacyFile.readBytes())
        domFile.writeText(json.encodeToString(dom.copy(textLength = 5)))
        assertFalse(store.compact(1))
        assertFalse(marker.exists())
        domFile.writeText(json.encodeToString(dom))
        assertTrue(store.compact(1))
        assertTrue(marker.isFile)
    }

    @Test
    fun invalidLegacySidecarRemainsRetryableAfterDomWasCompressed() = runTest {
        seedOldLayout(styled = true)
        legacyFile.writeText("{invalid JSON")
        assertTrue(store.compact(1))
        assertTrue(GzipTextFiles.isGzip(domFile))
        assertFalse(marker.exists())
        legacyFile.writeText(json.encodeToString(legacy))
        assertTrue(store.compact(1))
        assertTrue(marker.isFile)
        assertEquals(legacy.copy(blocks = emptyList()), store.readChapter(1, 0)!!.document)
    }

    @Test
    fun freshImportsWriteCompressedDomAndPreserveChapterMetadata() = runTest {
        val epub = emptyArchive()
        store.replace(1, epub, layoutPackage(styled = true), listOf(
            EpubLayoutChapterInput(0, dom.href, legacy, dom)
        ))
        val saved = json.decodeFromString<EpubLayoutPackage>(indexFile.readText())
        assertEquals("chapters/ch-00000.json.gz", saved.chapters.single().fileName)
        assertTrue(GzipTextFiles.isGzip(File(layoutRoot, saved.chapters.single().fileName)))
        assertTrue(marker.isFile)
        assertFalse(store.compact(1))
        val loaded = BookLayoutStore(context).readChapter(1, 0)!!
        assertEquals(dom, loaded.dom)
        assertEquals(legacy.copy(blocks = emptyList()), loaded.document)
        assertEquals(saved.stylesheets, loaded.stylesheets)
    }

    @Test
    fun freshUnstyledImportsKeepBlocksForTheLegacyReader() = runTest {
        store.replace(1, emptyArchive(), layoutPackage(styled = false), listOf(
            EpubLayoutChapterInput(0, dom.href, legacy, dom)
        ))
        assertEquals(legacy, store.readChapter(1, 0)!!.document)
        assertEquals(dom, store.readChapter(1, 0)!!.dom)
    }

    @Test
    fun v9LibraryIsNotCompactedAndRemainsReadable() = runTest {
        seedOldLayout(styled = false)
        indexFile.writeText(json.encodeToString(layoutPackage(styled = false).copy(schemaVersion = 9)))
        domFile.writeText(json.encodeToString(legacy))
        val original = domFile.readBytes()
        assertFalse(store.compact(1))
        assertFalse(marker.exists())
        assertArrayEquals(original, domFile.readBytes())
        assertEquals(legacy, store.readChapter(1, 0)!!.document)
        assertNotNull(store.readChapter(1, 0)!!.dom)
    }

    private fun seedOldLayout(styled: Boolean) {
        domFile.parentFile!!.mkdirs()
        legacyFile.parentFile!!.mkdirs()
        indexFile.writeText(json.encodeToString(layoutPackage(styled)))
        domFile.writeText(json.encodeToString(dom))
        legacyFile.writeText(json.encodeToString(legacy))
    }

    private fun layoutPackage(styled: Boolean) = EpubLayoutPackage(
        packageDocumentPath = "OPS/content.opf",
        stylesheets = if (styled) listOf(EpubStylesheetText("OPS/style.css", "p { color: black; }")) else emptyList(),
        chapters = listOf(EpubLayoutChapterRef(0, dom.href, 4, "chapters/ch-00000.json"))
    )

    private fun emptyArchive(): File = temporary.newFile("book.epub").apply {
        ZipOutputStream(outputStream()).use { }
    }
}
