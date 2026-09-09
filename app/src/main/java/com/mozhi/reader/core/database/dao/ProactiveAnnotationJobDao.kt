package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.ProactiveAnnotationJobEntity

@Dao
interface ProactiveAnnotationJobDao {
    @Query("SELECT * FROM proactive_annotation_jobs WHERE bookId = :bookId AND chapterIndex = :chapterIndex AND personaId = :personaId AND sourceRevision = :revision LIMIT 1")
    suspend fun find(bookId: Long, chapterIndex: Int, personaId: Long, revision: String): ProactiveAnnotationJobEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(job: ProactiveAnnotationJobEntity): Long

    @Query("SELECT COUNT(*) FROM annotations WHERE proactiveJobId IS NOT NULL AND createdAt >= :since")
    suspend fun createdSince(since: Long): Int

    @Update
    suspend fun update(job: ProactiveAnnotationJobEntity)
}
