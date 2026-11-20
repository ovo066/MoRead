package com.mozhi.reader.ai.companion

import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.library.AnnotationMedia
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class ProactiveAnnotationCommit(val annotationId: Long, val job: ProactiveAnnotationJobEntity)

/** A short DB-only transaction. All source IO, SHA and DataStore checks happen in the caller. */
internal class ProactiveAnnotationCommitStore(private val database: MoReadDatabase) {
    private class ContextExpired : RuntimeException()

    /** 接话和段落账本一起落库，事务内重查父段评与角色回复，防止生成期间被删除或被抢先接话。 */
    suspend fun commitReply(
        peer: ProactivePeerNote,
        text: String,
        updatedJob: ProactiveAnnotationJobEntity,
        now: Long,
        contextValid: () -> Boolean
    ): ProactiveAnnotationCommit? = withContext(NonCancellable) {
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                val dao = database.annotationDao()
                val parent = dao.getAnnotation(peer.annotationId) ?: return@withTransaction null
                if (parent.bookId != updatedJob.bookId || parent.chapterIndex != updatedJob.chapterIndex ||
                    dao.getReplies(peer.annotationId).any { it.personaId != null }) return@withTransaction null
                val jobs = database.proactiveAnnotationJobDao()
                if (jobs.find(updatedJob.bookId, updatedJob.chapterIndex, updatedJob.personaId,
                        updatedJob.sourceRevision)?.id != updatedJob.id) return@withTransaction null
                dao.insertReply(AnnotationReplyEntity(
                    annotationId = peer.annotationId, personaId = updatedJob.personaId, replyToId = null,
                    contentMarkdown = text, createdAt = now
                ))
                jobs.update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                ProactiveAnnotationCommit(peer.annotationId, updatedJob)
            }
        } catch (_: ContextExpired) { null }
    }

    /** 跳过也要拿到取消安全的落库回执，内存账本才不会与数据库分叉。 */
    suspend fun skip(
        updatedJob: ProactiveAnnotationJobEntity, contextValid: () -> Boolean
    ): ProactiveAnnotationJobEntity? = withContext(NonCancellable) {
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                val jobs = database.proactiveAnnotationJobDao()
                if (jobs.find(updatedJob.bookId, updatedJob.chapterIndex, updatedJob.personaId,
                        updatedJob.sourceRevision)?.id != updatedJob.id) return@withTransaction null
                jobs.update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                updatedJob
            }
        } catch (_: ContextExpired) { null }
    }

    /**
     * null/throw ALWAYS means no commit; a returned ID ALWAYS means committed.
     * NonCancellable spans just SQLite so cancellation cannot lose the commit acknowledgement
     * and make the service delete an image that is already referenced by a published row.
     */
    suspend fun commit(
        row: AnnotationEntity,
        updatedJob: ProactiveAnnotationJobEntity,
        illustration: IllustrationEntity?,
        dailyCap: Int,
        startOfDay: Long,
        contextValid: () -> Boolean
    ): ProactiveAnnotationCommit? = withContext(NonCancellable) {
        val media = AnnotationMedia.decode(row.mediaJson)
        try {
            database.withTransaction {
                if (!contextValid()) throw ContextExpired()
                if (database.proactiveAnnotationJobDao().createdSince(startOfDay) >= dailyCap) return@withTransaction null
                val imageId = illustration?.let { database.illustrationDao().insert(it) }
                val id = database.annotationDao().insert(row.copy(
                    proactiveJobId = updatedJob.id, mediaJson = media.copy(illustrationId = imageId).encode()))
                database.proactiveAnnotationJobDao().update(updatedJob)
                if (!contextValid()) throw ContextExpired()
                ProactiveAnnotationCommit(id, updatedJob)
            }
        } catch (_: ContextExpired) { null }
    }
}
