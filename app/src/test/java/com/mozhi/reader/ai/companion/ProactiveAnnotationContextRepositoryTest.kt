package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ChapterKnowledgeDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationContextRepositoryTest {
    private val library = mockk<LibraryRepository>()
    private val database = mockk<MoReadDatabase>()
    private val dao = mockk<ChapterKnowledgeDao>()
    private val repository = ProactiveAnnotationContextRepository(library, database)
    private val book = BookEntity(id = 1, title = "测试书", author = "", coverPath = null,
        epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 100)
    private val evidence = "顾衡在雪岭将玉佩交给沈岚，许诺来年相见。"

    init {
        coEvery { library.getBook(1) } returns book
        coEvery { library.bookTextRevision(1) } returns "r"
        every { database.chapterKnowledgeDao() } returns dao
        coEvery { dao.getBefore(1, any()) } returns emptyList()
        coEvery { library.getChapter(1, any()) } answers {
            val index = arg<Int>(1)
            ChapterEntity(bookId = 1, chapterIndex = index, title = "第 $index 章", href = "", charCount = evidence.length)
        }
        coEvery { library.readChapterTextStrict(1, any()) } returns evidence
    }

    @Test fun onlyLoadsEarlierChaptersAndNeverTouchesTheTargetChapterOrItsSuffix() = runTest {
        val prepared = repository.prepare(1, 3)
        val context = prepared.forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(context.background.contains(evidence))
        coVerify(exactly = 1) { library.getChapter(1, 0) }
        coVerify(exactly = 1) { library.getChapter(1, 2) }
        coVerify(exactly = 0) { library.getChapter(1, match { it >= 3 }) }
        coVerify(exactly = 1) { dao.getBefore(1, 3) }
    }

    @Test fun staleChapterCountDoesNotHideKnownEarlierChapters() = runTest {
        coEvery { library.getBook(1) } returns book.copy(totalChapters = 0)
        val context = repository.prepare(1, 3).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(context.background.contains(evidence))
        coVerify(exactly = 1) { library.getChapter(1, 2) }
        coVerify(exactly = 0) { library.getChapter(1, match { it >= 3 }) }
    }

    @Test fun firstChapterHasNoBackgroundAndDoesNotReadTheRestOfTheBook() = runTest {
        val result = repository.prepare(1, 0).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(result.background.isEmpty())
        coVerify(exactly = 0) { library.getChapter(any(), any()) }
        coVerify(exactly = 0) { dao.getBefore(any(), any()) }
    }

    @Test fun oneUnavailableChapterDoesNotDiscardEvidenceFromOtherChapters() = runTest {
        coEvery { library.getChapter(1, 1) } throws IllegalStateException("unavailable")
        val result = repository.prepare(1, 3).forParagraph(evidence, ProactiveAnnotationParagraph(0, evidence.length), 8_000)
        assertTrue(result.background.contains("只覆盖部分正文"))
        assertTrue(result.background.contains(evidence))
    }

    @Test fun sourceRevisionChangeRejectsPreparedSnapshot() = runTest {
        coEvery { library.bookTextRevision(1) } returnsMany listOf("r", "changed")
        try {
            repository.prepare(1, 3)
            fail("changed source must not be used")
        } catch (_: IllegalStateException) { }
    }

    @Test fun cancellationIsNeverConvertedIntoAnEmptyBackground() = runTest {
        coEvery { library.getChapter(1, 0) } throws CancellationException("reader left")
        try {
            repository.prepare(1, 3)
            fail("cancelled")
        } catch (_: CancellationException) { }
    }
}
