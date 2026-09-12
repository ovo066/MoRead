package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.LibraryCompanionToolset
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.ai.prompt.LibraryCompanionPrompt
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.datastore.UserMaskStore
import com.mozhi.reader.core.di.ApplicationScope
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class LibraryReplyState(
    val running: Boolean = false,
    val roundId: String? = null,
    val text: String = "",
    val status: String? = null,
    val error: String? = null,
    /** Event-first commits bridge the delay before the Room observer, keyed by the real row ID. */
    val committed: List<MessageEntity> = emptyList()
)

/** Owns generation outside the screen. All mutable streaming buffers stay on Main. */
@Singleton
class LibraryCompanionRunner @Inject constructor(
    private val chats: AiChatRepository,
    private val personas: PersonaRepository,
    private val masks: UserMaskStore,
    private val guard: LibraryScopeGuard,
    private val tools: LibraryCompanionToolset,
    private val loop: AgentLoop,
    private val tracker: CompanionGenerationTracker,
    @ApplicationScope private val applicationScope: CoroutineScope
) {
    private val mutable = MutableStateFlow<Map<Long, LibraryReplyState>>(emptyMap())
    val states = mutable.asStateFlow()
    private val mutex = Mutex()
    private val jobs = mutableMapOf<Long, Job>()

    suspend fun send(conversation: ConversationEntity, text: String, focusBookIds: List<Long> = emptyList()) = historyLock {
        require(conversation.bookId == null && conversation.type == LibraryBookScopes.CONVERSATION_TYPE)
        require(text.isNotBlank() && text.length <= 8_000) { "消息长度应为 1–8000 字" }
        val ready = prepare(conversation.id, focusBookIds)
        val userRoundId = UUID.randomUUID().toString()
        chats.appendUserMessage(conversation.id, text, clientRoundId = userRoundId, maskId = ready.maskId)
        startReply(ready, userRoundId)
    }

    suspend fun editMessage(conversationId: Long, messageId: Long, content: String) = historyLock {
        val target = editableMessage(conversationId, messageId)
        require(content.isNotBlank()) { "消息不能为空" }
        require(target.role != "user" || content.length <= 8_000) { "提问最多 8000 字" }
        if (target.role == "user") {
            // All source/persona checks precede the destructive truncation.
            val ready = prepare(conversationId, focusFor(target), target)
            chats.editMessage(messageId, content)
            startReply(ready, chats.ensureUserRoundId(messageId))
        } else {
            chats.editMessage(messageId, content)
            clearFinishedReply(conversationId)
        }
    }

    suspend fun reroll(conversationId: Long, messageId: Long) = historyLock {
        val target = editableMessage(conversationId, messageId)
        require(target.role == "assistant") { "只能重新生成 AI 回复" }
        val user = chats.getMessages(conversationId).lastOrNull { it.id < target.id && it.role == "user" }
            ?: error("找不到这条回复对应的用户消息")
        val ready = prepare(conversationId, focusFor(user), user)
        chats.prepareReroll(messageId)
        startReply(ready, chats.ensureUserRoundId(user.id))
    }

    suspend fun retry(conversationId: Long) = historyLock {
        requireIdle(conversationId)
        val user = chats.getMessages(conversationId).lastOrNull { it.role == "user" }
            ?: error("没有需要回复的消息")
        val ready = prepare(conversationId, focusFor(user), user)
        chats.prepareRetry(user.id)
        startReply(ready, chats.ensureUserRoundId(user.id))
    }

    suspend fun deleteMessage(conversationId: Long, messageId: Long) = historyLock {
        editableMessage(conversationId, messageId)
        chats.deleteMessage(messageId)
        clearFinishedReply(conversationId)
    }

    suspend fun branchFrom(conversationId: Long, messageId: Long): Long = historyLock {
        editableMessage(conversationId, messageId)
        chats.branchConversation(conversationId, messageId)
    }

    suspend fun deleteConversation(conversationId: Long) = historyLock {
        stop(conversationId)
        chats.deleteConversation(conversationId)
        mutable.update { it - conversationId }
    }

    private suspend fun <T> historyLock(action: suspend () -> T): T = withContext(Dispatchers.Main.immediate) {
        mutex.withLock { action() }
    }

    private suspend fun requireIdle(id: Long): ConversationEntity {
        require(!tracker.isActive(id)) { "请先停止本话题的回复" }
        val current = chats.getConversation(id) ?: error("话题已被删除")
        require(current.bookId == null && current.type == LibraryBookScopes.CONVERSATION_TYPE) { "不是书库伴读话题" }
        return current
    }

    private suspend fun editableMessage(conversationId: Long, messageId: Long): MessageEntity {
        requireIdle(conversationId)
        return chats.getMessages(conversationId).firstOrNull { it.id == messageId && it.role in setOf("user", "assistant") }
            ?: error("消息不存在或不属于当前话题")
    }

    private fun focusFor(user: MessageEntity): List<Long> = user.sourceBookIdsJson
        ?.let(LibraryBookScopes::decodeTurnBooks)?.take(LibraryBookScopes.MAX_FOCUS_BOOKS).orEmpty()

    private data class PreparedTurn(val conversation: ConversationEntity, val scopes: List<LibraryBookScope>,
        val focused: List<Long>, val prompt: String, val maskId: Long)

    private suspend fun prepare(id: Long, focusBookIds: List<Long>, user: MessageEntity? = null): PreparedTurn {
        val current = requireIdle(id)
        val focus = focusBookIds.distinct()
        require(focus.size <= LibraryBookScopes.MAX_FOCUS_BOOKS)
        return withContext(Dispatchers.IO) {
            val priorScopes = if (user == null) LibraryBookScopes.decode(current.bookScopesJson) else LibraryBookScopes.retainedForHistory(
                current.bookScopesJson, chats.getMessages(id).filter { it.id <= user.id && it.role == "user" }.map { it.sourceBookIdsJson })
            val previous = guard.refresh(priorScopes)
            val missing = focus.filter { bookId -> previous.none { it.bookId == bookId } }
            val scopes = previous + if (missing.isEmpty()) emptyList() else guard.capture(missing)
            LibraryBookScopes.encode(scopes)
            val persona = current.personaId?.let { personas.getPersona(it) ?: error("角色已删除，请新建话题") }
            val mask = when {
                user == null -> masks.activeMask()
                user.maskId == 0L -> null
                else -> masks.settings.first().masks.firstOrNull { it.id == user.maskId }
                    ?: error("这条消息使用的面具已删除，请新建话题")
            }
            PreparedTurn(current, scopes, focus, LibraryCompanionPrompt.build(persona, mask, scopes, focus), mask?.id ?: 0L)
        }
    }

    private fun clearFinishedReply(id: Long) { mutable.update { it - id } }

    private suspend fun startReply(ready: PreparedTurn, userRoundId: String) {
            val current = ready.conversation
            val id = current.id
            // Replacing the state clears event-first commits from the deleted/edited generation.
            clearFinishedReply(id)
            val sources = LibraryConversationSources(id, userRoundId, ready.scopes, guard, chats, ready.focused)
            withContext(Dispatchers.IO) { sources.persist() }
            update(id) { LibraryReplyState(running = true, status = "正在准备回复…") }
            val job = applicationScope.launch(Dispatchers.Main.immediate, start = CoroutineStart.LAZY) {
                val buffer = StringBuilder()
                var roundId: String? = null
                val ticker = launch {
                    while (isActive) {
                        delay(50)
                        val snapshot = buffer.toString()
                        update(id) { it.copy(text = snapshot) }
                    }
                }
                try {
                    val capabilities = withContext(Dispatchers.IO) { tools.forConversation(sources, current.personaId) }
                    loop.run(id, capabilities, ready.prompt,
                        maxRounds = 4, validateContext = sources::validate)
                        .buffer(32).flowOn(Dispatchers.IO).collect { event ->
                            when (event) {
                                is AgentEvent.RoundStarted -> {
                                    roundId = event.roundId
                                    buffer.setLength(0)
                                    update(id) { it.copy(roundId = event.roundId, text = "", status = "正在回复…") }
                                }
                                is AgentEvent.Text -> buffer.append(event.text)
                                is AgentEvent.Reasoning -> Unit
                                is AgentEvent.RoundCommitted -> {
                                    if (event.message.clientRoundId == roundId) buffer.setLength(0)
                                    update(id) { it.copy(text = buffer.toString(), committed = (it.committed + event.message).distinctBy { row -> row.id }) }
                                }
                                is AgentEvent.ToolRun -> update(id) { it.copy(status = event.displayName) }
                                is AgentEvent.ToolFinished -> update(id) { it.copy(status = if (event.succeeded) "继续回复…" else event.detail) }
                            }
                        }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { savePartialIfCurrent(id, sources.all, buffer.toString(), roundId) }
                    update(id) { it.copy(status = "已停止") }
                    throw cancelled
                } catch (error: Exception) {
                    // A scope/source rejection must not persist unverified, newly generated text.
                    savePartialIfCurrent(id, sources.all, buffer.toString(), roundId)
                    update(id) { it.copy(error = error.message ?: "生成失败，请检查模型设置或网络", text = "") }
                } finally {
                    ticker.cancel()
                    tracker.end(id)
                    jobs.remove(id)
                    update(id) { it.copy(running = false, text = "", status = it.status?.takeIf { status -> status.startsWith("已停止") }) }
                    prune()
                }
            }
            jobs[id] = job
            tracker.begin(id, job)
            job.start()
    }

    private suspend fun savePartialIfCurrent(id: Long, scopes: List<LibraryBookScope>, text: String, roundId: String?) {
        val valid = withContext(Dispatchers.IO) { runCatching { guard.validate(scopes) }.isSuccess }
        if (valid) savePartial(id, text, roundId)
    }

    private suspend fun savePartial(id: Long, text: String, roundId: String?) {
        if (text.isBlank() || roundId == null) return
        runCatching { chats.appendAssistantMessage(id, text, clientRoundId = roundId) }.getOrNull()?.let { message ->
            update(id) { it.copy(committed = (it.committed + message).distinctBy { row -> row.id }) }
        }
    }

    suspend fun stop(id: Long) = withContext(Dispatchers.Main.immediate) {
        jobs[id]?.let { it.cancel(); it.join() }
    }

    suspend fun forget(id: Long) {
        stop(id)
        mutable.update { it - id }
    }

    private fun update(id: Long, change: (LibraryReplyState) -> LibraryReplyState) {
        mutable.update { it + (id to change(it[id] ?: LibraryReplyState())) }
    }

    private fun prune() {
        mutable.update { states ->
            val finished = states.filterValues { !it.running }.keys.toList()
            states - finished.take((finished.size - 16).coerceAtLeast(0)).toSet()
        }
    }
}
