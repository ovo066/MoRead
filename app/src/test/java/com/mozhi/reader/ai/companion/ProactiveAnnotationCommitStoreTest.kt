package com.mozhi.reader.ai.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.AnnotationMedia
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProactiveAnnotationCommitStoreTest {
    private suspend fun withDatabase(block: suspend (MoReadDatabase, ProactiveAnnotationJobEntity) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            db.bookDao().insertBook(BookEntity(id = 1, title = "书", author = "", coverPath = null,
                epubPath = "", sourceType = BookSourceType.TXT, importedAt = 0, totalChapters = 1))
            val job = ProactiveAnnotationJobEntity(bookId = 1, chapterIndex = 0, personaId = 2,
                sourceRevision = "sha", status = "PENDING", createdAt = 100, updatedAt = 100)
            val id = db.proactiveAnnotationJobDao().insert(job)
            block(db, job.copy(id = id))
        } finally { db.close() }
    }
    private fun row() = AnnotationEntity(bookId = 1, personaId = 2, chapterIndex = 0,
        startCharOffset = 0, endCharOffset = 40, selectedText = "正文", createdAt = 100,
        sourceScopeChapterIndex = 0, sourceScopeCharOffset = 80)
    private fun image() = IllustrationEntity(bookId = 1, prompt = "图", imagePath = "/annotations/image.png", createdAt = 100)

    @Test fun commitsAnnotationImageOriginAndLedgerTogetherAndManualChatDoesNotConsumeCap() = runBlocking {
        withDatabase { db, job ->
            repeat(5) { db.annotationDao().insert(row()) } // user-requested AI: persona+source, but not proactive
            assertEquals(0, db.proactiveAnnotationJobDao().createdSince(0))
            val result = ProactiveAnnotationCommitStore(db).commit(row(), job.copy(doneParagraphEnds = "[80]"), image(), 1, 0) { true }
            assertNotNull(result)
            val saved = db.annotationDao().getAnnotation(result!!.annotationId)!!
            assertEquals(job.id, saved.proactiveJobId)
            assertNotNull(db.illustrationDao().get(AnnotationMedia.decode(saved.mediaJson).illustrationId!!))
            assertEquals("[80]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            assertEquals(1, db.proactiveAnnotationJobDao().createdSince(0))
            assertNull(ProactiveAnnotationCommitStore(db).commit(row(), job.copy(doneParagraphEnds = "[80,160]"), image(), 1, 0) { true })
            assertEquals(6, db.annotationDao().getCountForBook(1))
            assertEquals(1, db.illustrationDao().getForBook(1).size)
            assertEquals("[80]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
        }
    }

    @Test fun lifecycleChangeInsideTransactionRollsBackImageAnnotationAndLedger() = runBlocking {
        withDatabase { db, job ->
            var checks = 0
            val result = ProactiveAnnotationCommitStore(db).commit(row(), job.copy(doneParagraphEnds = "[80]"), image(), 5, 0) {
                ++checks == 1 // enters valid; invalidates after real INSERTs and ledger UPDATE
            }
            assertNull(result)
            assertEquals(2, checks)
            assertEquals(0, db.annotationDao().getCountForBook(1))
            assertTrue(db.illustrationDao().getForBook(1).isEmpty())
            assertEquals("[]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            assertEquals(0, db.proactiveAnnotationJobDao().createdSince(0))
        }
    }

    @Test fun sqlCountsMatchSharedVisibilityIncludingLegacyWhitespaceAndPartialSources() = runBlocking {
        withDatabase { db, _ ->
            val rows = listOf(row(), row().copy(sourceScopeCharOffset = 81), row().copy(personaId = null),
                row().copy(sourceScopeChapterIndex = null),
                row().copy(sourceScopeChapterIndex = null, sourceScopeCharOffset = null),
                row().copy(sourceScopeChapterIndex = null, sourceScopeCharOffset = null, selectedText = "\t\u2003\n"),
                row().copy(chapterIndex = 1, sourceScopeChapterIndex = 1))
            rows.forEach { db.annotationDao().insert(it) }
            val scope = com.mozhi.reader.core.retrieval.ReadingScope.upto(0, 80)
            val counts = db.annotationDao().getVisibleCounts(1, 0, false, 0, 80,
                com.mozhi.reader.core.retrieval.AnnotationVisibility.sqlWhitespace)
            val visible = rows.filter { com.mozhi.reader.core.retrieval.AnnotationVisibility.isVisible(it, scope) }
            assertEquals(visible.size, counts.total)
            assertEquals(visible.count { it.chapterIndex == 0 }, counts.currentChapter)
        }
    }

    @Test fun sqliteFailureCannotPublishImageOrParagraphSuccess() = runBlocking {
        withDatabase { db, job ->
            val error = runCatching {
                ProactiveAnnotationCommitStore(db).commit(row().copy(bookId = 999), job.copy(doneParagraphEnds = "[80]"), image(), 5, 0) { true }
            }.exceptionOrNull()
            assertNotNull(error) // real FK failure after the illustration insert
            assertEquals(0, db.annotationDao().getCountForBook(1))
            assertTrue(db.illustrationDao().getForBook(1).isEmpty())
            assertEquals("[]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
        }
    }

    private fun peer(annotationId: Long) = ProactivePeerNote(annotationId, 8, "照夜", "直言",
        0, 40, "正文", "段评", false, sourceEnd = 80)

    @Test fun replyAndLedgerCommitTogetherWithoutAnotherAnnotationOrMedia() = runBlocking {
        withDatabase { db, job ->
            val parentId = db.annotationDao().insert(row().copy(personaId = 8))
            val result = ProactiveAnnotationCommitStore(db).commitReply(peer(parentId), "接一句", job.copy(doneParagraphEnds = "[80]"), 200) { true }
            assertNotNull(result)
            assertEquals(parentId, result!!.annotationId)
            val reply = db.annotationDao().getReplies(parentId).single()
            assertEquals(job.personaId, reply.personaId)
            assertNull(reply.replyToId)
            assertEquals("接一句", reply.contentMarkdown)
            assertEquals(200L, reply.createdAt)
            assertEquals("{}", reply.mediaJson)
            assertEquals("[80]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            assertEquals(1, db.annotationDao().getCountForBook(1))
            assertTrue(db.illustrationDao().getForBook(1).isEmpty())
            assertEquals(1, db.annotationDao().countPersonaRepliesForChapter(1, 0, 2))
            assertEquals(0, db.annotationDao().countPersonaRepliesForChapter(1, 1, 2))
            assertEquals(0, db.annotationDao().countPersonaRepliesForChapter(2, 0, 2))
        }
    }

    @Test fun deletedParentRejectsReplyWithoutUpdatingLedger() = runBlocking {
        withDatabase { db, job ->
            val id = db.annotationDao().insert(row())
            db.annotationDao().delete(id)
            assertNull(ProactiveAnnotationCommitStore(db).commitReply(peer(id), "接一句", job.copy(doneParagraphEnds = "[80]"), 200) { true })
            assertTrue(db.annotationDao().getReplies(id).isEmpty())
            assertEquals("[]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
        }
    }

    @Test fun existingPersonaReplyWinsEvenIfThePeerSnapshotSaidTheThreadWasEmpty() = runBlocking {
        withDatabase { db, job ->
            val id = db.annotationDao().insert(row())
            val prior = AnnotationReplyEntity(annotationId = id, personaId = 99, contentMarkdown = "先接了", createdAt = 100)
            db.annotationDao().insertReply(prior)
            assertNull(ProactiveAnnotationCommitStore(db).commitReply(peer(id), "迟到", job.copy(doneParagraphEnds = "[80]"), 200) { true })
            assertEquals(listOf("先接了"), db.annotationDao().getReplies(id).map { it.contentMarkdown })
            assertEquals("[]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
        }
    }

    @Test fun userReplyDoesNotOccupyThePersonaReplySlot() = runBlocking {
        withDatabase { db, job ->
            val id = db.annotationDao().insert(row())
            db.annotationDao().insertReply(AnnotationReplyEntity(annotationId = id, personaId = null, contentMarkdown = "用户的话", createdAt = 100))
            assertNotNull(ProactiveAnnotationCommitStore(db).commitReply(peer(id), "角色的话", job.copy(doneParagraphEnds = "[80]"), 200) { true })
            assertEquals(listOf(null, 2L), db.annotationDao().getRepliesForAnnotations(listOf(id)).map { it.personaId })
            assertEquals(1, db.annotationDao().countPersonaRepliesForChapter(1, 0, 2))
        }
    }

    @Test fun lifecycleChangeRollsBackReplyAndLedgerAndAllowsAFreshAttempt() = runBlocking {
        withDatabase { db, job ->
            val id = db.annotationDao().insert(row())
            val store = ProactiveAnnotationCommitStore(db)
            var checks = 0
            assertNull(store.commitReply(peer(id), "旧回复", job.copy(doneParagraphEnds = "[80]"), 200) { ++checks == 1 })
            assertEquals(2, checks)
            assertTrue(db.annotationDao().getReplies(id).isEmpty())
            assertEquals("[]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            assertNotNull(store.commitReply(peer(id), "新回复", job.copy(doneParagraphEnds = "[80]"), 300) { true })
            assertEquals(listOf("新回复"), db.annotationDao().getReplies(id).map { it.contentMarkdown })
        }
    }

    @Test fun skipUpdatesOnlyLedgerAndNeverCountsAsProducedContent() = runBlocking {
        withDatabase { db, job ->
            val store = ProactiveAnnotationCommitStore(db)
            val saved = store.skip(job.copy(doneParagraphEnds = "[40,80]")) { true }
            assertEquals("[40,80]", saved!!.doneParagraphEnds)
            assertEquals("[40,80]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            assertEquals(0, db.annotationDao().countProactiveForJob(job.id))
            assertEquals(0, db.annotationDao().countPersonaRepliesForChapter(1, 0, 2))
            assertEquals(0, db.proactiveAnnotationJobDao().createdSince(0))
            var checks = 0
            assertNull(store.skip(job.copy(doneParagraphEnds = "[40,80,120]")) { ++checks == 1 })
            assertEquals("[40,80]", db.proactiveAnnotationJobDao().find(1, 0, 2, "sha")!!.doneParagraphEnds)
            val result = store.commit(row(), job.copy(doneParagraphEnds = "[40,80,120]"), null, 2, 0) { true }
            assertNotNull(result)
            assertEquals(1, db.annotationDao().countProactiveForJob(job.id))
        }
    }

    @Test fun removedJobCannotLeaveAReplyWithoutItsLedger() = runBlocking {
        withDatabase { db, job ->
            val id = db.annotationDao().insert(row())
            db.proactiveAnnotationJobDao().deleteForBook(1)
            assertNull(ProactiveAnnotationCommitStore(db).commitReply(peer(id), "接一句", job.copy(doneParagraphEnds = "[80]"), 200) { true })
            assertTrue(db.annotationDao().getReplies(id).isEmpty())
        }
    }
}
