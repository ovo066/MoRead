package com.mozhi.reader.ai.media

import android.app.Application
import androidx.room.Room
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IllustrationQueueTest {
    private lateinit var db: MoReadDatabase
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val library = mockk<LibraryRepository>()
    private val media = mockk<AiMediaGenerationService>()
    private val recipes = mockk<ImageConsistencyRepository>()
    private lateinit var queue: IllustrationQueue
    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), MoReadDatabase::class.java).build()
        val book = BookEntity(1, "Book", "", null, "", BookSourceType.TXT, 1, totalChapters = 20, maxReachedChapterIndex = 19, maxReachedCharOffset = 100)
        db.bookDao().insertBook(book)
        coEvery { library.getBook(1) } returns book
        coEvery { library.getChapter(1, any()) } answers { ChapterEntity(bookId = 1, chapterIndex = arg(1), title = "chapter", href = "", charCount = 6) }
        coEvery { library.readChapterText(1, any()) } returns "source"
        queue = IllustrationQueue(db, library, recipes, media, scope)
    }
    @After fun close() { scope.cancel(); db.close() }
    private fun row(index: Int, status: String = "pending") = IllustrationQueueEntity("job-$index", 1, index, "source",
        ImageRecipe(shot = ShotSpec(action = "scene $index")).encode(), status, createdAt = 1)
    private suspend fun done() { withTimeout(10_000) { queue.running.first { it.isEmpty() } } }

    @Test fun pauseFinishesPaidRequestAndResumeSkipsCompletedChapters() = runBlocking {
        var calls = 0
        coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            if (calls == 1) queue.pause(1)
            IllustrationEntity(bookId = 1, chapterIndex = arg(1), prompt = "prompt", imagePath = "candidate-$calls.png", createdAt = 1)
        }
        queue.enqueue(listOf(row(0), row(1)))
        queue.start(1); done()
        assertEquals(listOf("done", "pending"), db.imageConsistencyDao().queue(1).map { it.status })
        assertEquals(1, db.illustrationDao().getForBook(1).size)
        queue.start(1); done()
        assertEquals(2, calls)
        assertTrue(db.imageConsistencyDao().queue(1).all { it.status == "done" && it.illustrationId != null })
    }
    @Test fun restartDoesNotAutomaticallyReplayUnknownPaidRequestsAndFailureIsIndividuallyRetryable() = runBlocking {
        var calls = 0
        coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            calls++
            if (calls == 1) error("network failed")
            IllustrationEntity(bookId = 1, prompt = "prompt", imagePath = "image.png", createdAt = 1)
        }
        db.imageConsistencyDao().saveQueue(listOf(row(0, "running"), row(1)))
        queue.start(1); done()
        assertEquals(1, calls)
        assertEquals(listOf("running", "failed"), db.imageConsistencyDao().queue(1).map { it.status })
        val failed = db.imageConsistencyDao().queue(1).last()
        db.imageConsistencyDao().saveQueue(row(2))
        queue.retry(failed); done()
        assertEquals(2, calls)
        assertEquals(listOf("running", "done", "pending"), db.imageConsistencyDao().queue(1).map { it.status })
    }

    @Test fun twentyChapterBatchResumesAndRetriesOnlyTheFailedChapter() = runBlocking {
        val calls = mutableListOf<Int>()
        var failedOnce = false
        coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val chapter = arg<Int>(1)
            calls += chapter
            if (chapter == 7 && !failedOnce) { failedOnce = true; error("temporary network failure") }
            if (chapter == 9 && calls.count { it == 9 } == 1) queue.pause(1)
            IllustrationEntity(bookId = 1, chapterIndex = chapter, prompt = "prompt", imagePath = "chapter-$chapter.png", createdAt = 1)
        }
        queue.enqueue((0 until 20).map { row(it) })
        queue.start(1); done()
        assertEquals(9, db.illustrationDao().getForBook(1).size)
        queue.start(1); done()
        assertEquals(19, db.illustrationDao().getForBook(1).size)
        queue.retry(db.imageConsistencyDao().queue(1).single { it.status == "failed" }); done()
        assertEquals(20, db.illustrationDao().getForBook(1).size)
        assertEquals(21, calls.size)
        assertEquals(2, calls.count { it == 7 })
        assertTrue((0 until 20).filter { it != 7 }.all { chapter -> calls.count { it == chapter } == 1 })
        assertTrue(db.imageConsistencyDao().queue(1).all { it.status == "done" })
    }
}
