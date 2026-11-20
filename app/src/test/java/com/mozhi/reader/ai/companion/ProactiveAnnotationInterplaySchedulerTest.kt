package com.mozhi.reader.ai.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProactiveAnnotationInterplaySchedulerTest {
    private class Fixture(val database: MoReadDatabase, scope: CoroutineScope) {
        val body = (0..4).joinToString("\n") { "段落$it：" + "正文内容。".repeat(15) }
        val paragraphs = ProactiveAnnotationParagraphs.split(body)
        val service = mockk<ProactiveAnnotationService>()
        val quota = mockk<ProactiveAnnotationQuota>()
        val autonomy = MutableStateFlow(CompanionAutonomySettings(proactiveAnnotationsEnabled = true,
            annotationPersonaIds = setOf(3, 8), annotationLimits = ProactiveAnnotationLimits(
                maxPerChapter = 2, dailyMax = ProactiveAnnotationLimits.UNLIMITED)))
        val scheduler: ProactiveAnnotationScheduler

        init {
            val library = mockk<LibraryRepository>()
            coEvery { library.getBook(1) } returns book()
            coEvery { library.getChapter(1, 0) } returns ChapterEntity(bookId = 1, chapterIndex = 0, title = "", href = "", charCount = body.length)
            coEvery { library.readChapterText(1, any()) } returns body
            val personas = mockk<PersonaRepository>()
            coEvery { personas.getPersona(any()) } answers {
                val id = firstArg<Long>()
                if (id == 8L) null else PersonaEntity(id = id, name = "角色$id", personality = "", isRoleplay = false, createdAt = 0)
            }
            val settings = mockk<ReaderSettingsRepository>()
            every { settings.activePersonaId } returns MutableStateFlow(3L)
            every { settings.companionAutonomySettings } returns autonomy
            coEvery { quota.reserve(any(), any(), any(), any()) } returns ProactiveAnnotationAllowance(true, 100)
            coEvery { quota.recordCreated(any(), any(), any(), any()) } just Runs
            scheduler = ProactiveAnnotationScheduler(database, library, personas, settings, quota, service, scope)
            scheduler.setReaderBook(1)
        }

        fun row(paragraph: ProactiveAnnotationParagraph) = AnnotationEntity(bookId = 1, personaId = 3, chapterIndex = 0,
            startCharOffset = paragraph.start, endCharOffset = paragraph.end, selectedText = body.substring(paragraph.start, paragraph.end),
            note = "段评", sourceScopeChapterIndex = 0, sourceScopeCharOffset = paragraph.end, createdAt = System.currentTimeMillis())

        suspend fun completedJob(): ProactiveAnnotationJobEntity = withTimeout(10_000) {
            var job: ProactiveAnnotationJobEntity?
            do {
                job = database.proactiveAnnotationJobDao().find(1, 0, 3, ProactiveAnnotationParagraphs.revision(body))
                if (job?.status != "DONE") delay(10)
            } while (job?.status != "DONE")
            requireNotNull(job)
        }

        companion object {
            fun book() = BookEntity(id = 1, title = "书", author = "", coverPath = null, epubPath = "",
                sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture, CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            database.bookDao().insertBook(Fixture.book())
            block(Fixture(database, scope), scope)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            database.close()
        }
    }

    @Test fun replyUsesParentNoticeIdAndSharesChapterCapWithOwnNotesButNotSkippedEnds() = runBlocking {
        withFixture { f, scope ->
            val replyTarget = f.paragraphs[3]
            val ownTarget = f.paragraphs[4]
            val parentId = f.database.annotationDao().insert(f.row(replyTarget).copy(personaId = 9, proactiveJobId = 99))
            val notice = CompletableDeferred<ProactiveAnnotationBatchResult>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { f.scheduler.results.collect { notice.complete(it) } }
            // 即便偏好计数保存失败，已经提交的回复和段评仍然要回执、限额并发出查看入口。
            coEvery { f.quota.recordCreated(any(), any(), any(), any()) } throws IllegalStateException("quota unavailable")
            coEvery { f.service.generateForChapter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val request = firstArg<ProactiveAnnotationRequest>()
                val permit = arg<suspend () -> ProactiveAnnotationAllowance?>(1)
                val commit = arg<suspend (AnnotationEntity, Int, IllustrationEntity?) -> Boolean>(3)
                val reply = arg<suspend (ProactivePeerNote, String, Int) -> Boolean>(5)
                val skip = arg<suspend (Set<Int>) -> Unit>(6)
                skip(f.paragraphs.take(3).map { it.end }.toSet())
                assertNotNull(permit())
                val peer = request.peers.single()
                assertEquals(parentId, peer.annotationId)
                assertTrue(reply(peer, "接一句", replyTarget.end))
                assertFalse(reply(peer, "重复接话", replyTarget.end))
                assertNotNull(permit())
                assertTrue(commit(f.row(ownTarget), ownTarget.end, null))
                assertNull(permit())
                ProactiveAnnotationGenerationResult(false, false)
            }
            f.scheduler.onChapterCompleted(1, 0)
            val job = f.completedJob()
            val result = withTimeout(10_000) { notice.await() }
            assertEquals(0, job.attempts)
            assertEquals(f.paragraphs.map { it.end }.toSet(), Json.decodeFromString<List<Int>>(job.doneParagraphEnds).toSet())
            assertEquals(2, result.createdCount)
            assertEquals(parentId, result.annotationIds.first())
            assertEquals(2, result.annotationIds.distinct().size)
            assertEquals(2, f.database.annotationDao().getCountForBook(1))
            assertEquals(1, f.database.annotationDao().countProactiveForJob(job.id))
            assertEquals(1, f.database.annotationDao().countPersonaRepliesForChapter(1, 0, 3))
            coVerify(exactly = 2) { f.quota.recordCreated(1, 0, 0, any()) }
            f.scheduler.onChapterCompleted(1, 0)
            delay(100)
            coVerify(exactly = 1) { f.service.generateForChapter(any(), any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test fun allSkippedDoneJobStaysDoneOnReentryWithoutQuotaOrCompletionNotice() = runBlocking {
        withFixture { f, scope ->
            val notice = CompletableDeferred<ProactiveAnnotationBatchResult>()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { f.scheduler.results.collect { notice.complete(it) } }
            coEvery { f.service.generateForChapter(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<suspend (Set<Int>) -> Unit>(6)(f.paragraphs.map { it.end }.toSet())
                ProactiveAnnotationGenerationResult(false, false)
            }
            f.scheduler.onChapterCompleted(1, 0)
            val job = f.completedJob()
            assertEquals(0, job.attempts)
            assertEquals(0, f.database.annotationDao().countProactiveForJob(job.id))
            assertEquals(f.paragraphs.map { it.end }.toSet(), Json.decodeFromString<List<Int>>(job.doneParagraphEnds).toSet())
            f.scheduler.onChapterCompleted(1, 0)
            delay(100)
            coVerify(exactly = 1) { f.service.generateForChapter(any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { f.quota.recordCreated(any(), any(), any(), any()) }
            assertFalse(notice.isCompleted)
        }
    }
}
