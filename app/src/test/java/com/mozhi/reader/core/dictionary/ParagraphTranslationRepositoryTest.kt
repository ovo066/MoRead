package com.mozhi.reader.core.dictionary

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ParagraphTranslationRepositoryTest {
    private val body = "A reader opens a book.\nThe window is open."
    private val records = englishParagraphs(body).map { ParagraphTranslation(it.start, it.end, it.key, "译文：${it.start}") }

    @Test fun deletingOneParagraphSurvivesReopenAndPreservesOtherBooksAndChapters() = runTest {
        val app = RuntimeEnvironment.getApplication()
        val repo = ParagraphTranslationRepository(app)
        records.forEach { repo.save(1, 0, body, it) }
        repo.save(1, 1, body, records.first())
        repo.save(2, 0, body, records.first())
        assertEquals(listOf(records.last()), repo.delete(1, 0, body, records.first()))
        val reopened = ParagraphTranslationRepository(app)
        assertEquals(listOf(records.last()), reopened.load(1, 0, body))
        assertEquals(listOf(records.first()), reopened.load(1, 1, body))
        assertEquals(listOf(records.first()), reopened.load(2, 0, body))
        assertTrue(reopened.delete(1, 0, body, records.last()).isEmpty())
        assertTrue(ParagraphTranslationRepository(app).load(1, 0, body).isEmpty())
    }

    @Test fun replacementUpdatesOneCachedParagraphAndStaleDeletionCannotRemoveIt() = runTest {
        val repo = ParagraphTranslationRepository(RuntimeEnvironment.getApplication())
        records.forEach { repo.save(1, 0, body, it) }
        val replacement = records.first().copy(chinese = "新的译文")
        repo.save(1, 0, body, replacement)
        assertEquals(setOf(replacement, records.last()), repo.load(1, 0, body).toSet())
        try {
            repo.delete(1, 0, body, records.first().copy(sourceKey = "outdated source"))
            fail("Stale deletion must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(setOf(replacement, records.last()), repo.load(1, 0, body).toSet())
    }
}
