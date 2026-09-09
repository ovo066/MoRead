package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationSchedulerTest {
    private val job = ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 2, personaId = 3,
        sourceRevision = "hash", createdAt = 0, updatedAt = 0, attempts = 1, status = "FAILED")
    @Test fun chapterRangeIsBoundedToSixAndBookEnd() {
        assertEquals((7..12).toList(), annotationChapterRange(7, 999, 99).toList())
        assertEquals(listOf(7), annotationChapterRange(7, -1, 99).toList())
        assertEquals(listOf(7, 8), annotationChapterRange(7, 5, 8).toList())
        assertTrue(annotationChapterRange(9, 5, 8).isEmpty())
    }
    @Test fun durableSuccessNeverRetriesAndFailureHasTwoAttemptCeiling() {
        assertTrue(job.canAttempt(10))
        assertFalse(job.copy(attempts = 2).canAttempt(10))
        assertFalse(job.copy(status = "DONE").canAttempt(Long.MAX_VALUE))
    }
    @Test fun interruptedPendingWaitsTenMinutes() {
        assertFalse(job.copy(status = "PENDING").canAttempt(599_999))
        assertTrue(job.copy(status = "PENDING").canAttempt(600_000))
    }
    @Test fun personaAndSourceArePartOfDurableIdentity() {
        assertNotEquals(job, job.copy(personaId = 4))
        assertNotEquals(job, job.copy(sourceRevision = "different"))
    }
}
