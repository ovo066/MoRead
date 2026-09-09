package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ProactiveAnnotationJobDao
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.datastore.*
import com.mozhi.reader.core.library.LibraryRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProactiveAnnotationSchedulerWorkerTest {
    private class Ledger : ProactiveAnnotationJobDao {
        val rows = linkedMapOf<Long, ProactiveAnnotationJobEntity>()
        var dailyCount = 0
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
            annotationLimits = ProactiveAnnotationLimits(timing = ProactiveAnnotationTiming.ON_CHAPTER_ENTRY, aheadChapters = 5)))
        val persona = MutableStateFlow<Long?>(3)
        var body = "正文".repeat(40)
        var totalChapters = 20
        val scheduler: ProactiveAnnotationScheduler
        init {
            every { database.proactiveAnnotationJobDao() } returns ledger
            every { settings.companionAutonomySettings } returns autonomy
            every { settings.activePersonaId } returns persona
            coEvery { personas.getPersona(any()) } answers {
                PersonaEntity(id = firstArg(), name = "知墨", personality = "", isRoleplay = false, createdAt = 0)
            }
            coEvery { library.getBook(any()) } answers { BookEntity(id = 1, title = "书", author = "", coverPath = null,
                epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = totalChapters) }
            coEvery { library.getChapters(any()) } answers {
                (0..19).map { ChapterEntity(bookId = firstArg(), chapterIndex = it, title = "", href = "", charCount = 80) }
            }
            coEvery { library.getChapter(any(), any()) } answers {
                ChapterEntity(bookId = firstArg(), chapterIndex = secondArg(), title = "", href = "", charCount = 80)
            }
            coEvery { library.readChapterText(any(), any()) } answers { body }
            coEvery { quota.reserve(any(), any(), any(), any()) } returns ProactiveAnnotationAllowance(true, 2)
            coEvery { quota.recordCreated(any(), any(), any(), any()) } just Runs
            coEvery { service.generateForChapter(any(), any(), any(), any()) } returns ProactiveAnnotationGenerationResult(false, false)
            scheduler = ProactiveAnnotationScheduler(database, library, personas, settings, quota, service, scope)
            scheduler.setReaderBook(1)
        }
    }

    @Test fun singleWorkerIsBoundedAndRepeatedCompletedIdentitiesDoNotRun() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProactiveAnnotationRequest>()
        coEvery { f.service.generateForChapter(capture(requests), any(), any(), any()) } coAnswers {
            gate.await(); ProactiveAnnotationGenerationResult(false, false)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertEquals(1, requests.size)
        repeat(20) { f.scheduler.onChapterEntered(1, it) }; runCurrent()
        assertEquals(1, requests.size)
        gate.complete(Unit); runCurrent()
        assertTrue(requests.size <= 7) // one active plus six pending, never twenty unbounded jobs
        assertEquals(requests.map { it.chapterIndex }.distinct(), requests.map { it.chapterIndex })
        val count = requests.size
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertEquals(count, requests.size)
    }

    @Test fun switchingBooksClearsQueuedWorkButLetsActiveCallFinish() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val requests = mutableListOf<ProactiveAnnotationRequest>()
        coEvery { f.service.generateForChapter(capture(requests), any(), any(), any()) } coAnswers {
            gate.await(); ProactiveAnnotationGenerationResult(false, false)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.setReaderBook(2)
        f.scheduler.onChapterEntered(2, 10); runCurrent()
        assertEquals(1, requests.size)
        gate.complete(Unit); runCurrent()
        assertTrue(requests.drop(1).all { it.bookId == 2L })
        assertEquals(7, requests.size)
    }

    @Test fun oldReaderDisposalDoesNotClearNewBookQueue() = runTest {
        val f = Fixture(backgroundScope)
        val requests = mutableListOf<ProactiveAnnotationRequest>()
        coEvery { f.service.generateForChapter(capture(requests), any(), any(), any()) } returns ProactiveAnnotationGenerationResult(false, false)
        f.scheduler.setReaderBook(2)
        f.scheduler.clearReaderBook(1)
        f.scheduler.onChapterEntered(2, 10); runCurrent()
        assertEquals(6, requests.size)
        assertTrue(requests.all { it.bookId == 2L })
        f.scheduler.clearReaderBook(2)
        f.scheduler.onChapterEntered(2, 0); runCurrent()
        assertEquals(6, requests.size)
    }

    @Test fun masterOffClearsQueueAndRejectsLateCommit() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var accepted: Boolean? = null
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val sink = arg<suspend (AnnotationEntity, Int, IllustrationEntity?) -> Boolean>(3)
            gate.await()
            accepted = sink(AnnotationEntity(bookId = 1, personaId = 3, chapterIndex = 0,
                startCharOffset = 0, endCharOffset = 40, selectedText = "正文", createdAt = 0), 80, null)
            ProactiveAnnotationGenerationResult(false, true)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.autonomy.value = f.autonomy.value.copy(proactiveAnnotationsEnabled = false); runCurrent()
        gate.complete(Unit); runCurrent()
        assertEquals(false, accepted)
        coVerify(exactly = 1) { f.service.generateForChapter(any(), any(), any(), any()) }
        assertEquals("PAUSED", f.ledger.rows.values.single().status)
        assertEquals(0, f.ledger.rows.values.single().attempts)
    }

    @Test fun leaveReaderRejectsLateParagraphAndDoesNotChargeAnnotationQuota() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var accepted: Boolean? = null
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val sink = arg<suspend (AnnotationEntity, Int, IllustrationEntity?) -> Boolean>(3)
            gate.await()
            accepted = sink(AnnotationEntity(bookId = 1, personaId = 3, chapterIndex = 0,
                startCharOffset = 0, endCharOffset = 40, selectedText = "正文", createdAt = 0), 80, null)
            ProactiveAnnotationGenerationResult(false, true)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.clearReaderBook(1)
        gate.complete(Unit); runCurrent()
        assertEquals(false, accepted)
        assertEquals("[]", f.ledger.rows.values.single().doneParagraphEnds)
        coVerify(exactly = 0) { f.quota.recordCreated(any(), any(), any(), any()) }
    }

    @Test fun returningToSameBookCannotAuthorizePreviousReaderRequest() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var accepted: Boolean? = null
        var permit: ProactiveAnnotationAllowance? = ProactiveAnnotationAllowance(true)
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val allowance = arg<suspend () -> ProactiveAnnotationAllowance?>(1)
            val sink = arg<suspend (AnnotationEntity, Int, IllustrationEntity?) -> Boolean>(3)
            gate.await()
            permit = allowance()
            accepted = sink(AnnotationEntity(bookId = 1, personaId = 3, chapterIndex = 0,
                startCharOffset = 0, endCharOffset = 40, selectedText = "正文", createdAt = 0), 80, null)
            ProactiveAnnotationGenerationResult(false, true)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.scheduler.clearReaderBook(1)
        f.scheduler.setReaderBook(1)
        gate.complete(Unit); runCurrent()
        assertNull(permit)
        assertEquals(false, accepted)
        assertEquals("PAUSED", f.ledger.rows.values.single().status)
        assertEquals(0, f.ledger.rows.values.single().attempts)
        coVerify(exactly = 0) { f.quota.recordCreated(any(), any(), any(), any()) }
    }

    @Test fun failurePreservesDurableSuccessfulParagraphsFromPreviousAttempt() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        runCurrent()
        f.ledger.insert(ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 0, personaId = 3,
            sourceRevision = ProactiveAnnotationParagraphs.revision(f.body), status = "FAILED", attempts = 1,
            doneParagraphEnds = "[40]", createdAt = 0, updatedAt = 0))
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            assertEquals(setOf(40), firstArg<ProactiveAnnotationRequest>().doneParagraphEnds)
            error("second paragraph failed")
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertEquals("[40]", f.ledger.rows.values.single().doneParagraphEnds)
        assertEquals(2, f.ledger.rows.values.single().attempts)
        assertEquals("FAILED", f.ledger.rows.values.single().status)
        coVerify(exactly = 0) { f.quota.recordCreated(any(), any(), any(), any()) }
    }

    @Test fun corruptParagraphLedgerFailsClosedInsteadOfRegeneratingPublishedNotes() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        runCurrent()
        f.ledger.insert(ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 0, personaId = 3,
            sourceRevision = ProactiveAnnotationParagraphs.revision(f.body), status = "FAILED", attempts = 1,
            doneParagraphEnds = "corrupt", createdAt = 0, updatedAt = 0))
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertEquals("corrupt", f.ledger.rows.values.single().doneParagraphEnds)
        assertEquals("invalid_paragraph_ledger", f.ledger.rows.values.single().failureReason)
        assertEquals(2, f.ledger.rows.values.single().attempts)
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
    }

    @Test fun failuresRetryAtMostTwiceButPersonaAndSourceChangesGetNewJobs() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        runCurrent()
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } returns ProactiveAnnotationGenerationResult(true, false)
        repeat(3) { f.scheduler.onChapterEntered(1, 0); runCurrent() }
        coVerify(exactly = 2) { f.service.generateForChapter(any(), any(), any(), any()) }
        f.persona.value = 4; runCurrent()
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.body += "新正文"
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertEquals(3, f.ledger.rows.size)
        assertEquals(setOf(3L, 4L), f.ledger.rows.values.map { it.personaId }.toSet())
    }

    @Test fun enablingReplaysRememberedEntryWithoutAnotherReaderEvent() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(proactiveAnnotationsEnabled = false,
            annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        f.scheduler.onChapterEntered(1, 4); runCurrent()
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
        f.autonomy.value = f.autonomy.value.copy(proactiveAnnotationsEnabled = true)
        runCurrent()
        coVerify(exactly = 1) { f.service.generateForChapter(match { it.chapterIndex == 4 }, any(), any(), any()) }
        coVerify(exactly = 0) { f.library.getChapters(any()) }
    }

    @Test fun unrelatedAutonomyNoticeAndOtherBookEditsDoNotCancelOrRetry() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        val gate = CompletableDeferred<Unit>()
        var permitted: Boolean? = null
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val allowance = arg<suspend () -> ProactiveAnnotationAllowance?>(1)
            gate.await()
            permitted = allowance() != null
            ProactiveAnnotationGenerationResult(false, false)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.autonomy.value = f.autonomy.value.copy(voiceRepliesEnabled = true, imageRepliesEnabled = true,
            annotationNotice = ProactiveAnnotationNotice.FAST_MODEL,
            annotationLimitsByBook = mapOf(99L to BookProactiveAnnotationLimits(true, ProactiveAnnotationLimits(dailyMax = 1))))
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(true, permitted)
        assertEquals(0, f.ledger.rows.values.single().attempts)
        coVerify(exactly = 1) { f.service.generateForChapter(any(), any(), any(), any()) }
    }

    @Test fun relevantPolicyChangeRequeuesActiveIdentityWithoutSpendingFailureBudget() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            if (++calls == 1) { gate.await(); ProactiveAnnotationGenerationResult(false, true) }
            else ProactiveAnnotationGenerationResult(false, false)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(maxPerChapter = 3))
        runCurrent(); gate.complete(Unit); runCurrent()
        assertEquals(2, calls)
        assertEquals(1, f.ledger.rows.size)
        assertEquals(0, f.ledger.rows.values.single().attempts)
        assertEquals("DONE", f.ledger.rows.values.single().status)
        // Completed jobs stay complete even when a later setting asks for more paragraphs.
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(maxPerChapter = 4))
        runCurrent()
        assertEquals(2, calls)
    }

    @Test fun exhaustedDailyBudgetDoesNotReadSourceOrChurnPausedLedger() = runTest {
        val f = Fixture(backgroundScope)
        f.autonomy.value = f.autonomy.value.copy(annotationLimits = f.autonomy.value.annotationLimits.copy(aheadChapters = 0))
        runCurrent()
        f.ledger.insert(ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 0, personaId = 3,
            sourceRevision = "existing", status = "PAUSED", attempts = 0, doneParagraphEnds = "[40]", createdAt = 1, updatedAt = 42))
        val previous = f.ledger.rows.toMap()
        coEvery { f.quota.reserve(any(), any(), any(), any()) } returns ProactiveAnnotationAllowance(false)
        repeat(4) { f.scheduler.onChapterEntered(1, 0); runCurrent() }
        assertEquals(previous, f.ledger.rows)
        coVerify(exactly = 0) { f.library.getChapter(any(), any()) }
        coVerify(exactly = 0) { f.library.readChapterText(any(), any()) }
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
        coEvery { f.library.getBook(any()) } returns null
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
    }

    @Test fun durableDailyCountStopsBeforeChapterIoEvenWhenPreferenceAccountingIsBehind() = runTest {
        val f = Fixture(backgroundScope)
        f.ledger.dailyCount = f.autonomy.value.annotationLimits.dailyMax
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        assertTrue(f.ledger.rows.isEmpty())
        coVerify(exactly = 0) { f.library.getChapter(any(), any()) }
        coVerify(exactly = 0) { f.library.readChapterText(any(), any()) }
        coVerify(exactly = 0) { f.service.generateForChapter(any(), any(), any(), any()) }
    }

    @Test fun staleChapterCountStillSchedulesEnteredChapterWithoutInventingAheadChapters() = runTest {
        val f = Fixture(backgroundScope)
        f.totalChapters = 0
        f.scheduler.onChapterEntered(1, 4); runCurrent()
        coVerify(exactly = 1) { f.service.generateForChapter(match { it.chapterIndex == 4 }, any(), any(), any()) }
        assertEquals(listOf(4), f.ledger.rows.values.map { it.chapterIndex })
    }

    @Test fun knownPolicyAbortImmediatelyBeforeMediaChargeDoesNotDebitQuota() = runTest {
        val f = Fixture(backgroundScope)
        val gate = CompletableDeferred<Unit>()
        var chargeError: Throwable? = null
        coEvery { f.service.generateForChapter(any(), any(), any(), any()) } coAnswers {
            val charge = arg<suspend (Int, Int) -> Unit>(2)
            gate.await()
            chargeError = runCatching { charge(1, 0) }.exceptionOrNull()
            ProactiveAnnotationGenerationResult(false, true)
        }
        f.scheduler.onChapterEntered(1, 0); runCurrent()
        f.autonomy.value = f.autonomy.value.copy(proactiveAnnotationsEnabled = false)
        runCurrent(); gate.complete(Unit); runCurrent()
        assertNotNull(chargeError)
        coVerify(exactly = 0) { f.quota.recordCreated(any(), any(), any(), any()) }
        assertEquals(0, f.ledger.rows.values.single().attempts)
    }
}
