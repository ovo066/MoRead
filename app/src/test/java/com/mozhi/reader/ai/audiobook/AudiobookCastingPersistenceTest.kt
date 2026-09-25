package com.mozhi.reader.ai.audiobook

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.core.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AudiobookCastingPersistenceTest {
    @Test fun rediscoveryKeepsManualCastingStableIdsAndProducedScripts() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).build()
        try {
            val dao = db.audiobookDao()
            val repo = AudiobookRepository(db, dao)
            val role = repo.addRole(AudiobookRoleEntity(bookId = 1, name = "苏晚", aliases = "晚晚", kind = "CHARACTER", voiceId = "manual"))
            dao.upsertSegments(listOf(AudiobookSegmentEntity(bookId = 1, chapterIndex = 0, startCharOffset = 0, endCharOffset = 8,
                roleId = role.id, audioPath = "existing.wav", audioMillis = 1200)))
            dao.upsertChapter(AudiobookChapterEntity(1, 0, "READY"))
            repo.replaceRoles(1, listOf(role.copy(id = 0, name = "晚晚", voiceId = "ai-proposed", source = "AI"),
                AudiobookRoleEntity(bookId = 1, name = "林舟", kind = "CHARACTER")))
            assertEquals(2, repo.getRoles(1).size)
            assertEquals("manual", repo.getRoles(1).first { it.id == role.id }.voiceId)
            assertEquals("existing.wav", repo.getSegments(1, 0).single().audioPath)
            assertEquals("READY", repo.getChapter(1, 0)?.state)
            repo.applyEnginePolicy(1, AudiobookEnginePolicy.ALL_SYSTEM)
            assertEquals("", repo.getRoles(1).first { it.id == role.id }.voiceId)
            assertNull(repo.getSegments(1, 0).single().audioPath)
            assertEquals("CONFIRMED", repo.getChapter(1, 0)?.state)
            assertEquals(role.id, repo.getSegments(1, 0).single().roleId)
        } finally { db.close() }
    }
}
