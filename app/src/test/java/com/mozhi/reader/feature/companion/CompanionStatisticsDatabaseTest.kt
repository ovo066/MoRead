package com.mozhi.reader.feature.companion

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.ai.chat.AiChatRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CompanionStatisticsDatabaseTest {
    @Test fun onlyRepliedUsersCountAndConversationDeletionRemovesTheirStatistics() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val bookChat = dao.insertConversation(ConversationEntity(bookId = null, title = "本书", type = "COMPANION", createdAt = 1))
            val libraryChat = dao.insertConversation(ConversationEntity(bookId = null, title = "跨书", type = "LIBRARY_COMPANION", createdAt = 1))
            val selection = dao.insertConversation(ConversationEntity(bookId = null, title = "选段", type = "SELECTION", createdAt = 1))
            suspend fun put(conversation: Long, role: String, text: String, tools: String? = null): Long = dao.insertMessage(MessageEntity(
                conversationId = conversation, role = role, content = text, toolCallsJson = tools, createdAt = 1,
                clientRoundId = if (role == "assistant") "reply:$conversation:$text" else null
            ))
            put(bookChat, "user", "失败问题")
            put(bookChat, "user", "只调用工具的问题")
            put(bookChat, "assistant", "先查一下", "[{\"name\":\"read\"}]")
            put(bookChat, "tool", "工具结果")
            val replied = put(bookChat, "user", "有效问题")
            put(bookChat, "assistant", "有效回复")
            put(bookChat, "assistant", "额外一段不重复记轮次")
            put(bookChat, "user", "只有上限提示")
            put(bookChat, "assistant", "（已达到单轮工具调用上限，回复基于目前掌握的信息）")
            val partial = put(libraryChat, "user", "比较笔记")
            put(libraryChat, "assistant", "用户停止后保留的部分回复")
            put(selection, "user", "解释选段")
            put(selection, "assistant", "选段回复")
            val rounds = dao.observeCompletedCompanionRounds().first()
            assertEquals(setOf(replied, partial), rounds.map { it.id }.toSet())
            assertEquals(1, dao.observeLibraryConversations().first().size)
            assertTrue(dao.observeCompanionUsage().first().none { it.type == "SELECTION" })
            dao.deleteConversation(libraryChat)
            assertEquals(listOf(replied), dao.observeCompletedCompanionRounds().first().map { it.id })
        } finally { db.close() }
    }

    @Test fun wordsExcludeToolsReasoningSelectionAndLimitNoticesAndDeduplicateLegacyBranches() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val repo = AiChatRepository(db, dao, dagger.Lazy { error("No vectors") }, dagger.Lazy { error("No attachments") })
            val chat = repo.startConversation(null, "旧伴读", "COMPANION", "系统规则", null)
            dao.insertMessage(MessageEntity(conversationId = chat, role = "user", content = "你好\n\t世界 😀", createdAt = 1))
            val reply = dao.insertMessage(MessageEntity(conversationId = chat, role = "assistant", content = "一起读", reasoningContent = "不计入字数", clientRoundId = "old-reply", createdAt = 2))
            dao.insertMessage(MessageEntity(conversationId = chat, role = "tool", content = "原文不应算作聊天", createdAt = 3))
            dao.insertMessage(MessageEntity(conversationId = chat, role = "assistant", content = "（已达到单轮工具调用上限，回复基于目前掌握的信息）", createdAt = 4))
            val branch = repo.branchConversation(chat, reply)
            val rows = dao.observeCompanionWords().first()
            assertEquals(4, rows.size)
            assertEquals(setOf("old-reply"), rows.map { it.roundId }.toSet())
            val statistics = buildCompanionStatistics(dao.observeCompletedCompanionRounds().first(), emptyList(), CompanionStatsSelection(), words = rows)
            assertEquals(8L, statistics.chatCharacters) // SQLite counts Unicode code points; emoji counts once.
            assertEquals(1, statistics.rounds)
            dao.deleteConversation(branch)
            assertEquals(2, dao.observeCompanionWords().first().size)
            dao.deleteConversation(chat)
            assertTrue(dao.observeCompanionWords().first().isEmpty())
        } finally { db.close() }
    }

    @Test fun newUserIdentityAndKnownTurnAssociationsPersistWithConversationScopes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).build()
        try {
            val dao = db.chatDao()
            val repo = AiChatRepository(db, dao, dagger.Lazy { error("No vectors") }, dagger.Lazy { error("No attachments") })
            val chat = repo.startConversation(null, "书库", "LIBRARY_COMPANION", "规则", null)
            repo.appendUserMessage(chat, "你好")
            val user = repo.getMessages(chat).last()
            assertFalse(user.clientRoundId.isNullOrBlank())
            repo.updateLibraryContext(chat, user.clientRoundId!!, "[]", "[]")
            repo.appendAssistantMessage(chat, "一起读书吧", "reply")
            assertEquals("[]", dao.observeCompletedCompanionRounds().first().single().sourceBookIdsJson)
            val branch = repo.branchConversation(chat, repo.getMessages(chat).last().id)
            assertEquals(user.clientRoundId, repo.getMessages(branch).single { it.role == "user" }.clientRoundId)
        } finally { db.close() }
    }
}
