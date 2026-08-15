package com.mozhi.reader.ai.memory

import com.mozhi.reader.core.database.dao.PersonaDao
import com.mozhi.reader.core.vector.MemoryEntry
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 记忆管理页看到的一条记忆。 */
data class StoredMemory(
    val id: Long,
    val summary: String,
    val createdAt: Long,
    val bookId: Long?,
    /** 0 = 本人层；非 0 表示这条出自某个用户面具下的扮演。 */
    val maskId: Long
)

/**
 * 记忆库的读写门面，供角色编辑页的「记忆管理」使用。
 * 向量库调用一律甩到 IO 线程：ObjectBox 是同步 API，主线程上翻几百条会卡住滚动。
 */
@Singleton
class PersonaMemoryRepository @Inject constructor(
    private val vectorStore: BoxStore,
    private val personaDao: PersonaDao
) {
    suspend fun count(personaId: Long): Long = withContext(Dispatchers.IO) {
        runCatching { VectorQueries.countMemories(vectorStore, personaId) }.getOrDefault(0L)
    }

    suspend fun page(personaId: Long, offset: Int, limit: Int): List<StoredMemory> =
        withContext(Dispatchers.IO) {
            runCatching {
                VectorQueries.listMemories(vectorStore, personaId, offset, limit)
                    .map(MemoryEntry::toStored)
            }.getOrDefault(emptyList())
        }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        runCatching { VectorQueries.removeMemory(vectorStore, id) }
        Unit
    }

    /** 清空该角色的记忆与画像：两者都是「它对用户的了解」，只清一半会留下矛盾状态。 */
    suspend fun clear(personaId: Long) = withContext(Dispatchers.IO) {
        runCatching { VectorQueries.removeMemoriesForPersona(vectorStore, personaId) }
        runCatching { personaDao.updateUserProfile(personaId, "") }
        Unit
    }

    suspend fun profile(personaId: Long): String = withContext(Dispatchers.IO) {
        personaDao.getPersona(personaId)?.userProfile.orEmpty()
    }

    suspend fun saveProfile(personaId: Long, profile: String) = withContext(Dispatchers.IO) {
        personaDao.updateUserProfile(personaId, profile.trim().take(UserProfileParser.MAX_PROFILE_CHARS))
    }
}

private fun MemoryEntry.toStored(): StoredMemory = StoredMemory(
    id = id,
    summary = summary.orEmpty(),
    createdAt = createdAt,
    bookId = bookId,
    maskId = maskId
)
