package com.mozhi.reader.ai.companion

import androidx.room.withTransaction
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.TagNameNormalizer
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.dao.ShelfOrganizationDao
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The model can only prepare plans. Applying them is exclusively a user-interface action. */
@Singleton
class LibraryOrganizationCoordinator @Inject constructor(
    private val db: MoReadDatabase,
    private val chats: ChatDao,
    private val library: LibraryRepository,
    private val shelf: ShelfOrganizationRepository,
    private val dao: ShelfOrganizationDao,
    private val generations: CompanionGenerationTracker
) {
    private val mutex = Mutex()

    suspend fun preview(requests: List<LibraryOrganizationRequest>): LibraryOrganizationPlan = db.withTransaction {
        LibraryOrganizationPlans.validateRequests(requests)
        val changes = requests.mapNotNull { request ->
            val book = library.getBook(request.bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍已被移除")
            val tags = dao.getTagsForBook(book.id)
            val beforeGroup = book.groupId?.let { dao.getGroup(it) ?: error("分组已变更，请重试") }
            require(request.removeTags.all { name -> tags.any { TagNameNormalizer.isSame(it.name, name) } }) { "《${book.title.take(24)}》没有要移除的标签" }
            val add = request.addTags.filterNot { name -> tags.any { TagNameNormalizer.isSame(it.name, name) } }
            val groupName = request.groupName?.takeUnless { beforeGroup?.parentId == null && beforeGroup?.name == it }
            if (add.isEmpty() && request.removeTags.isEmpty() && groupName == null) return@mapNotNull null
            LibraryOrganizationChange(
                bookId = book.id, title = book.title.take(120),
                beforeTags = tags.map { LibraryOrganizationTag(it.id, it.name) },
                beforeGroupId = book.groupId, beforeGroupName = beforeGroup?.name.orEmpty(), beforeGroupParentId = beforeGroup?.parentId,
                addTags = add, removeTags = request.removeTags, groupName = groupName,
                existingAddTags = add.mapNotNull { dao.findTagByName(it)?.let { tag -> LibraryOrganizationTag(tag.id, tag.name) } },
                targetGroupId = groupName?.let { dao.findGroup(it, null)?.id }
            )
        }
        require(changes.isNotEmpty()) { "书架无需调整" }
        LibraryOrganizationPlan(UUID.randomUUID().toString(), changes).also { LibraryOrganizationPlans.encode(it) }
    }

    suspend fun confirm(messageId: Long, apply: Boolean): Int = mutex.withLock {
        db.withTransaction {
            val message = chats.getMessage(messageId)?.takeIf { it.role == "tool" } ?: error("方案已不存在")
            require(!generations.isActive(message.conversationId)) { "请等待当前回复完成" }
            val conversation = chats.getConversation(message.conversationId)
            require(conversation?.type == LibraryBookScopes.CONVERSATION_TYPE && conversation.bookId == null) { "话题无效" }
            val plan = LibraryOrganizationPlans.decode(message.content) ?: error("方案格式无效")
            require(plan.status == "PENDING") { "这份方案已处理" }
            if (apply) {
                // Re-validate persisted plans, including those restored from a backup.
                plan.changes.forEach { change ->
                    val book = library.getBook(change.bookId)?.takeIf { it.removedAt == 0L } ?: error("书籍已被移除")
                    val group = book.groupId?.let { dao.getGroup(it) }
                    require(book.title.take(120) == change.title && book.groupId == change.beforeGroupId &&
                        group?.name.orEmpty() == change.beforeGroupName && group?.parentId == change.beforeGroupParentId &&
                        dao.getTagsForBook(book.id).map { LibraryOrganizationTag(it.id, it.name) }.toSet() == change.beforeTags.toSet()
                    ) { "书架已变更，请重新生成方案" }
                    change.existingAddTags.forEach { expected ->
                        require(dao.findTagByName(expected.name)?.id == expected.id) { "标签已变更，请重新生成方案" }
                    }
                    change.targetGroupId?.let { targetId ->
                        val target = dao.getGroup(targetId)
                        require(target != null && target.name == change.groupName && target.parentId == null) { "分组已变更，请重新生成方案" }
                    }
                }
                plan.changes.forEach { change ->
                    change.addTags.forEach { shelf.addTagToBooks(shelf.createOrGetTag(it), listOf(change.bookId)) }
                    change.removeTags.forEach { name ->
                        val tag = change.beforeTags.single { TagNameNormalizer.isSame(it.name, name) }
                        shelf.removeTagFromBooks(tag.id, listOf(change.bookId))
                    }
                    change.groupName?.let { name -> shelf.setBookGroup(listOf(change.bookId), change.targetGroupId ?: shelf.createOrGetGroup(name, null)) }
                }
            }
            chats.updateMessageContent(messageId, LibraryOrganizationPlans.encode(plan.copy(status = if (apply) "APPLIED" else "CANCELLED")), System.currentTimeMillis())
            if (apply) plan.changes.size else 0
        }
    }
}
