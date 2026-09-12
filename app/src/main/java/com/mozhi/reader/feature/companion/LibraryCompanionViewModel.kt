package com.mozhi.reader.feature.companion

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.companion.LibraryBookScope
import com.mozhi.reader.ai.companion.LibraryBookScopes
import com.mozhi.reader.ai.companion.LibraryCitation
import com.mozhi.reader.ai.companion.LibraryCitationVerifier
import com.mozhi.reader.ai.companion.LibraryCompanionRunner
import com.mozhi.reader.ai.companion.LibraryReplyState
import com.mozhi.reader.ai.companion.LocatedLibraryCitation
import com.mozhi.reader.ai.companion.LibraryOrganizationCoordinator
import com.mozhi.reader.ai.companion.LibraryOrganizationMessage
import com.mozhi.reader.ai.companion.LibraryOrganizationPlans
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.dao.ChatDao
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryChatSession(
    val conversation: ConversationEntity? = null,
    val scopes: List<LibraryBookScope> = emptyList(),
    val selectedBooks: Set<Long> = emptySet(),
    val selectedPersonaId: Long? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val error: String? = null
)

data class LibraryChatCatalog(val books: List<BookEntity> = emptyList(), val personas: List<PersonaEntity> = emptyList(), val activePersonaId: Long? = null,
    val settings: com.mozhi.reader.core.datastore.ReaderSettings = com.mozhi.reader.core.datastore.ReaderSettings())
data class LibraryChatMessages(val rows: List<MessageEntity> = emptyList(), val reply: LibraryReplyState = LibraryReplyState(), val conversationId: Long? = null,
    val organizationPlans: List<LibraryOrganizationMessage> = emptyList())

