package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.LibraryCompanionToolset
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.UserMaskStore
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class LibraryCompanionRunnerTest {
    private val main = newSingleThreadContext("library-runner-test")
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val chats = mockk<AiChatRepository>()
    private val personas = mockk<PersonaRepository>()
    private val masks = mockk<UserMaskStore>()
    private val guard = mockk<LibraryScopeGuard>()
    private val tools = mockk<LibraryCompanionToolset>()
    private val loop = mockk<AgentLoop>()
    private val tracker = CompanionGenerationTracker()
    private val runner = LibraryCompanionRunner(chats, personas, masks, guard, tools, loop, tracker, scope)
    private val conversation = ConversationEntity(id = 1, bookId = null, title = "闲聊", type = LibraryBookScopes.CONVERSATION_TYPE, createdAt = 1)
    private val committed = MessageEntity(id = 9, conversationId = 1, role = "assistant", content = "完整回复", createdAt = 2, clientRoundId = "r1")

    @Before fun setup() {
        Dispatchers.setMain(main)
        coEvery { masks.activeMask() } returns null
        coEvery { chats.getConversation(1) } returns conversation
        coEvery { chats.updateLibraryContext(any(), any(), any(), any()) } just Runs
        coEvery { guard.refresh(any()) } answers { firstArg() }
        coEvery { guard.validate(any<List<LibraryBookScope>>()) } just Runs
        coEvery { chats.appendUserMessage(any(), any(), any(), any(), any(), any(), any()) } just Runs
        coEvery { chats.appendAssistantMessage(any(), any(), any(), any()) } answers {
            committed.copy(content = secondArg(), clientRoundId = thirdArg())
        }
        coEvery { tools.forConversation(any(), any()) } returns emptyList()
    }

    @After fun close() {
        scope.cancel()
        Dispatchers.resetMain()
        main.close()
    }

    @Test fun eventFirstCommitRemainsVisibleAndCannotDuplicateOnReplay() = runBlocking {
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1"))
            emit(AgentEvent.Text("完整回复"))
            emit(AgentEvent.RoundCommitted(committed))
            emit(AgentEvent.RoundCommitted(committed))
        }
        runner.send(conversation, "聊聊阅读")
        val state = withTimeout(5_000) { runner.states.first { it[1]?.running == false && it[1]?.committed?.isNotEmpty() == true } }.getValue(1)
        assertEquals(listOf(committed), state.committed)
        assertEquals("", state.text)
        assertNull(state.status)
        assertFalse(tracker.isActive(1))
        coVerify(exactly = 1) { chats.appendUserMessage(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { chats.appendAssistantMessage(any(), any(), any(), any()) }
    }

    @Test fun stoppingPersistsPartialOnceAndWaitsBeforeRemovingGenerationOwnership() = runBlocking {
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1"))
            emit(AgentEvent.Text("未完的回复"))
            awaitCancellation()
        }
        runner.send(conversation, "聊聊阅读")
        withTimeout(5_000) { runner.states.first { it[1]?.text == "未完的回复" } }
        assertTrue(tracker.isActive(1))
        runner.stop(1)
        val state = runner.states.value.getValue(1)
        assertFalse(state.running)
        assertFalse(tracker.isActive(1))
        assertEquals("未完的回复", state.committed.single().content)
        coVerify(exactly = 1) { chats.appendAssistantMessage(1, "未完的回复", "r1", any()) }
        runner.forget(1)
        assertTrue(runner.states.value.isEmpty())
    }

    @Test fun invalidScopeFailsBeforeSavingUserInputOrCallingTheModel() = runBlocking {
        coEvery { guard.refresh(any()) } throws IllegalStateException("正文已变更")
        assertTrue(runCatching { runner.send(conversation, "问题") }.isFailure)
        assertTrue(runner.states.value.isEmpty())
        coVerify(exactly = 0) { chats.appendUserMessage(any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { loop.run(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun reloadedConversationOwnsPersonaAndInvalidTypesFailBeforeAppending() = runBlocking {
        coEvery { chats.getConversation(1) } returns conversation.copy(personaId = 8)
        coEvery { personas.getPersona(8) } returns PersonaEntity(id = 8, name = "此刻的角色", personality = "安静", isRoleplay = true, createdAt = 1)
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1")); emit(AgentEvent.RoundCommitted(committed))
        }
        runner.send(conversation, "你好")
        withTimeout(5_000) { runner.states.first { it[1]?.running == false } }
        coVerify(exactly = 1) { tools.forConversation(any(), 8) }
        coVerify(exactly = 1) { personas.getPersona(8) }
        coEvery { chats.getConversation(1) } returns conversation.copy(type = "SELECTION")
        assertTrue(runCatching { runner.send(conversation, "不应保存") }.isFailure)
        coVerify(exactly = 1) { chats.appendUserMessage(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun stopDoesNotPersistUnverifiedTextAfterTheSourceIsRevised() = runBlocking {
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1")); emit(AgentEvent.Text("尚未核实")); awaitCancellation()
        }
        runner.send(conversation, "问题")
        withTimeout(5_000) { runner.states.first { it[1]?.text == "尚未核实" } }
        assertTrue(runCatching { runner.send(conversation, "重复发送") }.isFailure)
        coEvery { guard.validate(any<List<LibraryBookScope>>()) } throws IllegalStateException("正文变更")
        runner.stop(1)
        assertFalse(tracker.isActive(1))
        assertTrue(runner.states.value.getValue(1).committed.isEmpty())
        coVerify(exactly = 0) { chats.appendAssistantMessage(any(), any(), any(), any()) }
    }

    @Test fun retryContinuesTheExistingQuestionWithoutAppendingAnotherUserMessage() = runBlocking {
        val user = MessageEntity(id = 2, conversationId = 1, role = "user", content = "原来的提问", createdAt = 1, clientRoundId = "original-user", sourceBookIdsJson = "[]")
        coEvery { chats.getMessages(1) } returns listOf(user)
        coEvery { chats.prepareRetry(2) } returns 1
        coEvery { chats.ensureUserRoundId(2) } returns "original-user"
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1")); emit(AgentEvent.RoundCommitted(committed))
        }
        runner.retry(1)
        withTimeout(5_000) { runner.states.first { it[1]?.running == false } }
        coVerify(exactly = 0) { chats.appendUserMessage(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { chats.updateLibraryContext(1, "original-user", "[]", "[]") }
    }

    @Test fun failedScopePreflightDoesNotDestroyTheReplyBeingRerolled() = runBlocking {
        val user = MessageEntity(id = 2, conversationId = 1, role = "user", content = "问题", createdAt = 1)
        coEvery { chats.getMessages(1) } returns listOf(user, committed)
        coEvery { guard.refresh(any()) } throws IllegalStateException("阅读范围已重置")
        assertTrue(runCatching { runner.reroll(1, 9) }.isFailure)
        coVerify(exactly = 0) { chats.prepareReroll(any()) }
        verify(exactly = 0) { loop.run(any(), any(), any(), any(), any(), any()) }
    }

    @Test fun editingAnAssistantClearsEventFirstCopiesAndRunningHistoryIsLocked() = runBlocking {
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow {
            emit(AgentEvent.RoundStarted("r1")); emit(AgentEvent.RoundCommitted(committed))
        }
        runner.send(conversation, "问题")
        withTimeout(5_000) { runner.states.first { it[1]?.running == false } }
        coEvery { chats.getMessages(1) } returns listOf(committed)
        coEvery { chats.editMessage(9, "新版回答") } returns com.mozhi.reader.ai.chat.MessageEditResult(1, "assistant", false)
        runner.editMessage(1, 9, "新版回答")
        assertNull(runner.states.value[1])
        every { loop.run(any(), any(), any(), any(), any(), any()) } returns flow { awaitCancellation() }
        runner.send(conversation, "新问题")
        assertTrue(runCatching { runner.deleteMessage(1, 9) }.isFailure)
        coVerify(exactly = 0) { chats.deleteMessage(any()) }
        runner.stop(1)
        Unit
    }
}
