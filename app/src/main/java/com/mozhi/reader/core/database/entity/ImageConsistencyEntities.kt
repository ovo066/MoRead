package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "book_image_styles", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)])
data class BookImageStyleEntity(@PrimaryKey val bookId: Long, val specJson: String)

/** Global user templates own their snapshots independently of the book they came from. */
@Entity(tableName = "image_style_templates")
data class ImageStyleTemplateEntity(@PrimaryKey val id: String, val name: String, val specJson: String, val updatedAt: Long)

@Entity(tableName = "character_looks", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId", "characterKey", "sinceChapter"], unique = true)])
data class CharacterLookEntity(@PrimaryKey val id: String, val bookId: Long, val characterKey: String,
    val sinceChapter: Int, val specJson: String)

/** One row per chapter. Completed output IDs survive cancellation/restart. */
@Entity(tableName = "illustration_queue", foreignKeys = [ForeignKey(entity = BookEntity::class,
    parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["bookId"])])
data class IllustrationQueueEntity(@PrimaryKey val id: String, val bookId: Long, val chapterIndex: Int,
    val sourceText: String, val recipeJson: String, val status: String = "pending",
    val illustrationId: Long? = null, val error: String = "", val createdAt: Long)
