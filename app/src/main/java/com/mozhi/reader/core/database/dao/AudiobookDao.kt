package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudiobookDao {
    @Query("SELECT * FROM audiobook_roles WHERE bookId = :bookId ORDER BY sortOrder, id")
    fun observeRoles(bookId: Long): Flow<List<AudiobookRoleEntity>>

    @Query("SELECT * FROM audiobook_roles WHERE bookId = :bookId ORDER BY sortOrder, id")
    suspend fun getRoles(bookId: Long): List<AudiobookRoleEntity>

    @Query("SELECT * FROM audiobook_roles WHERE id = :roleId LIMIT 1")
    suspend fun getRolesForId(roleId: Long): AudiobookRoleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRoles(roles: List<AudiobookRoleEntity>): List<Long>

    @Update
    suspend fun updateRole(role: AudiobookRoleEntity)

    @Query("DELETE FROM audiobook_roles WHERE id = :roleId")
    suspend fun deleteRole(roleId: Long)

    @Query("UPDATE audiobook_segments SET audioPath = NULL, audioMillis = 0 WHERE roleId = :roleId")
    suspend fun clearAudioForRole(roleId: Long)

    @Query(
        "UPDATE audiobook_chapters SET " +
            "state = CASE WHEN state IN ('CONFIRMED', 'SYNTHESIZING', 'READY') THEN 'CONFIRMED' ELSE state END, " +
            "readySegmentCount = 0, totalMillis = 0 " +
            "WHERE bookId = :bookId AND chapterIndex IN " +
            "(SELECT DISTINCT chapterIndex FROM audiobook_segments WHERE roleId = :roleId)"
    )
    suspend fun invalidateAudioForRole(bookId: Long, roleId: Long)

    @Query("UPDATE audiobook_segments SET roleId = NULL, audioPath = NULL, audioMillis = 0 WHERE roleId = :roleId")
    suspend fun clearRoleReferences(roleId: Long)

    @Query("DELETE FROM audiobook_roles WHERE bookId = :bookId")
    suspend fun deleteRoles(bookId: Long)

    @Query(
        "SELECT * FROM audiobook_segments WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startCharOffset, id"
    )
    fun observeSegments(bookId: Long, chapterIndex: Int): Flow<List<AudiobookSegmentEntity>>

    @Query(
        "SELECT * FROM audiobook_segments WHERE bookId = :bookId AND chapterIndex = :chapterIndex " +
            "ORDER BY startCharOffset, id"
    )
    suspend fun getSegments(bookId: Long, chapterIndex: Int): List<AudiobookSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSegments(segments: List<AudiobookSegmentEntity>): List<Long>

    @Update
    suspend fun updateSegment(segment: AudiobookSegmentEntity)

    @Query("DELETE FROM audiobook_segments WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun deleteSegments(bookId: Long, chapterIndex: Int)

    @Query("DELETE FROM audiobook_segments WHERE bookId = :bookId")
    suspend fun deleteSegmentsForBook(bookId: Long)

    @Query("SELECT * FROM audiobook_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observeChapters(bookId: Long): Flow<List<AudiobookChapterEntity>>

    @Query(
        "SELECT * FROM audiobook_chapters WHERE bookId = :bookId AND chapterIndex = :chapterIndex LIMIT 1"
    )
    suspend fun getChapter(bookId: Long, chapterIndex: Int): AudiobookChapterEntity?

    @Query("SELECT * FROM audiobook_chapters WHERE bookId = :bookId ORDER BY chapterIndex")
    suspend fun getChapters(bookId: Long): List<AudiobookChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: AudiobookChapterEntity)

    @Query("DELETE FROM audiobook_chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersForBook(bookId: Long)

    @Query(
        "UPDATE audiobook_chapters SET state = 'SCRIPTED', readySegmentCount = 0, totalMillis = 0 " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex"
    )
    suspend fun markChapterScripted(bookId: Long, chapterIndex: Int)

    @Query(
        "UPDATE audiobook_chapters SET state = 'STALE' " +
            "WHERE bookId = :bookId AND chapterIndex = :chapterIndex"
    )
    suspend fun markChapterStale(bookId: Long, chapterIndex: Int)

    @Query(
        "SELECT COUNT(*) FROM audiobook_chapters WHERE bookId = :bookId AND state = 'READY'"
    )
    suspend fun readyChapterCount(bookId: Long): Int
}
