package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_character_guides", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookCharacterGuideEntity(
    @PrimaryKey val bookId: Long,
    val generationId: String,
    val sourceRevision: String,
    val modelKey: String,
    val modelLabel: String,
    val promptVersion: Int,
    val contentJson: String,
    val createdAt: Long
)

/** Verified extraction checkpoints. A cancelled run can resume without paying for these parts again. */
@Entity(tableName = "book_character_parts", primaryKeys = ["bookId", "chapterIndex", "start"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")])
data class BookCharacterPartEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val start: Int,
    val end: Int,
    val generationId: String,
    val sourceRevision: String,
    val sourceHash: String,
    val modelKey: String,
    val promptVersion: Int,
    val contentJson: String,
    val createdAt: Long
)
