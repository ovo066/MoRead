package com.mozhi.reader.ai.chat

import androidx.room.withTransaction
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.library.AttachmentStore
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** 历史消息编辑后的行为：用户消息会截断后文并需要重新生成，AI 消息只改正文。 */
data class MessageEditResult(
    val conversationId: Long,
    val role: String,
    val shouldRegenerate: Boolean
)

/**
 * 阅读侧统一会话仓库：持久化、自由新建/切换、编辑、删除、重 roll 与分支。
 *
 * 工具调用消息也是历史完整性的一部分。所有会改变既有历史的操作都会把记忆固化水位
 * 归零，并删除该会话已经生成的 ObjectBox 记忆，避免编辑后仍召回旧说法。
 */
@Singleton
class AiChatRepository @Inject constructor(
    private val database: MoReadDatabase,
    private val chatDao: ChatDao,
    private val vectorStore: dagger.Lazy<BoxStore>,
    private val attachmentStore: dagger.Lazy<AttachmentStore>
) {
    suspend fun startConversation(
        bookId: Long?,
        title: String,
        type: String,
        systemPrompt: String,
        firstUserMessage: String?,
        personaId: Long? = null,
        parentConversationId: Long? = null,
        branchedFromMessageId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val conversationId = chatDao.insertConversation(
                ConversationEntity(
                    bookId = bookId,
                    personaId = personaId,
                    title = title.take(MAX_TITLE_CHARS),
                    type = type,
                    parentConversationId = parentConversationId,
                    branchedFromMessageId = branchedFromMessageId,
                    createdAt = now,
                    updatedAt = now
                )
            )
            chatDao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.SYSTEM.wire,
                    content = systemPrompt,
                    createdAt = now
                )
            )
            firstUserMessage?.let {
                chatDao.insertMessage(
                    MessageEntity(
                        conversationId = conversationId,
                        role = ChatRole.USER.wire,
                        content = it,
                        createdAt = now + 1
                    )
                )
            }
            conversationId
        }
    }

    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> =
        chatDao.observeMessages(conversationId)

    suspend fun getMessages(conversationId: Long): List<MessageEntity> =
        chatDao.getMessages(conversationId)

    fun observeConversations(
        bookId: Long,
        personaId: Long,
        type: String
    ): Flow<List<ConversationEntity>> = chatDao.observeConversations(bookId, personaId, type)

    suspend fun getConversation(conversationId: Long): ConversationEntity? =
        chatDao.getConversation(conversationId)

    /** 该书 + 该角色最近一次伴读会话；没有则 null（首次发送时才建）。 */
    suspend fun findLatestConversation(
        bookId: Long,
        personaId: Long,
        type: String
    ): ConversationEntity? = chatDao.getLatestConversation(bookId, personaId, type)

    suspend fun appendUserMessage(
        conversationId: Long,
        content: String,
        attachmentsJson: String? = null
    ) {
        val clean = content.trim()
        require(clean.isNotEmpty() || attachmentsJson != null) { "消息不能为空" }
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatDao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.USER.wire,
                    content = clean,
                    createdAt = now,
                    attachmentsJson = attachmentsJson
                )
            )
            val conversation = chatDao.getConversation(conversationId)
            if (conversation?.title == NEW_CONVERSATION_TITLE) {
                chatDao.updateConversationTitle(
                    conversationId,
                    clean.replace(Regex("\\s+"), " ").take(MAX_TITLE_CHARS),
                    now
                )
            } else {
                chatDao.touchConversation(conversationId, now)
            }
        }
    }

    /** Persists a (possibly partial) assistant reply, e.g. when the user stops generation. */
    suspend fun appendAssistantMessage(conversationId: Long, content: String) {
        if (content.isBlank()) return
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatDao.insertMessage(
                MessageEntity(
                    conversationId = conversationId,
                    role = ChatRole.ASSISTANT.wire,
                    content = content,
                    createdAt = now
                )
            )
            chatDao.touchConversation(conversationId, now)
        }
    }

    /**
     * 编辑用户消息等价于 AI 客户端常见的「编辑并重发」：保留该条、删除其后全部管道，
     * 调用方随后重新跑 Agent。编辑 assistant 只替换正文，不擅自改动后续对话。
     */
    suspend fun editMessage(messageId: Long, content: String): MessageEditResult {
        val clean = content.trim()
        require(clean.isNotEmpty()) { "消息不能为空" }
        val message = chatDao.getMessage(messageId) ?: error("消息不存在")
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatDao.updateMessageContent(messageId, clean, now)
            if (message.role == ChatRole.USER.wire) {
                chatDao.deleteMessagesAfter(message.conversationId, message.id)
            }
            chatDao.resetMemoryConsolidationWatermark(message.conversationId, now)
        }
        invalidateConsolidatedMemory(message.conversationId)
        return MessageEditResult(
            conversationId = message.conversationId,
            role = message.role,
            shouldRegenerate = message.role == ChatRole.USER.wire
        )
    }

    /** 删除一个可见消息；用户消息连同本轮隐藏的 assistant/tool 管道一起删除。 */
    suspend fun deleteMessage(messageId: Long) {
        val message = chatDao.getMessage(messageId) ?: return
        val messages = chatDao.getMessages(message.conversationId)
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (message.role == ChatRole.USER.wire) {
                val nextUserId = messages.firstOrNull {
                    it.id > message.id && it.role == ChatRole.USER.wire
                }?.id ?: Long.MAX_VALUE
                chatDao.deleteMessageRange(
                    conversationId = message.conversationId,
                    fromMessageId = message.id,
                    untilMessageId = nextUserId
                )
            } else {
                chatDao.deleteMessage(message.id)
            }
            chatDao.resetMemoryConsolidationWatermark(message.conversationId, now)
        }
        invalidateConsolidatedMemory(message.conversationId)
    }

    /** 删除目标 AI 回复所属用户轮次之后的全部内容，为重 roll 留下自洽历史。 */
    suspend fun prepareReroll(assistantMessageId: Long): Long {
        val target = chatDao.getMessage(assistantMessageId) ?: error("消息不存在")
        require(target.role == ChatRole.ASSISTANT.wire) { "只能重新生成 AI 回复" }
        val precedingUser = chatDao.getMessages(target.conversationId)
            .lastOrNull { it.id < target.id && it.role == ChatRole.USER.wire }
            ?: error("找不到这条回复对应的用户消息")
        val now = System.currentTimeMillis()
        database.withTransaction {
            chatDao.deleteMessagesAfter(target.conversationId, precedingUser.id)
            chatDao.resetMemoryConsolidationWatermark(target.conversationId, now)
        }
        invalidateConsolidatedMemory(target.conversationId)
        return target.conversationId
    }

    /** 复制从 system 到 [throughMessageId] 的完整管道，生成可独立继续的新会话。 */
    suspend fun branchConversation(
        conversationId: Long,
        throughMessageId: Long
    ): Long {
        val source = chatDao.getConversation(conversationId) ?: error("会话不存在")
        val sourceMessages = chatDao.getMessages(conversationId)
            .takeWhile { it.id <= throughMessageId }
        require(sourceMessages.isNotEmpty()) { "分支位置无效" }
        val now = System.currentTimeMillis()
        return database.withTransaction {
            val branchId = chatDao.insertConversation(
                source.copy(
                    id = 0,
                    title = "${source.title.take(MAX_TITLE_CHARS - BRANCH_SUFFIX.length)}$BRANCH_SUFFIX",
                    parentConversationId = source.id,
                    branchedFromMessageId = throughMessageId,
                    memoryConsolidatedThroughMessageId = 0,
                    createdAt = now,
                    updatedAt = now
                )
            )
            sourceMessages.forEachIndexed { index, message ->
                chatDao.insertMessage(
                    message.copy(
                        id = 0,
                        conversationId = branchId,
                        createdAt = now + index
                    )
                )
            }
            branchId
        }
    }

    suspend fun renameConversation(conversationId: Long, title: String) {
        val clean = title.trim().take(MAX_TITLE_CHARS)
        require(clean.isNotEmpty()) { "会话标题不能为空" }
        chatDao.updateConversationTitle(conversationId, clean, System.currentTimeMillis())
    }

    suspend fun deleteConversation(conversationId: Long) {
        invalidateConsolidatedMemory(conversationId)
        chatDao.deleteConversation(conversationId)
        attachmentStore.get().deleteFor(conversationId)
    }

    private fun invalidateConsolidatedMemory(conversationId: Long) {
        runCatching {
            VectorQueries.removeMemoriesForConversation(vectorStore.get(), conversationId)
        }
    }

    companion object {
        const val NEW_CONVERSATION_TITLE = "新会话"
        private const val BRANCH_SUFFIX = " · 分支"
        private const val MAX_TITLE_CHARS = 60
    }
}
