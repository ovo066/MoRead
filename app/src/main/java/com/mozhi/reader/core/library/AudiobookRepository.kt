package com.mozhi.reader.core.library

import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.AudiobookDao
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import javax.inject.Inject
import javax.inject.Singleton

enum class AudiobookChapterState { NONE, SCRIPTED, CONFIRMED, SYNTHESIZING, READY, STALE }
enum class AudiobookRoleKind { NARRATOR, CHARACTER }
enum class AudiobookEngine { SYSTEM, AI }

@Singleton
class AudiobookRepository @Inject constructor(
    private val database: MoReadDatabase,
    private val dao: AudiobookDao
) {
    fun observeRoles(bookId: Long) = dao.observeRoles(bookId)
    fun observeSegments(bookId: Long, chapterIndex: Int) = dao.observeSegments(bookId, chapterIndex)
    fun observeChapters(bookId: Long) = dao.observeChapters(bookId)

    suspend fun getRoles(bookId: Long) = dao.getRoles(bookId)
    suspend fun getSegments(bookId: Long, chapterIndex: Int) = dao.getSegments(bookId, chapterIndex)
    suspend fun getChapter(bookId: Long, chapterIndex: Int) = dao.getChapter(bookId, chapterIndex)
    suspend fun getChapters(bookId: Long) = dao.getChapters(bookId)
    suspend fun readyChapterCount(bookId: Long) = dao.readyChapterCount(bookId)

    suspend fun replaceRoles(bookId: Long, roles: List<AudiobookRoleEntity>) {
        database.withTransaction {
            val userRoles = dao.getRoles(bookId).filter { it.source == "USER" }
            dao.deleteSegmentsForBook(bookId)
            dao.deleteChaptersForBook(bookId)
            dao.deleteRoles(bookId)
            dao.upsertRoles((roles.filterNot { proposed -> userRoles.any { it.name == proposed.name } } + userRoles))
        }
    }

    suspend fun updateRole(role: AudiobookRoleEntity) {
        database.withTransaction {
            dao.clearAudioForRole(role.id)
            dao.invalidateAudioForRole(role.bookId, role.id)
            dao.updateRole(role.copy(source = "USER"))
        }
    }

    suspend fun addRole(role: AudiobookRoleEntity): AudiobookRoleEntity {
        val id = dao.upsertRoles(listOf(role.copy(id = 0, source = "USER"))).single()
        return role.copy(id = id, source = "USER")
    }

    suspend fun deleteRole(roleId: Long) {
        database.withTransaction {
            val role = dao.getRolesForId(roleId)
            if (role != null) dao.invalidateAudioForRole(role.bookId, roleId)
            dao.clearRoleReferences(roleId)
            dao.deleteRole(roleId)
        }
    }

    suspend fun applyEnginePolicy(bookId: Long, policy: AudiobookEnginePolicy) {
        dao.getRoles(bookId).forEach { role ->
            dao.updateRole(role.copy(engine = policy.engineFor(role.kind).name, source = "USER"))
        }
    }

    suspend fun replaceScript(
        bookId: Long,
        chapterIndex: Int,
        revision: Int,
        segments: List<AudiobookSegmentEntity>
    ) {
        database.withTransaction {
            dao.deleteSegments(bookId, chapterIndex)
            if (segments.isNotEmpty()) dao.upsertSegments(segments)
            dao.upsertChapter(
                AudiobookChapterEntity(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    state = AudiobookChapterState.SCRIPTED.name,
                    scriptedAt = System.currentTimeMillis(),
                    segmentCount = segments.size,
                    readySegmentCount = 0,
                    totalMillis = 0
                )
            )
        }
    }

    suspend fun confirmScript(bookId: Long, chapterIndex: Int) {
        val current = dao.getChapter(bookId, chapterIndex) ?: return
        dao.upsertChapter(
            current.copy(
                state = AudiobookChapterState.CONFIRMED.name,
                confirmedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun updateSegment(segment: AudiobookSegmentEntity) {
        database.withTransaction {
            dao.updateSegment(segment)
            dao.markChapterScripted(segment.bookId, segment.chapterIndex)
        }
    }

    suspend fun updateProducedSegment(segment: AudiobookSegmentEntity) = dao.updateSegment(segment)
    suspend fun updateChapter(chapter: AudiobookChapterEntity) = dao.upsertChapter(chapter)
    suspend fun markStale(bookId: Long, chapterIndex: Int) = dao.markChapterStale(bookId, chapterIndex)
}

enum class AudiobookEnginePolicy {
    ALL_SYSTEM,
    NARRATOR_SYSTEM_CHARACTERS_AI,
    ALL_AI,
    CUSTOM;

    fun engineFor(roleKind: String): AudiobookEngine = when (this) {
        ALL_SYSTEM -> AudiobookEngine.SYSTEM
        NARRATOR_SYSTEM_CHARACTERS_AI -> if (roleKind == AudiobookRoleKind.NARRATOR.name) {
            AudiobookEngine.SYSTEM
        } else {
            AudiobookEngine.AI
        }
        ALL_AI -> AudiobookEngine.AI
        CUSTOM -> AudiobookEngine.AI
    }
}
