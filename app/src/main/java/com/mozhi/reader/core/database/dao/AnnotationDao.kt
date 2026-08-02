package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.AnnotationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {
    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET note = :note, colorTag = :colorTag WHERE id = :annotationId")
    suspend fun updateContent(annotationId: Long, note: String, colorTag: String)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun delete(annotationId: Long)

    @Query("SELECT * FROM annotations WHERE id = :annotationId")
    suspend fun getAnnotation(annotationId: Long): AnnotationEntity?

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId " +
            "ORDER BY chapterIndex ASC, startCharOffset ASC"
    )
    fun observeForBook(bookId: Long): Flow<List<AnnotationEntity>>

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startCharOffset ASC"
    )
    suspend fun getForChapter(bookId: Long, chapterIndex: Int): List<AnnotationEntity>

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    fun observeCountForBook(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM annotations")
    fun observeCount(): Flow<Int>
}
