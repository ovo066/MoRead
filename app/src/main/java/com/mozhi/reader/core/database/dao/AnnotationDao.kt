package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import kotlinx.coroutines.flow.Flow

data class AnnotationReplyCount(
    val annotationId: Long,
    val replyCount: Int
)

@Dao
interface AnnotationDao {
    @Insert
    suspend fun insert(annotation: AnnotationEntity): Long

    @Query("UPDATE annotations SET note = :note, colorTag = :colorTag WHERE id = :annotationId")
    suspend fun updateContent(annotationId: Long, note: String, colorTag: String)

    @Query("UPDATE annotations SET style = :style, colorTag = :colorTag WHERE id = :annotationId")
    suspend fun updateStyle(annotationId: Long, style: String, colorTag: String)

    @Query("UPDATE annotations SET note = :note WHERE id = :annotationId")
    suspend fun updateNote(annotationId: Long, note: String)

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

    @Query(
        "SELECT * FROM annotations WHERE bookId = :bookId " +
            "AND chapterIndex BETWEEN :fromIndex AND :toIndex " +
            "ORDER BY chapterIndex ASC, startCharOffset ASC"
    )
    suspend fun getForChapterRange(
        bookId: Long,
        fromIndex: Int,
        toIndex: Int
    ): List<AnnotationEntity>

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    suspend fun getCountForBook(bookId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM annotations WHERE bookId = :bookId AND chapterIndex = :chapterIndex"
    )
    suspend fun getCountForChapter(bookId: Long, chapterIndex: Int): Int

    @Query("SELECT COUNT(*) FROM annotations WHERE bookId = :bookId")
    fun observeCountForBook(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM annotations")
    fun observeCount(): Flow<Int>

    // ---- 段评讨论串回复层 ----

    @Insert
    suspend fun insertReply(reply: AnnotationReplyEntity): Long

    @Query("SELECT * FROM annotation_replies WHERE annotationId IN (:annotationIds) ORDER BY createdAt ASC, id ASC")
    fun observeReplies(annotationIds: List<Long>): Flow<List<AnnotationReplyEntity>>

    @Query("SELECT * FROM annotation_replies WHERE annotationId = :annotationId ORDER BY createdAt ASC, id ASC")
    suspend fun getReplies(annotationId: Long): List<AnnotationReplyEntity>

    @Query(
        "SELECT annotationId, COUNT(*) AS replyCount FROM annotation_replies " +
            "WHERE annotationId IN (:annotationIds) GROUP BY annotationId"
    )
    suspend fun getReplyCounts(annotationIds: List<Long>): List<AnnotationReplyCount>

    @Query("DELETE FROM annotation_replies WHERE id = :replyId")
    suspend fun deleteReply(replyId: Long)

    /** 有回复的批注 id 集合：纯高亮一旦有讨论也要在正文出「评」标记。 */
    @Query(
        "SELECT DISTINCT annotationId FROM annotation_replies WHERE annotationId IN " +
            "(SELECT id FROM annotations WHERE bookId = :bookId)"
    )
    fun observeRepliedAnnotationIds(bookId: Long): Flow<List<Long>>
}
