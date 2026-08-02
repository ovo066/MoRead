package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query(
        "UPDATE notes SET title = :title, contentMarkdown = :contentMarkdown, " +
            "updatedAt = :updatedAt WHERE id = :noteId"
    )
    suspend fun updateContent(noteId: Long, title: String, contentMarkdown: String, updatedAt: Long)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun delete(noteId: Long)

    @Query("SELECT * FROM notes WHERE id = :noteId")
    suspend fun getNote(noteId: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bookId = :bookId ORDER BY updatedAt DESC")
    fun observeForBook(bookId: Long): Flow<List<NoteEntity>>

    @Query("SELECT COUNT(*) FROM notes WHERE bookId = :bookId")
    fun observeCountForBook(bookId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM notes")
    fun observeCount(): Flow<Int>
}
