package com.mozhi.reader.ai.chat

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.entity.MessageEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class LibraryHistoryMutationTest {
    private lateinit var db: MoReadDatabase
    private lateinit var repo: AiChatRepository
    private var conversation = 0L
    private var user = 0L
    private var assistant = 0L
    private var later = 0L

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MoReadDatabase::class.java).allowMainThreadQueries().build()
        repo = AiChatRepository(db, db.chatDao(), dagger.Lazy { error("No vector store") }, dagger.Lazy { error("No attachments") })
        conversation = repo.startConversation(null, "书库话题", "LIBRARY_COMPANION", "system", null, personaId = 7, bookScopesJson = "[]")
        user = insert("user", "第一问", round = "user-round", books = "[1,2]")
        assistant = insert("assistant", "第一答", round = "assistant-round")
        later = insert("user", "第二问", round = "later-round", books = "[2]")
        insert("assistant", "第二答")
        db.chatDao().updateRollingSummary(conversation, "不应带入更早分支的后文摘要", later)
    }

    @After fun close() { db.close() }

    @Test fun userEditsTruncateLaterHistoryAndInvalidateSummaryWithoutDuplicatingQuestion() = runBlocking {
        assertTrue(repo.editMessage(user, "修订后的第一问").shouldRegenerate)
        val rows = repo.getMessages(conversation)
        assertEquals(listOf("system", "user"), rows.map { it.role })
        assertEquals("修订后的第一问", rows.last().content)
        assertEquals(user, rows.last().id)
        assertEquals("user-round", repo.ensureUserRoundId(user))
        assertEquals("", repo.getConversation(conversation)?.rollingSummary)
    }

    @Test fun assistantEditKeepsLaterConversationAndClearsStaleSummary() = runBlocking {
        assertFalse(repo.editMessage(assistant, "编辑过的回答").shouldRegenerate)
        assertEquals(5, repo.getMessages(conversation).size)
        assertEquals("编辑过的回答", repo.getMessages(conversation).first { it.id == assistant }.content)
        assertNotNull(db.chatDao().getMessage(later))
        assertEquals(0L, repo.getConversation(conversation)?.summarizedThroughMessageId)
    }

    @Test fun branchPreservesSourceIdentityAndScopeButNotLaterSummaries() = runBlocking {
        val branchId = repo.branchConversation(conversation, assistant)
        val branch = requireNotNull(repo.getConversation(branchId))
        assertEquals("LIBRARY_COMPANION", branch.type)
        assertEquals(7L, branch.personaId)
        assertEquals(conversation, branch.parentConversationId)
        assertEquals(assistant, branch.branchedFromMessageId)
        assertEquals("[]", branch.bookScopesJson)
        assertEquals("", branch.rollingSummary)
        assertEquals(0L, branch.summarizedThroughMessageId)
        val copied = repo.getMessages(branchId)
        assertEquals(listOf("system", "第一问", "第一答"), copied.map { it.content })
        assertEquals("[1,2]", copied.first { it.role == "user" }.sourceBookIdsJson)
        assertEquals("assistant-round", copied.last().clientRoundId)
        assertEquals(5, repo.getMessages(conversation).size)
        assertTrue(runCatching { repo.branchConversation(conversation, Long.MAX_VALUE) }.isFailure)
    }

    @Test fun rerollAndRetryReuseTheOriginalUserAndDropAllLaterPipelines() = runBlocking {
        assertEquals(conversation, repo.prepareReroll(assistant))
        assertEquals(listOf("system", "第一问"), repo.getMessages(conversation).map { it.content })
        repo.prepareRetry(user)
        assertEquals(2, repo.getMessages(conversation).size)
        assertEquals("", repo.getConversation(conversation)?.rollingSummary)
    }

    @Test fun deletingAUserRemovesOnlyTheirRoundAndItsToolResults() = runBlocking {
        repo.deleteMessage(user)
        assertEquals(listOf("system", "第二问", "第二答"), repo.getMessages(conversation).map { it.content })
        assertEquals("", repo.getConversation(conversation)?.rollingSummary)
    }

    @Test fun assistantToolBranchKeepsItsRequiredToolResults() = runBlocking {
        repo.prepareRetry(user)
        val call = db.chatDao().insertMessage(MessageEntity(conversationId = conversation, role = "assistant", content = "查阅原文", createdAt = 2,
            toolCallsJson = """[{"id":"tool-1","name":"read_book_section","arguments":"{}"}]"""))
        db.chatDao().insertMessage(MessageEntity(conversationId = conversation, role = "tool", content = "已读原文", toolCallId = "tool-1", createdAt = 3))
        insert("assistant", "不应复制的后续解答")
        val branch = repo.branchConversation(conversation, call)
        assertEquals(listOf("system", "user", "assistant", "tool"), repo.getMessages(branch).map { it.role })
    }

    @Test fun earlyBranchDoesNotKeepBooksThatWereOnlyReadInLaterTurns() = runBlocking {
        val scopes = listOf(1L, 2L).map { com.mozhi.reader.ai.companion.LibraryBookScope(it, "书$it", "a".repeat(64), 1, 20) }
        val codec = com.mozhi.reader.ai.companion.LibraryBookScopes
        db.chatDao().updateLibraryScopes(conversation, codec.encode(scopes))
        db.chatDao().updateLibraryTurnBooks(conversation, "user-round", "[1]")
        val branch = repo.branchConversation(conversation, assistant)
        assertEquals(listOf(1L), codec.decode(repo.getConversation(branch)!!.bookScopesJson).map { it.bookId })
        assertEquals(2, codec.decode(repo.getConversation(conversation)!!.bookScopesJson).size)
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET sourceBookIdsJson = NULL WHERE id = ?", arrayOf(user))
        val legacyBranch = repo.branchConversation(conversation, assistant)
        assertEquals(2, codec.decode(repo.getConversation(legacyBranch)!!.bookScopesJson).size)
    }

    private suspend fun insert(role: String, text: String, round: String? = null, books: String? = null): Long = db.chatDao().insertMessage(
        MessageEntity(conversationId = conversation, role = role, content = text, createdAt = 1, clientRoundId = round, sourceBookIdsJson = books))
}
