package com.mozhi.reader.core.library

import com.mozhi.reader.core.database.ShelfTagBackfill
import com.mozhi.reader.core.database.TagNameNormalizer
import com.mozhi.reader.core.database.dao.ShelfOrganizationDao
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class ShelfOrganizationSnapshot(
    val groups: List<ShelfGroupEntity> = emptyList(),
    val groupCounts: Map<Long?, Int> = emptyMap(),
    val tags: List<BookTagEntity> = emptyList(),
    val tagCounts: Map<Long, Int> = emptyMap(),
    val tagRefs: List<BookTagRefEntity> = emptyList(),
    val collections: List<BookCollectionEntity> = emptyList()
) {
    fun tagIdsFor(bookId: Long): Set<Long> = tagRefs
        .asSequence()
        .filter { it.bookId == bookId }
        .map(BookTagRefEntity::tagId)
        .toSet()
}

@Singleton
class ShelfOrganizationRepository @Inject constructor(
    private val dao: ShelfOrganizationDao
) {
    private val organization = combine(
        dao.observeGroups(),
        dao.observeGroupCounts().map { rows -> rows.associate { it.groupId to it.bookCount } },
        dao.observeTags(),
        dao.observeTagCounts().map { rows -> rows.associate { it.tagId to it.bookCount } },
        dao.observeTagRefs()
    ) { groups, groupCounts, tags, tagCounts, refs ->
        ShelfOrganizationSnapshot(groups, groupCounts, tags, tagCounts, refs)
    }

    val snapshot: Flow<ShelfOrganizationSnapshot> =
        combine(organization, dao.observeCollections()) { current, collections ->
            current.copy(collections = collections)
        }

    suspend fun createCollection(name: String, bookIds: Collection<Long>): Long =
        dao.createCollection(name.trim(), bookIds.toList())

    suspend fun addBooksToCollection(id: Long, bookIds: Collection<Long>) =
        dao.addBooksToCollection(id, bookIds.toList())

    suspend fun removeBooksFromCollection(bookIds: Collection<Long>) =
        dao.removeBooksFromCollection(bookIds.toList())

    suspend fun renameCollection(id: Long, name: String) = dao.renameCollection(id, name.trim())

    suspend fun dissolveCollection(id: Long) = dao.dissolveCollection(id)

    suspend fun reorderCollectionBooks(collectionId: Long, bookIds: List<Long>) =
        dao.reorderCollectionBooks(collectionId, bookIds)

    suspend fun deleteEmptyCollections() = dao.deleteEmptyCollections()

    suspend fun saveGroup(group: ShelfGroupEntity): Long = dao.upsertGroup(
        group.copy(name = group.name.trim())
    )

    suspend fun createOrGetGroup(name: String, parentId: Long?): Long {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "分组名不能为空" }
        dao.findGroup(normalized, parentId)?.let { return it.id }
        val inserted = dao.insertGroup(
            ShelfGroupEntity(
                name = normalized,
                parentId = parentId,
                createdAt = System.currentTimeMillis()
            )
        )
        return if (inserted > 0) inserted else requireNotNull(dao.findGroup(normalized, parentId)).id
    }

    suspend fun createOrGetGroupPath(relativeDirectory: String): Long? {
        val parts = relativeDirectory.split('/', '\\')
            .map(String::trim)
            .filter(String::isNotEmpty)
        if (parts.isEmpty()) return null
        val parentId = createOrGetGroup(parts.first(), null)
        if (parts.size == 1) return parentId
        return createOrGetGroup(parts.drop(1).joinToString(" / "), parentId)
    }

    suspend fun deleteGroup(id: Long, moveBooksToGroupId: Long? = null) =
        dao.deleteGroup(id, moveBooksToGroupId)

    suspend fun setGroupParent(id: Long, parentId: Long?) = dao.setGroupParent(id, parentId)

    suspend fun reorderGroups(groups: List<ShelfGroupEntity>) {
        groups.forEachIndexed { index, group -> dao.setGroupSortOrder(group.id, index) }
    }

    suspend fun moveBooksToGroup(fromGroupId: Long?, toGroupId: Long?) {
        if (fromGroupId == null) dao.moveUngroupedBooks(toGroupId)
        else dao.moveBooksFromGroup(fromGroupId, toGroupId)
    }

    suspend fun setBookGroup(bookIds: Collection<Long>, groupId: Long?) {
        if (bookIds.isNotEmpty()) dao.setBookGroup(bookIds.toList(), groupId)
    }

    suspend fun saveTag(tag: BookTagEntity): Long = dao.upsertTag(
        tag.copy(
            name = TagNameNormalizer.normalize(tag.name),
            groupName = TagNameNormalizer.normalize(tag.groupName)
        )
    )

    suspend fun createOrGetTag(name: String, groupName: String = ""): Long {
        val normalized = TagNameNormalizer.normalize(name)
        require(normalized.isNotEmpty()) { "标签名不能为空" }
        dao.findTagByName(normalized)?.let { return it.id }
        val inserted = dao.insertTag(
            BookTagEntity(
                name = normalized,
                colorTag = ShelfTagBackfill.colorFor(normalized),
                groupName = TagNameNormalizer.normalize(groupName),
                createdAt = System.currentTimeMillis()
            )
        )
        return if (inserted > 0) inserted else requireNotNull(dao.findTagByName(normalized)).id
    }

    suspend fun addTagToBooks(tagId: Long, bookIds: Collection<Long>) {
        if (bookIds.isEmpty()) return
        dao.insertTagRefs(bookIds.distinct().map { BookTagRefEntity(it, tagId) })
    }

    suspend fun removeTagFromBooks(tagId: Long, bookIds: Collection<Long>) {
        if (bookIds.isNotEmpty()) dao.removeTagFromBooks(tagId, bookIds.distinct())
    }

    suspend fun deleteTag(id: Long) = dao.deleteTag(id)

    suspend fun deleteTags(ids: Collection<Long>) {
        if (ids.isNotEmpty()) dao.deleteTags(ids.distinct())
    }

    suspend fun setTagsGroup(ids: Collection<Long>, groupName: String) {
        if (ids.isNotEmpty()) dao.setTagsGroup(ids.distinct(), TagNameNormalizer.normalize(groupName))
    }

    suspend fun reorderTags(tags: List<BookTagEntity>) {
        tags.forEachIndexed { index, tag -> dao.setTagSortOrder(tag.id, index) }
    }

    suspend fun mergeTags(sourceIds: Collection<Long>, targetId: Long) {
        dao.mergeTags(sourceIds.distinct(), targetId)
    }
}
