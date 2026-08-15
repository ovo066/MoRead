package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: Long): ConversationEntity?

    @Query(
        "SELECT * FROM conversations WHERE bookId = :bookId AND personaId = :personaId " +
            "AND type = :type ORDER BY updatedAt DESC, id DESC LIMIT 1"
    )
    suspend fun getLatestConversation(
        bookId: Long,
        personaId: Long,
        type: String
    ): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE bookId = :bookId ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(bookId: Long): Flow<List<ConversationEntity>>

    @Query(
        "SELECT * FROM conversations WHERE bookId = :bookId AND personaId = :personaId " +
            "AND type = :type ORDER BY updatedAt DESC, id DESC"
    )
    fun observeConversations(
        bookId: Long,
        personaId: Long,
        type: String
    ): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun updateConversationTitle(conversationId: Long, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :conversationId")
    suspend fun touchConversation(conversationId: Long, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: Long)

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    /** 全局统计用：用户发出的每条消息记一次「AI 对话」。 */
    @Query("SELECT COUNT(*) FROM messages WHERE role = 'user'")
    fun observeUserMessageCount(): Flow<Int>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY id ASC")
    suspend fun getMessages(conversationId: Long): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: Long): MessageEntity?

    @Query("UPDATE messages SET content = :content, editedAt = :editedAt WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, content: String, editedAt: Long)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId AND id > :messageId")
    suspend fun deleteMessagesAfter(conversationId: Long, messageId: Long)

    @Query(
        "DELETE FROM messages WHERE conversationId = :conversationId " +
            "AND id >= :fromMessageId AND id < :untilMessageId"
    )
    suspend fun deleteMessageRange(
        conversationId: Long,
        fromMessageId: Long,
        untilMessageId: Long
    )

    @Query(
        "UPDATE conversations SET memoryConsolidatedThroughMessageId = 0, " +
            "updatedAt = :updatedAt WHERE id = :conversationId"
    )
    suspend fun resetMemoryConsolidationWatermark(conversationId: Long, updatedAt: Long)

    @Query(
        "UPDATE conversations SET memoryConsolidatedThroughMessageId = :messageId " +
            "WHERE id = :conversationId AND memoryConsolidatedThroughMessageId < :messageId"
    )
    suspend fun advanceMemoryConsolidationWatermark(conversationId: Long, messageId: Long)

    /** 前情提要与它的水位一起写：两者分开更新会出现「提要旧、水位新」的空窗。 */
    @Query(
        "UPDATE conversations SET rollingSummary = :summary, " +
            "summarizedThroughMessageId = :messageId WHERE id = :conversationId"
    )
    suspend fun updateRollingSummary(conversationId: Long, summary: String, messageId: Long)

    /** 历史被编辑/删除后提要可能引用了不存在的内容，直接清空重来。 */
    @Query(
        "UPDATE conversations SET rollingSummary = '', summarizedThroughMessageId = 0 " +
            "WHERE id = :conversationId"
    )
    suspend fun clearRollingSummary(conversationId: Long)
}
