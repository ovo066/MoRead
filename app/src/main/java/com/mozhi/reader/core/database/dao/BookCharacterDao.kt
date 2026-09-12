package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.mozhi.reader.core.database.entity.BookCharacterGuideEntity
import com.mozhi.reader.core.database.entity.BookCharacterPartEntity
import kotlinx.coroutines.flow.Flow

data class BookCharacterCheckpoint(
    val generationId: String, val sourceRevision: String, val modelKey: String,
    val promptVersion: Int, val completedParts: Int
)

@Dao
interface BookCharacterDao {
    @Query("SELECT * FROM book_character_guides WHERE bookId = :bookId")
    fun observeGuide(bookId: Long): Flow<BookCharacterGuideEntity?>
    @Query("SELECT * FROM book_character_guides WHERE bookId = :bookId")
    suspend fun getGuide(bookId: Long): BookCharacterGuideEntity?
    @Query("SELECT * FROM book_character_parts WHERE bookId = :bookId ORDER BY chapterIndex, start")
    suspend fun getParts(bookId: Long): List<BookCharacterPartEntity>
    @Query("SELECT * FROM book_character_parts WHERE bookId = :bookId AND chapterIndex = :chapterIndex ORDER BY start")
    suspend fun getChapterParts(bookId: Long, chapterIndex: Int): List<BookCharacterPartEntity>
    @Query("SELECT generationId, sourceRevision, modelKey, promptVersion, COUNT(*) AS completedParts FROM book_character_parts WHERE bookId = :bookId GROUP BY generationId, sourceRevision, modelKey, promptVersion ORDER BY MAX(createdAt) DESC LIMIT 1")
    suspend fun getCheckpoint(bookId: Long): BookCharacterCheckpoint?
    @Query("SELECT generationId, sourceRevision, modelKey, promptVersion, COUNT(*) AS completedParts FROM book_character_parts WHERE bookId = :bookId GROUP BY generationId, sourceRevision, modelKey, promptVersion ORDER BY MAX(createdAt) DESC LIMIT 1")
    fun observeCheckpoint(bookId: Long): Flow<BookCharacterCheckpoint?>
    @Upsert suspend fun saveGuide(guide: BookCharacterGuideEntity)
    @Upsert suspend fun savePart(part: BookCharacterPartEntity)
    @Query("DELETE FROM book_character_parts WHERE bookId = :bookId")
    suspend fun deleteParts(bookId: Long)
    @Query("DELETE FROM book_character_guides WHERE bookId = :bookId")
    suspend fun deleteGuide(bookId: Long)
}