sealed interface LibraryChatEvent {
    data class Notice(val text: String) : LibraryChatEvent
    data class OpenSource(val location: LocatedLibraryCitation) : LibraryChatEvent
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryCompanionViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val chats: AiChatRepository,
    chatDao: ChatDao,
    library: LibraryRepository,
    personas: PersonaRepository,
    settings: ReaderSettingsRepository,
    private val runner: LibraryCompanionRunner,
    private val citations: LibraryCitationVerifier,
    private val organization: LibraryOrganizationCoordinator
) : ViewModel() {
    private val mutable = MutableStateFlow(LibraryChatSession(
        draft = savedState["draft"] ?: "",
        selectedBooks = savedState.get<LongArray>("selectedBooks")?.take(4)?.toSet().orEmpty(),
        selectedPersonaId = savedState["selectedPersonaId"]
    ))
    val session = mutable.asStateFlow()
    private val conversationId = MutableStateFlow<Long?>(null)
    private val channel = Channel<LibraryChatEvent>(Channel.BUFFERED)
    val events = channel.receiveAsFlow()
    val catalog = combine(library.observeBooks(), personas.observePersonas(), settings.activePersonaId, settings.settings) { books, roles, active, preferences ->
        LibraryChatCatalog(books, roles, active.takeIf { id -> roles.any { it.id == id } } ?: roles.firstOrNull()?.id, preferences)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryChatCatalog())
    val conversations = chatDao.observeLibraryConversations().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val messages = conversationId.flatMapLatest { id ->
        if (id == null) flowOf(LibraryChatMessages()) else combine(chats.observeMessages(id), runner.states) { rows, states ->
            val reply = states[id] ?: LibraryReplyState()
            LibraryChatMessages((rows + reply.committed).distinctBy { it.id }.sortedBy { it.id }
                .filter { it.role in setOf("user", "assistant") && it.content.isNotBlank() }, reply, id,
                rows.mapNotNull(LibraryOrganizationPlans::fromMessage))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryChatMessages())

    init {
        savedState.get<Long>("conversationId")?.let { open(it, restoring = true) }
        viewModelScope.launch {
            conversations.collect { rows ->
                val id = conversationId.value
                rows.firstOrNull { it.id == id }?.let { latest ->
                    val scopes = runCatching { LibraryBookScopes.decode(latest.bookScopesJson) }.getOrNull() ?: return@let
                    mutable.update { if (it.conversation?.id == id) it.copy(conversation = latest, scopes = scopes) else it }
                }
            }
        }
    }

    fun setDraft(value: String) {
        val bounded = value.take(8_000)
        mutable.update { it.copy(draft = bounded) }
        savedState["draft"] = bounded
    }

    fun toggleBook(id: Long) {
        if (mutable.value.busy) return
        mutable.update {
            val selected = it.selectedBooks
            if (id in selected) it.copy(selectedBooks = selected - id, error = null)
            else if (selected.size < LibraryBookScopes.MAX_FOCUS_BOOKS) it.copy(selectedBooks = selected + id, error = null)
            else it.copy(error = "最多选 4 本重点书籍")
        }
        savedState["selectedBooks"] = mutable.value.selectedBooks.toLongArray()
    }

    fun selectPersona(id: Long) {
        if (!mutable.value.busy && !messages.value.reply.running && mutable.value.draft.isBlank()) {
            if (mutable.value.conversation != null) resetConversation()
            mutable.update { it.copy(selectedPersonaId = id) }
            savedState["selectedPersonaId"] = id
        }
    }

    fun newConversation() {
        if (mutable.value.busy) return
        resetConversation()
    }

    private fun resetConversation() {
        conversationId.value = null
        savedState["conversationId"] = null
        savedState["draft"] = ""
        savedState["selectedBooks"] = longArrayOf()
        savedState["selectedPersonaId"] = null
        mutable.value = LibraryChatSession()
    }

    fun open(id: Long, restoring: Boolean = false) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                activate(id, restoring)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun activate(id: Long, keepDraft: Boolean = false) {
        val conversation = chats.getConversation(id) ?: error("话题已被删除")
        require(conversation.type == LibraryBookScopes.CONVERSATION_TYPE && conversation.bookId == null)
        val scopes = LibraryBookScopes.decode(conversation.bookScopesJson)
        val draft = if (keepDraft) mutable.value.draft else ""
        val focused = if (keepDraft) mutable.value.selectedBooks else scopes.take(4).map { it.bookId }.toSet()
        mutable.value = LibraryChatSession(conversation, scopes, focused, conversation.personaId, draft, busy = true)
        conversationId.value = id
        savedState["conversationId"] = id
        savedState["draft"] = draft
        savedState["selectedBooks"] = focused.toLongArray()
        savedState["selectedPersonaId"] = conversation.personaId
    }

    fun send() {
        val snapshot = mutable.value
        if (snapshot.busy || snapshot.draft.isBlank() || messages.value.reply.running) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val conversation = snapshot.conversation ?: withContext(Dispatchers.IO) {
                    val id = chats.startConversation(
                        bookId = null, title = AiChatRepository.NEW_CONVERSATION_TITLE,
                        type = LibraryBookScopes.CONVERSATION_TYPE, systemPrompt = "书库伴读：按需查书，每本书独立限制已读范围。",
                        firstUserMessage = null, personaId = snapshot.selectedPersonaId ?: catalog.value.activePersonaId,
                        bookScopesJson = "[]"
                    )
                    checkNotNull(chats.getConversation(id))
                }
                val scopes = LibraryBookScopes.decode(conversation.bookScopesJson)
                mutable.update { it.copy(conversation = conversation, scopes = scopes) }
                conversationId.value = conversation.id
                savedState["conversationId"] = conversation.id
                runner.send(conversation, snapshot.draft.trim(), snapshot.selectedBooks.toList())
                // The composer stays editable during preparation; do not erase a newer draft.
                if (mutable.value.draft == snapshot.draft) setDraft("")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "无法发送消息") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun stop() { conversationId.value?.let { id -> viewModelScope.launch { runner.stop(id) } } }

    fun editMessage(id: Long, content: String) = historyAction { conversation -> runner.editMessage(conversation, id, content) }
    fun deleteMessage(id: Long) = historyAction { conversation -> runner.deleteMessage(conversation, id) }
    fun reroll(id: Long) = historyAction { conversation -> runner.reroll(conversation, id) }
    fun retry() = historyAction { conversation -> runner.retry(conversation) }
    fun branchFrom(id: Long) = historyAction { conversation -> activate(runner.branchFrom(conversation, id), keepDraft = true) }

    private fun historyAction(action: suspend (Long) -> Unit) {
        val id = conversationId.value ?: return
        if (mutable.value.busy || runner.states.value[id]?.running == true) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { action(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { channel.send(LibraryChatEvent.Notice(error.message ?: "无法修改这段对话")) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun confirmOrganization(messageId: Long, apply: Boolean) {
        if (mutable.value.busy || messages.value.reply.running) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { organization.confirm(messageId, apply) }
                channel.send(LibraryChatEvent.Notice(if (apply) "已整理 $count 本书" else "已取消方案"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { channel.send(LibraryChatEvent.Notice(error.message ?: "整理失败")) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun delete(id: Long) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                runner.deleteConversation(id)
                if (conversationId.value == id) resetConversation()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { channel.send(LibraryChatEvent.Notice(error.message ?: "删除失败")) }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun rename(id: Long, title: String) {
        viewModelScope.launch {
            try {
                chats.renameConversation(id, title)
                val updated = chats.getConversation(id)
                if (conversationId.value == id) mutable.update { it.copy(conversation = updated) }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { channel.send(LibraryChatEvent.Notice(error.message ?: "重命名失败")) }
        }
    }

    fun locate(citation: LibraryCitation) {
        val scopes = mutable.value.scopes
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { citations.locate(citation, scopes) }
                channel.send(if (result == null) LibraryChatEvent.Notice("未找到已读原文，无法跳转") else LibraryChatEvent.OpenSource(result))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { channel.send(LibraryChatEvent.Notice(error.message ?: "无法核对引文")) }
        }
    }
}
