package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** Derived reading notes, independent of WholeBook audiobook roles and of the original text. */
@Entity(
    tableName = "chapter_knowledge",
    primaryKeys = ["bookId", "chapterIndex"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")]
)
data class ChapterKnowledgeEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val sourceRevision: String,
    /** Exclusive UTF-16 end of the chapter prefix actually supplied to the model. */
    val sourceEnd: Int,
    val sourceHash: String,
    val modelKey: String,
    val modelLabel: String,
    val promptVersion: Int,
    val contentJson: String,
    val createdAt: Long
)
