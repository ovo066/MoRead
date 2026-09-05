package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "shelf_groups",
    indices = [Index(value = ["parentId", "name"], unique = true), Index("parentId")]
)
data class ShelfGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val parentId: Long? = null,
    val sortOrder: Int = 0,
    val createdAt: Long
)

@Entity(tableName = "book_collections")
data class BookCollectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long
)

@Entity(tableName = "book_tags", indices = [Index(value = ["name"], unique = true)])
data class BookTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorTag: String,
    val groupName: String = "",
    val sortOrder: Int = 0,
    val createdAt: Long
)

@Entity(
    tableName = "book_tag_refs",
    primaryKeys = ["bookId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = BookTagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class BookTagRefEntity(
    val bookId: Long,
    val tagId: Long
)

data class ShelfGroupCount(
    val groupId: Long?,
    val bookCount: Int
)

data class BookTagCount(
    val tagId: Long,
    val bookCount: Int
)
