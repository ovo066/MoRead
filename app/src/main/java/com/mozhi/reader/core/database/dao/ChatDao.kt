package com.mozhi.reader.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

data class ConversationStorageOwner(val id: Long, val bookId: Long?)

@Dao
interface ChatDao {
    @Query("""
        SELECT u.id, u.conversationId, u.createdAt, c.bookId, c.type, c.bookScopesJson,
               r.clientRoundId AS replyRoundId, u.sourceBookIdsJson
        FROM messages u JOIN conversations c ON c.id = u.conversationId
        JOIN messages r ON r.id = (
            SELECT MIN(a.id) FROM messages a
            WHERE a.conversationId = u.conversationId AND a.role = 'assistant' AND a.id > u.id
              AND length(trim(a.content)) > 0 AND COALESCE(a.toolCallsJson, '') IN ('', '[]')
              AND a.content NOT IN ('（已达到单轮工具调用上限，回复基于目前掌握的信息）', '（已达到工具调用上限，回复基于目前掌握的信息）')
              AND a.id < COALESCE((SELECT MIN(n.id) FROM messages n
                WHERE n.conversationId = u.conversationId AND n.role = 'user' AND n.id > u.id), 9223372036854775807)
        )
        WHERE u.role = 'user' AND c.type IN ('COMPANION', 'LIBRARY_COMPANION')
        ORDER BY u.createdAt, u.id
    """)
    fun observeCompletedCompanionRounds(): Flow<List<CompletedCompanionRound>>

    @Query("""
        SELECT m.id, m.clientRoundId AS roundId, m.createdAt, c.type, m.tokenUsage AS tokens
        FROM messages m JOIN conversations c ON c.id = m.conversationId
        WHERE m.role = 'assistant' AND c.type IN ('COMPANION', 'LIBRARY_COMPANION')
        ORDER BY m.createdAt, m.id
    """)
    fun observeCompanionUsage(): Flow<List<CompanionUsageRow>>

    @Query("""
        SELECT m.id, COALESCE(NULLIF(m.clientRoundId, ''), CASE WHEN m.role = 'user' THEN (
            SELECT NULLIF(a.clientRoundId, '') FROM messages a
            WHERE a.conversationId = m.conversationId AND a.role = 'assistant' AND a.id > m.id
              AND a.id < COALESCE((SELECT MIN(n.id) FROM messages n
                WHERE n.conversationId = m.conversationId AND n.role = 'user' AND n.id > m.id), 9223372036854775807)
            ORDER BY a.id LIMIT 1
        ) END) AS roundId, m.createdAt, c.type, m.role,
            length(replace(replace(replace(replace(m.content, ' ', ''), char(9), ''), char(10), ''), char(13), '')) AS characters
        FROM messages m JOIN conversations c ON c.id = m.conversationId
        WHERE m.role IN ('user', 'assistant') AND c.type IN ('COMPANION', 'LIBRARY_COMPANION')
          AND (m.role = 'user' OR m.content NOT IN ('（已达到单轮工具调用上限，回复基于目前掌握的信息）', '（已达到工具调用上限，回复基于目前掌握的信息）'))
        ORDER BY m.createdAt, m.id
    """)
    fun observeCompanionWords(): Flow<List<CompanionWordsRow>>

    @Query("UPDATE conversations SET bookScopesJson = :scopes WHERE id = :conversationId AND type = 'LIBRARY_COMPANION'")
    suspend fun updateLibraryScopes(conversationId: Long, scopes: String)

    @Query("UPDATE messages SET sourceBookIdsJson = :bookIds WHERE conversationId = :conversationId AND clientRoundId = :userRoundId AND role = 'user'")
    suspend fun updateLibraryTurnBooks(conversationId: Long, userRoundId: String, bookIds: String)

    @Query("SELECT id, bookId FROM conversations")
    suspend fun getStorageOwners(): List<ConversationStorageOwner>
    @Query("SELECT id FROM conversations WHERE bookId = :bookId")
    suspend fun getConversationIdsForBook(bookId: Long): List<Long>

    @Query("SELECT id FROM conversations")
    suspend fun getAllConversationIds(): List<Long>

    @Query("SELECT * FROM conversations WHERE bookId IS NULL AND type = 'LIBRARY_COMPANION' ORDER BY updatedAt DESC, id DESC")
    fun observeLibraryConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE bookId IS NULL AND type = 'LIBRARY_COMPANION'")
    suspend fun getLibraryConversations(): List<ConversationEntity>

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

    @Query("UPDATE messages SET clientRoundId = :roundId WHERE id = :messageId AND role = 'user'")
    suspend fun updateUserRoundId(messageId: Long, roundId: String)

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
