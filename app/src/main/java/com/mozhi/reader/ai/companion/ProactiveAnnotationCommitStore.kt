package com.mozhi.reader.ai.companion

import androidx.room.withTransaction
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity
import com.mozhi.reader.core.library.AnnotationMedia
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class ProactiveAnnotationCommit(val annotationId: Long, val job: ProactiveAnnotationJobEntity)

/** A short DB-only transaction. All source IO, SHA and DataStore checks happen in the caller. */
internal class ProactiveAnnotationCommitStore(private val database: MoReadDatabase) {
    private class ContextExpired : RuntimeException()

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
