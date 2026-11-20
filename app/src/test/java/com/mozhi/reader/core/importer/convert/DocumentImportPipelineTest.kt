package com.mozhi.reader.core.importer.convert

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import com.mozhi.reader.core.i18n.UiText
import com.mozhi.reader.core.importer.ImportFailureException
import com.mozhi.reader.core.importer.DuplicateBookException
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.importer.SourceFingerprints
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import com.mozhi.reader.feature.importer.ImportCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 数据库用替身，转换、Readium、正文抽取和布局存储均使用现有实际入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DocumentImportPipelineTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun context(): Context {
        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        return object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
    }

    private fun importer(context: Context, library: LibraryRepository) = ImportCoordinator(
        context, mockk(), mockk(), mockk(), EpubTextExtractor(), EpubLayoutDocumentParser(),
        EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context), mockk(), ReadiumServices(context), library
    )

    @Test fun markdownAndHtmlUseEpubPipelineAndRecordOriginalSourceFingerprints() = runBlocking {
        listOf(
            "source.md" to "# 书名\n\n## 第一章\n甲正文。\n\n## 第二章\n乙正文。",
            "source.html" to "<title>书名</title><h1>书名</h1><div><h2>第一章</h2><p>甲正文。</p><h2>第二章</h2><p>乙正文。</p></div>"
        ).forEach { (name, source) ->
            val context = context()
            val file = temporary.newFile(name).apply { writeText(source) }
            val original = file.readBytes()
            val fingerprint = SourceFingerprints.of(original)
            val library = mockk<LibraryRepository>(relaxed = true)
            val book = slot<BookEntity>()
            val chapters = slot<List<ChapterDraft>>()
            val bodies = slot<List<ChapterTextInput>>()
            coEvery { library.findBookBySourceHash(any()) } returns null
            coEvery { library.insertBook(capture(book), capture(chapters), any()) } returns 19L
            coEvery { library.materializeBookText(19L, capture(bodies), false) } returns Unit
            val coordinator = importer(context, library)
            assertEquals(PreparedImport.BookImported(19L), coordinator.prepare(Uri.fromFile(file), false))
            assertEquals(BookSourceType.EPUB, book.captured.sourceType)
            assertEquals(fingerprint.sha256, book.captured.sourceSha256)
            assertEquals(fingerprint.size, book.captured.sourceByteSize)
            assertEquals("书名", book.captured.title)
            val epub = File(book.captured.epubPath)
            assertTrue(epub.isFile)
            assertNotEquals(fingerprint.size, epub.length())
            assertEquals(listOf("第一章", "第二章"), chapters.captured.map { it.title })
            assertEquals(2, bodies.captured.size)
            assertTrue(bodies.captured[0].body.contains("甲正文"))
            assertTrue(bodies.captured[1].body.contains("乙正文"))
            assertArrayEquals(original, file.readBytes())
            coVerify(exactly = 1) { library.markTextReady(19L) }
            coVerify(exactly = 0) { library.getEpubBooksWithoutSource() }
            assertTrue(File(context.cacheDir, "document-import").listFiles().orEmpty().isEmpty())

            // 即使生成包大小不同、正文记录已移除，也按源指纹提示重复；不补算生成包的指纹。
            coEvery { library.findBookBySourceHash(fingerprint.sha256) } returns book.captured.copy(id = 19L, removedAt = 1L)
            val duplicate = coordinator.prepare(Uri.fromFile(file), false) as PreparedImport.Duplicate
            assertEquals(19L, duplicate.bookId)
            assertTrue(duplicate.exact)
            assertTrue(duplicate.removed)
            try {
                coordinator.importDirectly(Uri.fromFile(file))
                fail("Batch imports must skip an identical document")
            } catch (error: DuplicateBookException) {
                assertEquals(19L, error.bookId)
            }
            assertEquals(1, File(context.filesDir, "books").listFiles().orEmpty().count { it.extension == "epub" })
            assertTrue(File(context.cacheDir, "document-import").listFiles().orEmpty().isEmpty())
            assertEquals(PreparedImport.BookImported(19L), coordinator.prepare(Uri.fromFile(file), true))
            coVerify(exactly = 2) { library.insertBook(any(), any(), any()) }
        }
    }

    @Test fun emptyConversionAndCancellationCleanStagingAndDoNotPublishBooks() = runBlocking {
        val context = context()
        val library = mockk<LibraryRepository>(relaxed = true)
        coEvery { library.findBookBySourceHash(any()) } returns null
        val coordinator = importer(context, library)
        val empty = temporary.newFile("empty.html").apply { writeText("<title>空</title><script>hidden</script>") }
        try {
            coordinator.importDirectly(Uri.fromFile(empty))
            fail("Expected empty document failure")
        } catch (error: ImportFailureException) {
            assertEquals(UiText.of(R.string.import_document_empty), error.uiText)
        }
        coEvery { library.findBookBySourceHash(any()) } throws CancellationException("cancel")
        val source = temporary.newFile("cancel.md").apply { writeText("正文。") }
        try {
            coordinator.importDirectly(Uri.fromFile(source))
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(source.isFile)
        }
        assertTrue(File(context.cacheDir, "document-import").listFiles().orEmpty().isEmpty())
        assertTrue(File(context.filesDir, "books").listFiles().orEmpty().isEmpty())
        coVerify(exactly = 0) { library.insertBook(any(), any(), any()) }
    }
}
