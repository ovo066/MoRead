package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mozhi.reader.core.database.entity.ChapterKnowledgeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterKnowledgeDao {
    @Query("SELECT * FROM chapter_knowledge WHERE bookId = :bookId ORDER BY chapterIndex")
    fun observe(bookId: Long): Flow<List<ChapterKnowledgeEntity>>

    @Query("SELECT * FROM chapter_knowledge WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun get(bookId: Long, chapterIndex: Int): ChapterKnowledgeEntity?

    @Upsert
    suspend fun save(entry: ChapterKnowledgeEntity)

    @Query("DELETE FROM chapter_knowledge WHERE bookId = :bookId AND chapterIndex = :chapterIndex")
    suspend fun delete(bookId: Long, chapterIndex: Int)
}
