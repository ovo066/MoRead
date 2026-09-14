package com.mozhi.reader.feature.importer

import android.app.Application
import android.content.ContextWrapper
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.library.*
import com.mozhi.reader.core.readium.ReadiumServices
import io.mockk.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TxtImportStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun txtImportStoresCanonicalTextWithoutGeneratingOrOpeningAnEpub() = runTest {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) { override fun getFilesDir() = temporary.root }
        val splitter = TxtChapterSplitter()
        val rules = mockk<TxtTocRuleLoader> { every { rules } returns emptyList() }
        val sessions = ImportSessionStore(context, splitter, rules)
        val text = "第一章\n正文🙂。\n第二章\n另一个章节。"
        val split = splitter.splitWithCustomRegex(text, "^第[一二]章$")!!
        val session = sessions.create("book.txt", "书", "作者", "UTF-8", text, emptyList(), split)
        val library = mockk<LibraryRepository>(relaxed = true)
        val saved = slot<BookEntity>()
        coEvery { library.insertBook(capture(saved), any(), any()) } returns 1L
        val bodies = slot<List<ChapterTextInput>>()
        coEvery { library.materializeBookText(1, capture(bodies), true) } coAnswers {
            BookTextWriter().write(File(temporary.root, "book-text/1/text.mz"), bodies.captured)
            Unit
        }
        val readium = mockk<ReadiumServices>()
        val importer = ImportCoordinator(context, mockk(), rules, splitter, EpubTextExtractor(), EpubLayoutDocumentParser(),
            EpubPackageInspector(), BookLayoutStore(context), BookMediaStore(context), sessions, readium, library)
        assertEquals(1L, importer.confirmTxt(session.id, "书", "作者"))
        assertEquals("", saved.captured.epubPath)
        assertEquals(split.chapters.map { it.content }, bodies.captured.map { it.body })
        assertTrue(File(temporary.root, "book-text/1/text.mz").isFile)
        assertFalse(temporary.root.walkTopDown().any { it.extension == "epub" })
        assertNull(sessions.get(session.id))
        coVerify(exactly = 0) { readium.open(any()) }
    }
}
