package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.mozhi.reader.core.database.entity.ReadingHourlyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingHourlyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(hour: ReadingHourlyEntity)

    @Query("UPDATE reading_hourly SET durationMs = durationMs + :durationMs WHERE bookId = :bookId AND epochDay = :epochDay AND hour = :hour")
    suspend fun addDuration(bookId: Long, epochDay: Long, hour: Int, durationMs: Long)

    @Query("SELECT * FROM reading_hourly WHERE epochDay >= :start AND epochDay < :end ORDER BY epochDay, hour, bookId")
    fun observeBetween(start: Long, end: Long): Flow<List<ReadingHourlyEntity>>

    @Query("SELECT * FROM reading_hourly WHERE bookId = :bookId ORDER BY epochDay, hour")
    suspend fun getForBook(bookId: Long): List<ReadingHourlyEntity>
}
