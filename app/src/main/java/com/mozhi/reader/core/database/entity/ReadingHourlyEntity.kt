package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Aggregate active reading by local clock hour; never infer hours from old daily totals. */
@Entity(
    tableName = "reading_hourly",
    primaryKeys = ["bookId", "epochDay", "hour"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId"), Index("epochDay")]
)
data class ReadingHourlyEntity(val bookId: Long, val epochDay: Long, val hour: Int, val durationMs: Long)
