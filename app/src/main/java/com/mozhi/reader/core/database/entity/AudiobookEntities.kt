package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "audiobook_roles", indices = [Index("bookId")])
data class AudiobookRoleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val name: String,
    val aliases: String = "",
    val kind: String,
    val gender: String = "UNSPECIFIED",
    val engine: String = "AI",
    val voiceId: String = "",
    val extraJson: String = "",
    val color: String = "",
    val sortOrder: Int = 0,
    val source: String = "AI"
)

@Entity(
    tableName = "audiobook_segments",
    indices = [Index(value = ["bookId", "chapterIndex", "startCharOffset"])]
)
data class AudiobookSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterIndex: Int,
    val startCharOffset: Int,
    val endCharOffset: Int,
    val roleId: Long? = null,
    val emotion: String? = null,
    val instruction: String? = null,
    val audioPath: String? = null,
    val audioMillis: Int = 0,
    val revision: Int = 0
)

@Entity(tableName = "audiobook_chapters", primaryKeys = ["bookId", "chapterIndex"])
data class AudiobookChapterEntity(
    val bookId: Long,
    val chapterIndex: Int,
    val state: String,
    val scriptedAt: Long = 0,
    val confirmedAt: Long = 0,
    val synthesizedAt: Long = 0,
    val segmentCount: Int = 0,
    val readySegmentCount: Int = 0,
    val totalMillis: Long = 0
)
