package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.mozhi.reader.core.database.entity.BookTagCount
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.ShelfGroupCount
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ShelfOrganizationDao {
    @Query("SELECT * FROM book_collections ORDER BY createdAt DESC, id DESC")
    fun observeCollections(): Flow<List<BookCollectionEntity>>

    @Insert
    suspend fun insertCollection(collection: BookCollectionEntity): Long

    @Query("UPDATE book_collections SET name = :name WHERE id = :id")
    suspend fun renameCollection(id: Long, name: String)

    @Query("SELECT COALESCE(MAX(collectionOrder), -1) FROM books WHERE collectionId = :collectionId")
    suspend fun maxCollectionOrder(collectionId: Long): Int

    @Query("UPDATE books SET collectionId = :collectionId, collectionOrder = :order WHERE id = :bookId")
    suspend fun setBookCollection(bookId: Long, collectionId: Long?, order: Int)

    @Query("UPDATE books SET collectionId = NULL, collectionOrder = 0 WHERE id IN (:bookIds)")
    suspend fun clearBookCollections(bookIds: List<Long>)

    @Query("UPDATE books SET collectionId = NULL, collectionOrder = 0 WHERE collectionId = :collectionId")
    suspend fun clearCollection(collectionId: Long)

    @Query("DELETE FROM book_collections WHERE id = :id")
    suspend fun deleteCollectionRow(id: Long)

    @Query(
        "DELETE FROM book_collections WHERE id NOT IN " +
            "(SELECT DISTINCT collectionId FROM books WHERE collectionId IS NOT NULL)"
    )
    suspend fun deleteEmptyCollections()

    @Transaction
    suspend fun addBooksToCollection(collectionId: Long, bookIds: List<Long>) {
        var order = maxCollectionOrder(collectionId) + 1
        bookIds.distinct().forEach { bookId ->
            setBookCollection(bookId, collectionId, order++)
        }
        deleteEmptyCollections()
    }

    @Transaction
    suspend fun createCollection(name: String, bookIds: List<Long>): Long {
        val id = insertCollection(BookCollectionEntity(name = name, createdAt = System.currentTimeMillis()))
        bookIds.distinct().forEachIndexed { order, bookId ->
            setBookCollection(bookId, id, order)
        }
        deleteEmptyCollections()
        return id
    }

    @Transaction
    suspend fun removeBooksFromCollection(bookIds: List<Long>) {
        clearBookCollections(bookIds)
        deleteEmptyCollections()
    }

    @Transaction
    suspend fun dissolveCollection(id: Long) {
        clearCollection(id)
        deleteCollectionRow(id)
    }

    @Transaction
    suspend fun reorderCollectionBooks(collectionId: Long, bookIds: List<Long>) {
        bookIds.forEachIndexed { order, bookId ->
            setBookCollection(bookId, collectionId, order)
        }
    }

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
