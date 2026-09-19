package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ProactiveAnnotationJobDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProactiveAnnotationTimingSwitchTest {
    @Test fun switchingFromAfterCompleteToAheadMidBookKeepsGenerating() = runTest {
        val f = Fixture(backgroundScope)
        val requests = mutableListOf<ProactiveAnnotationRequest>()
        coEvery { f.service.generateForChapter(capture(requests), any(), any(), any()) } returns ProactiveAnnotationGenerationResult(false, false)
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.onChapterCompleted(1, 0); f.scheduler.onChapterEntered(1, 1); runCurrent()
        f.scheduler.onChapterCompleted(1, 1); f.scheduler.onChapterEntered(1, 2); runCurrent()
        assertEquals(listOf(0, 1), requests.map { it.chapterIndex })
        // Leave the reader, switch to "ahead two chapters", come back.
        f.scheduler.clearReaderBook(1)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(
            timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY, aheadChapters = 2))
        runCurrent()
        f.scheduler.setReaderBook(1)
        f.scheduler.onChapterEntered(1, 2); runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4), requests.map { it.chapterIndex })
        f.scheduler.onChapterCompleted(1, 2); f.scheduler.onChapterEntered(1, 3); runCurrent()
        f.scheduler.onChapterCompleted(1, 3); f.scheduler.onChapterEntered(1, 4); runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), requests.map { it.chapterIndex })
    }

    @Test fun switchingWhileReaderVisibleKeepsGenerating() = runTest {
        val f = Fixture(backgroundScope)
        val requests = mutableListOf<ProactiveAnnotationRequest>()
        coEvery { f.service.generateForChapter(capture(requests), any(), any(), any()) } returns ProactiveAnnotationGenerationResult(false, false)
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.onChapterCompleted(1, 0); f.scheduler.onChapterEntered(1, 1); runCurrent()
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(
            timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY, aheadChapters = 2))
        runCurrent()
        assertEquals(listOf(0, 1, 2, 3), requests.map { it.chapterIndex })
        f.scheduler.onChapterCompleted(1, 1); f.scheduler.onChapterEntered(1, 2); runCurrent()
        assertEquals(listOf(0, 1, 2, 3, 4), requests.map { it.chapterIndex })
    }

    @Test fun exhaustedDailyBudgetIsAnnouncedOncePerBookPerDay() = runTest {
        val f = Fixture(backgroundScope)
        val notices = mutableListOf<ProactiveAnnotationBatchResult>()
        backgroundScope.launch { f.scheduler.results.collect { notices += it } }
        f.ledger.dailyCount = f.autonomy.value.annotationLimits.dailyMax
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.onChapterCompleted(1, 0); f.scheduler.onChapterEntered(1, 1); runCurrent()
        assertEquals(1, notices.size)
        assertEquals(true, notices.single().dailyBudgetExhausted)
        assertEquals(0, notices.single().createdCount)
        assertEquals("知墨", notices.single().personaName)
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
        coVerify(exactly = 0) { f.library.readChapterText(any(), any()) }
    }

    @Test fun aCancelledJobDoesNotSilenceTheWorkerForTheRestOfTheSession() = runTest {
        val f = Fixture(backgroundScope)
        val chapters = mutableListOf<Int>()
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<ProactiveAnnotationRequest>().chapterIndex
            chapters += chapter
            if (chapter == 0) throw kotlinx.coroutines.CancellationException("request timed out")
            ProactiveAnnotationGenerationResult(false, false)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.onChapterCompleted(1, 0); f.scheduler.onChapterEntered(1, 1); runCurrent()
        f.scheduler.onChapterCompleted(1, 1); f.scheduler.onChapterEntered(1, 2); runCurrent()
        f.scheduler.onChapterCompleted(1, 2); runCurrent()
        // 第 0 章的取消只该废掉它自己：之后每一章都得照常生成。
        assertEquals(listOf(0, 1, 2), chapters)
    }

    private class Ledger : ProactiveAnnotationJobDao {
        val rows = linkedMapOf<Long, ProactiveAnnotationJobEntity>()
        var dailyCount = 0
        override suspend fun deleteForBook(bookId: Long) { rows.entries.removeAll { it.value.bookId == bookId } }
        override suspend fun find(bookId: Long, chapterIndex: Int, personaId: Long, revision: String) =
            rows.values.firstOrNull { it.bookId == bookId && it.chapterIndex == chapterIndex && it.personaId == personaId && it.sourceRevision == revision }
        override suspend fun insert(job: ProactiveAnnotationJobEntity): Long {
            if (find(job.bookId, job.chapterIndex, job.personaId, job.sourceRevision) != null) return -1
            val id = rows.size + 1L; rows[id] = job.copy(id = id); return id
        }
        override suspend fun update(job: ProactiveAnnotationJobEntity) { rows[job.id] = job }
        override suspend fun createdSince(since: Long) = dailyCount
    }

    private class Fixture(scope: kotlinx.coroutines.CoroutineScope) {
        val ledger = Ledger()
        val database = mockk<MoReadDatabase>()
        val library = mockk<LibraryRepository>()
        val personas = mockk<PersonaRepository>()
        val settings = mockk<ReaderSettingsRepository>()
        val quota = mockk<ProactiveAnnotationQuota>()
        val service = mockk<ProactiveAnnotationService>()
        val autonomy = MutableStateFlow(CompanionAutonomySettings(proactiveAnnotationsEnabled = true,
            annotationLimits = ProactiveAnnotationLimits(timing = ProactiveAnnotationTiming.AFTER_CHAPTER_COMPLETE)))
        val persona = MutableStateFlow<Long?>(3)
        val scheduler: ProactiveAnnotationScheduler
        init {
            every { database.proactiveAnnotationJobDao() } returns ledger
            every { settings.companionAutonomySettings } returns autonomy
            every { settings.activePersonaId } returns persona
            coEvery { personas.getPersona(any()) } answers {
                PersonaEntity(id = firstArg(), name = "知墨", personality = "", isRoleplay = false, createdAt = 0)
            }
            coEvery { library.getBook(any()) } answers { BookEntity(id = 1, title = "书", author = "", coverPath = null,
                epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 20) }
            coEvery { library.getChapter(any(), any()) } answers {
                ChapterEntity(bookId = firstArg(), chapterIndex = secondArg(), title = "", href = "", charCount = 80)
            }
            coEvery { library.readChapterText(any(), any()) } answers { "第" + secondArg<ChapterEntity>().chapterIndex + "章正文".repeat(40) }
            coEvery { quota.reserve(any(), any(), any(), any()) } returns ProactiveAnnotationAllowance(true, 2)
            coEvery { quota.recordCreated(any(), any(), any(), any()) } just Runs
            scheduler = ProactiveAnnotationScheduler(database, library, personas, settings, quota, service, scope)
            scheduler.setReaderBook(1)
        }
    }
}
