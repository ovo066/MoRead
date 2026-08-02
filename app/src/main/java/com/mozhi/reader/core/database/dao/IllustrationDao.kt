package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.IllustrationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IllustrationDao {
    @Query("SELECT * FROM illustrations WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeForBook(bookId: Long): Flow<List<IllustrationEntity>>

    @Query("SELECT * FROM illustrations WHERE id = :id")
    suspend fun get(id: Long): IllustrationEntity?

    @Query("SELECT * FROM illustrations WHERE bookId = :bookId")
    suspend fun getForBook(bookId: Long): List<IllustrationEntity>

    @Insert
    suspend fun insert(illustration: IllustrationEntity): Long

    @Query("DELETE FROM illustrations WHERE id = :id")
    suspend fun delete(id: Long)
}
