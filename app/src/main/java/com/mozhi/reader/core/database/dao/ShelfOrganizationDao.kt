package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mozhi.reader.core.database.entity.BookTagCount
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.ShelfGroupCount
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfOrganizationDao {
    @Query("SELECT * FROM shelf_groups ORDER BY sortOrder, createdAt, id")
    fun observeGroups(): Flow<List<ShelfGroupEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGroup(group: ShelfGroupEntity): Long

    @Query(
        "SELECT * FROM shelf_groups WHERE name = :name AND " +
            "((parentId = :parentId) OR (parentId IS NULL AND :parentId IS NULL)) LIMIT 1"
    )
    suspend fun findGroup(name: String, parentId: Long?): ShelfGroupEntity?

    @Update
    suspend fun updateGroup(group: ShelfGroupEntity)

    @Transaction
    suspend fun upsertGroup(group: ShelfGroupEntity): Long {
        if (group.id == 0L) return insertGroup(group)
        updateGroup(group)
        return group.id
    }

    @Query("DELETE FROM shelf_groups WHERE id = :id")
    suspend fun deleteGroupRow(id: Long)

    @Transaction
    suspend fun deleteGroup(id: Long, moveBooksToGroupId: Long?) {
        moveBooksFromGroup(id, moveBooksToGroupId)
        clearChildrenFromGroup(id)
        deleteGroupRow(id)
    }

    @Query("UPDATE shelf_groups SET parentId = NULL WHERE parentId = :id")
    suspend fun clearChildrenFromGroup(id: Long)

    @Query("UPDATE shelf_groups SET parentId = :parentId WHERE id = :id")
    suspend fun setGroupParent(id: Long, parentId: Long?)

    @Query("UPDATE shelf_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setGroupSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE books SET groupId = :toGroupId WHERE groupId = :fromGroupId")
    suspend fun moveBooksFromGroup(fromGroupId: Long, toGroupId: Long?)

    @Query("UPDATE books SET groupId = :toGroupId WHERE groupId IS NULL")
    suspend fun moveUngroupedBooks(toGroupId: Long?)

    @Query("SELECT groupId, COUNT(*) AS bookCount FROM books GROUP BY groupId")
    fun observeGroupCounts(): Flow<List<ShelfGroupCount>>

    @Query("SELECT * FROM book_tags ORDER BY groupName, sortOrder, createdAt, id")
    fun observeTags(): Flow<List<BookTagEntity>>

    @Query(
        "SELECT tagId, COUNT(*) AS bookCount FROM book_tag_refs GROUP BY tagId"
    )
    fun observeTagCounts(): Flow<List<BookTagCount>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: BookTagEntity): Long

    @Update
    suspend fun updateTag(tag: BookTagEntity)

    @Transaction
    suspend fun upsertTag(tag: BookTagEntity): Long {
        if (tag.id == 0L) return insertTag(tag)
        updateTag(tag)
        return tag.id
    }

    @Query("SELECT * FROM book_tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findTagByName(name: String): BookTagEntity?

    @Query("DELETE FROM book_tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    @Query(
        "INSERT OR IGNORE INTO book_tag_refs(bookId, tagId) " +
            "SELECT bookId, :targetId FROM book_tag_refs WHERE tagId IN (:sourceIds)"
    )
    suspend fun copyTagRefs(sourceIds: List<Long>, targetId: Long)

    @Query("DELETE FROM book_tags WHERE id IN (:sourceIds)")
    suspend fun deleteTags(sourceIds: List<Long>)

    @Query("UPDATE book_tags SET groupName = :groupName WHERE id IN (:tagIds)")
    suspend fun setTagsGroup(tagIds: List<Long>, groupName: String)

    @Query("UPDATE book_tags SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setTagSortOrder(id: Long, sortOrder: Int)

    @Transaction
    suspend fun mergeTags(sourceIds: List<Long>, targetId: Long) {
        val sources = sourceIds.filter { it != targetId }
        if (sources.isEmpty()) return
        copyTagRefs(sources, targetId)
        deleteTags(sources)
    }

    @Query("SELECT * FROM book_tag_refs ORDER BY bookId, tagId")
    fun observeTagRefs(): Flow<List<BookTagRefEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagRefs(refs: List<BookTagRefEntity>)

    @Query("DELETE FROM book_tag_refs WHERE tagId = :tagId AND bookId IN (:bookIds)")
    suspend fun removeTagFromBooks(tagId: Long, bookIds: List<Long>)

    @Query("UPDATE books SET groupId = :groupId WHERE id IN (:bookIds)")
    suspend fun setBookGroup(bookIds: List<Long>, groupId: Long?)
}
