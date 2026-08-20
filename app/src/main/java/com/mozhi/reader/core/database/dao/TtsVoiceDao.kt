package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TtsVoiceDao {
    @Query("SELECT * FROM tts_voices ORDER BY pinned DESC, sortOrder, displayName")
    fun observeVoices(): Flow<List<TtsVoiceEntity>>

    @Query("SELECT * FROM tts_voices ORDER BY pinned DESC, sortOrder, displayName")
    suspend fun getVoices(): List<TtsVoiceEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(voice: TtsVoiceEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(voices: List<TtsVoiceEntity>)

    @Update
    suspend fun update(voice: TtsVoiceEntity)

    @Delete
    suspend fun delete(voice: TtsVoiceEntity)
}
