package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Durable generation identity. Body SHA-256, not reading progress, defines sourceRevision. */
@Entity(
    tableName = "proactive_annotation_jobs",
    indices = [Index(value = ["bookId", "chapterIndex", "personaId", "sourceRevision"], unique = true)]
)
data class ProactiveAnnotationJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val personaId: Long,
    val sourceRevision: String,
    val status: String = "PENDING",
    val attempts: Int = 0,
    val doneParagraphEnds: String = "[]",
    val failureReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
