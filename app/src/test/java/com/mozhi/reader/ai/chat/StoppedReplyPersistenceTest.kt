package com.mozhi.reader.ai.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.feature.reader.StoppedCompanionReply
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class StoppedReplyPersistenceTest {
    private fun database() = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java
    ).allowMainThreadQueries().build()

    private fun repository(db: MoReadDatabase) = AiChatRepository(db, db.chatDao(),
        dagger.Lazy { error("Stop persistence must not read ObjectBox") },
        dagger.Lazy { error("Stop persistence must not read attachments") })

    @Test fun stopPersistsTextAndReasoningUnderOneRoundAndRetryIsIdempotent() = runBlocking {
        val db = database()
        try {
            val repo = repository(db)
            val conversation = repo.startConversation(null, "chat", "COMPANION", "system", null)
            val saved = repo.appendAssistantMessage(conversation, "partial", "round", "thinking")!!
            assertEquals("partial", saved.content)
            assertEquals("thinking", saved.reasoningContent)
            assertEquals("round", saved.clientRoundId)
            val retried = repo.appendAssistantMessage(conversation, "partial", "round", "thinking")!!
            assertEquals(saved.id, retried.id)
            assertEquals(1, repo.getMessages(conversation).count { it.clientRoundId == "round" })
            val reasoningOnly = repo.appendAssistantMessage(conversation, "", "reason-only", "unfinished thought")!!
            assertEquals("", reasoningOnly.content)
            assertEquals("unfinished thought", reasoningOnly.reasoningContent)
        } finally { db.close() }
    }

    @Test fun failedInsertDoesNotConsumeRecoveryPayloadAndSameRoundCanRetry() = runBlocking {
        val db = database()
        try {
            val repo = repository(db)
            val draft = StoppedCompanionReply(99, "retry-round", "kept text", "kept reasoning")
            val failed = runCatching { repo.appendAssistantMessage(draft.conversationId, draft.text,
                draft.roundId, draft.reasoning) }
            assertTrue(failed.isFailure) // Missing parent conversation, transaction must roll back.
            assertTrue(db.chatDao().getMessages(99).isEmpty())
            db.chatDao().insertConversation(ConversationEntity(id = 99, bookId = null,
                title = "restored", type = "COMPANION", createdAt = 1, updatedAt = 1))
            val saved = repo.appendAssistantMessage(draft.conversationId, draft.text, draft.roundId, draft.reasoning)!!
            assertEquals(draft.text, saved.content)
            assertEquals(draft.reasoning, saved.reasoningContent)
            assertEquals(draft.roundId, saved.clientRoundId)
        } finally { db.close() }
    }
}
