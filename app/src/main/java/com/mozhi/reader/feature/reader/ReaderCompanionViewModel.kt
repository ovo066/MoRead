package com.mozhi.reader.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.ai.chat.ReplySuggestionService
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.agent.MemoryScope
import com.mozhi.reader.ai.memory.MemoryConsolidationScheduler
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.ai.prompt.CompanionContextBuilder
import com.mozhi.reader.ai.search.WebSearchSettingsStore
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.chatAppearance
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.database.entity.enabledTools
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.datastore.UserMaskStore
import com.mozhi.reader.core.library.AttachmentStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.MessageAttachment
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import com.mozhi.reader.core.library.BookQuoteLocator
import com.mozhi.reader.core.library.QuoteChapter
import com.mozhi.reader.core.library.QuoteLocation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AgentStepState { RUNNING, SUCCEEDED, FAILED }

data class AgentExecutionStep(
    val callId: String,
    val toolName: String,
    val displayName: String,
    val state: AgentStepState,
    val detail: String = ""
)

/** 输入区待发送的附件（尚未落盘）；发送时经 AttachmentStore 压缩/保存。 */
data class PendingAttachment(
    val uri: Uri,
    val isImage: Boolean,
    val name: String
)

/** 全屏聊天页顶栏与 sceneQuote 素材。 */
/** 聊天页要交给阅读页去做的事。 */
sealed interface CompanionChatEvent {
    /** 跳到正文某处并短暂高亮；坐标是章内 UTF-16 偏移。 */
    data class LocateInBook(
        val chapterIndex: Int,
        val startCharOffset: Int,
        val endCharOffset: Int
    ) : CompanionChatEvent

    data class Message(val text: String) : CompanionChatEvent
}

data class CompanionChatContext(
    val bookTitle: String = "",
    val sceneQuote: String = ""
)

data class CompanionChatUiState(
    val personas: List<PersonaEntity> = emptyList(),
    val activePersona: PersonaEntity? = null,
    val conversationId: Long? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<MessageEntity> = emptyList(),
    val streamingText: String? = null,
    val isStreaming: Boolean = false,
    /** Status line while a tool runs, e.g. "正在检索书中原文…". */
    val toolStatus: String? = null,
    val executionSteps: List<AgentExecutionStep> = emptyList(),
    val embeddingProgress: BookEmbeddingProgress? = null,
    /** Default-on guard that limits book tools to the user's current reading position. */
    val spoilerProtectionEnabled: Boolean = true,
    /** AI 回合结束后生成的建议回复，点击即发送；发送/切换会话时清空。 */
    val suggestions: List<String> = emptyList(),
    /** 当前角色的聊天外观；未自定义时是 DEFAULT，界面据此跟随阅读主题。 */
    val appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    /** 外观选中的背景图的真实路径；图片被删掉时为 null。 */
    val backgroundImagePath: String? = null,
    /** 共享字体库，供气泡按 [appearance] 的 fontId 取字体。 */
    val fontLibrary: List<ReaderFontAsset> = emptyList(),
    val error: String? = null
)

/**
 * 伴读会话（阅读页悬浮球 / dock「伴读」）：当前角色来自伴读页选定的
 * activePersonaId（DataStore，双向同步），对话经 AgentLoop 真跑——
 * 系统提示词每轮由 CompanionContextBuilder 按最新进度、记忆与世界书重建，
 * 工具集按角色白名单过滤。会话按（书, 角色）持久续接，切角色即切会话。
 */
@HiltViewModel
class ReaderCompanionViewModel @Inject constructor(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val chatRepository: AiChatRepository,
    private val agentLoop: AgentLoop,
    private val readerToolset: ReaderToolset,
    private val contextBuilder: CompanionContextBuilder,
    private val memoryScheduler: MemoryConsolidationScheduler,
    private val embeddingProgressTracker: EmbeddingProgressTracker,
    private val attachmentStore: AttachmentStore,
    private val libraryRepository: LibraryRepository,
    private val suggestionService: ReplySuggestionService,
    private val webSearchSettingsStore: WebSearchSettingsStore,
    private val userMaskStore: UserMaskStore,
    private val generationTracker: CompanionGenerationTracker,
    @ApplicationScope private val applicationScope: CoroutineScope
) : ViewModel() {

    private val bookId = MutableStateFlow<Long?>(null)
    private val session = MutableStateFlow(SessionState())
    private var streamJob: Job? = null
    private var messagesJob: Job? = null
    private var conversationsJob: Job? = null
    private var suggestionJob: Job? = null
    private var generationJob: Job? = null
    private val eventChannel = Channel<CompanionChatEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /**
     * 流式正文的真源。SSE 增量只追加到这里（主线程独占），UI 快照由节拍器按
     * [STREAM_UI_TICK_MS] 发布到 session，避免逐 token 重组整页与 O(n²) 拼串。
     */
    private val streamBuffer = StringBuilder()

    private data class SessionState(
        val conversationId: Long? = null,
        val conversations: List<ConversationEntity> = emptyList(),
        val messages: List<MessageEntity> = emptyList(),
        val streamingText: String? = null,
        val isStreaming: Boolean = false,
        val toolStatus: String? = null,
        val executionSteps: List<AgentExecutionStep> = emptyList(),
        val suggestions: List<String> = emptyList(),
        val error: String? = null
    )

    private val activePersona = combine(
        personaRepository.observePersonas(),
        settingsRepository.activePersonaId
    ) { personas, storedId ->
        personas to (personas.find { it.id == storedId } ?: personas.firstOrNull())
    }

    private val embeddingProgress = bookId
        .filterNotNull()
        .flatMapLatest(embeddingProgressTracker::observeBook)

    /** 独立全屏聊天页用：书名 + 当前进度章节题（作为 sceneQuote）。 */
    val chatContext = bookId
        .filterNotNull()
        .flatMapLatest { id ->
            combine(
                libraryRepository.observeBook(id),
                libraryRepository.observeChapters(id)
            ) { book, chapters ->
                val chapterTitle = book?.lastReadChapterIndex
                    ?.let { chapters.getOrNull(it)?.title }
                    .orEmpty()
                CompanionChatContext(
                    bookTitle = book?.title.orEmpty(),
                    sceneQuote = chapterTitle.ifBlank { book?.title.orEmpty() }
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = CompanionChatContext()
        )

    val uiState = combine(
        activePersona,
        session,
        embeddingProgress,
        settingsRepository.companionSpoilerProtectionEnabled,
        settingsRepository.settings
    ) { (personas, active), session, embedding, spoilerProtectionEnabled, readerSettings ->
        val appearance = active?.chatAppearance() ?: PersonaChatAppearance.DEFAULT
        CompanionChatUiState(
            personas = personas,
            activePersona = active,
            conversationId = session.conversationId,
            conversations = session.conversations,
            messages = session.messages,
            streamingText = session.streamingText,
            isStreaming = session.isStreaming,
            toolStatus = session.toolStatus,
            executionSteps = session.executionSteps,
            embeddingProgress = embedding,
            spoilerProtectionEnabled = spoilerProtectionEnabled,
            suggestions = session.suggestions,
            appearance = appearance,
            backgroundImagePath = readerSettings.imageLibrary
                .firstOrNull { it.id == appearance.backgroundImageId }
                ?.filePath,
            fontLibrary = readerSettings.fontLibrary,
            error = session.error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CompanionChatUiState()
    )

    init {
        // （书, 角色）变化 → 挂到最近会话，同时持续观察该组合的全部历史；用户可随时
        // 新建、切换或删除，不再把一整本书锁死为单一会话。
        viewModelScope.launch {
            combine(bookId, activePersona) { book, (_, active) -> book to active?.id }
                .distinctUntilChanged()
                .collect { (book, personaId) ->
                    session.value.conversationId?.let(memoryScheduler::onConversationClosed)
                    streamJob?.cancel()
                    messagesJob?.cancel()
                    conversationsJob?.cancel()
                    suggestionJob?.cancel()
                    session.value = SessionState()
                    if (book != null && personaId != null) {
                        val existing = chatRepository.findLatestConversation(
                            bookId = book,
                            personaId = personaId,
                            type = CONVERSATION_TYPE
                        )
                        session.value = SessionState(conversationId = existing?.id)
                        existing?.id?.let(::observeMessages)
                        observeConversations(book, personaId)
                    }
                }
        }
    }

    fun bind(bookId: Long) {
        this.bookId.value = bookId
    }

    /** 切换伴读角色：写 DataStore，与伴读页共用同一份选择。 */
    fun selectPersona(personaId: Long) {
        viewModelScope.launch { settingsRepository.setActivePersonaId(personaId) }
    }

    fun setSpoilerProtectionEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setCompanionSpoilerProtectionEnabled(value) }
    }

    fun retryEmbedding() {
        bookId.value?.let(embeddingProgressTracker::retry)
    }

    fun newConversation() {
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        if (session.value.isStreaming) return
        viewModelScope.launch {
            runCatching {
                session.value.conversationId?.let(memoryScheduler::onConversationClosed)
                createConversation(book, persona)
            }.onFailure { error ->
                session.value = session.value.copy(error = error.userMessage())
            }
        }
    }

    fun selectConversation(conversationId: Long) {
        if (conversationId == session.value.conversationId || session.value.isStreaming) return
        if (session.value.conversations.none { it.id == conversationId }) return
        session.value.conversationId?.let(memoryScheduler::onConversationClosed)
        activateConversation(conversationId)
    }

    fun renameConversation(conversationId: Long, title: String) {
        if (session.value.isStreaming) return
        viewModelScope.launch {
            runCatching { chatRepository.renameConversation(conversationId, title) }
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    fun deleteConversation(conversationId: Long) {
        if (session.value.isStreaming) return
        viewModelScope.launch {
            runCatching { chatRepository.deleteConversation(conversationId) }
                .onSuccess {
                    if (session.value.conversationId == conversationId) {
                        val fallback = session.value.conversations.firstOrNull {
                            it.id != conversationId
                        }?.id
                        if (fallback == null) {
                            messagesJob?.cancel()
                            session.value = session.value.copy(
                                conversationId = null,
                                messages = emptyList(),
                                executionSteps = emptyList()
                            )
                        } else {
                            activateConversation(fallback)
                        }
                    }
                }
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    fun editMessage(messageId: Long, content: String, sceneQuote: String) {
        if (session.value.isStreaming) return
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        viewModelScope.launch {
            runCatching { chatRepository.editMessage(messageId, content) }
                .onSuccess { result ->
                    if (result.shouldRegenerate) {
                        stream(book, persona, result.conversationId, sceneQuote, content.trim())
                    }
                }
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    fun deleteMessage(messageId: Long) {
        if (session.value.isStreaming) return
        viewModelScope.launch {
            runCatching { chatRepository.deleteMessage(messageId) }
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    fun reroll(messageId: Long, sceneQuote: String) {
        if (session.value.isStreaming) return
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        viewModelScope.launch {
            runCatching { chatRepository.prepareReroll(messageId) }
                .onSuccess { conversationId ->
                    stream(book, persona, conversationId, sceneQuote, memoryQuery = null)
                }
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    fun branchFrom(messageId: Long) {
        val currentConversation = session.value.conversationId ?: return
        if (session.value.isStreaming) return
        viewModelScope.launch {
            runCatching {
                chatRepository.branchConversation(currentConversation, messageId)
            }.onSuccess(::activateConversation)
                .onFailure { error ->
                    session.value = session.value.copy(error = error.userMessage())
                }
        }
    }

    /** [sceneQuote] 为当前阅读位置附近的原文/章节题，作为场景与世界书触发材料。 */
    fun send(
        text: String,
        sceneQuote: String,
        attachments: List<PendingAttachment> = emptyList()
    ) {
        sendWithRequiredTools(text, sceneQuote, emptySet(), attachments)
    }

    fun generatePlotSummary(sceneQuote: String) {
        sendWithRequiredTools(
            text = "请生成并保存截至我当前阅读进度的剧情梗概。先查询进度，再用 read_book_section 从第 1 章开始读取已读正文；若提示内容未完，按 start_char 继续，不能用零散搜索片段代替指定范围。最后务必调用 save_plot_summary 保存。",
            sceneQuote = sceneQuote,
            requiredTools = setOf(
                "get_reading_progress",
                "read_book_section",
                "save_plot_summary"
            )
        )
    }

    private fun sendWithRequiredTools(
        text: String,
        sceneQuote: String,
        requiredTools: Set<String>,
        attachments: List<PendingAttachment> = emptyList()
    ) {
        val trimmed = text.trim()
        if ((trimmed.isEmpty() && attachments.isEmpty()) || session.value.isStreaming) return
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        viewModelScope.launch {
            try {
                val conversationId = session.value.conversationId
                    ?: createConversation(book, persona)
                val saved = attachments.mapNotNull { pending ->
                    if (pending.isImage) {
                        attachmentStore.saveImage(conversationId, pending.uri)
                    } else {
                        attachmentStore.saveTextFile(conversationId, pending.uri, pending.name)
                    }
                }
                chatRepository.appendUserMessage(
                    conversationId = conversationId,
                    content = trimmed.ifEmpty { "（发来附件）" },
                    attachmentsJson = MessageAttachment.encode(saved),
                    // 发送时就把面具钉在消息上：固化是异步的，那时面具可能已经切换。
                    maskId = userMaskStore.activeMask()?.id ?: 0L
                )
                stream(
                    book = book,
                    persona = persona,
                    conversationId = conversationId,
                    sceneQuote = sceneQuote,
                    memoryQuery = trimmed,
                    requiredTools = requiredTools
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                session.value = session.value.copy(error = error.userMessage())
            }
        }
    }

    /** Cancels the stream and keeps whatever arrived as a persisted partial reply. */
    fun stop() {
        val partial = streamBuffer.toString()
        val conversationId = session.value.conversationId
        streamJob?.cancel()
        streamJob = null
        // 也可能是上一次进来时留下的后台生成：那时本 ViewModel 手里没有它的 Job。
        conversationId?.let(generationTracker::cancel)
        streamBuffer.setLength(0)
        session.value = session.value.copy(
            isStreaming = false,
            streamingText = null,
            toolStatus = null
        )
        if (partial.isNotBlank() && conversationId != null) {
            viewModelScope.launch {
                chatRepository.appendAssistantMessage(conversationId, partial)
                memoryScheduler.afterTurn(conversationId)
            }
        }
    }

    fun retry(sceneQuote: String) {
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        val conversationId = session.value.conversationId ?: return
        if (session.value.isStreaming) return
        session.value = session.value.copy(error = null)
        viewModelScope.launch {
            stream(book, persona, conversationId, sceneQuote, memoryQuery = null)
        }
    }

    /** 用户明确划掉建议条时调用；下轮 AI 回复后会重新生成。 */
    fun dismissSuggestions() {
        suggestionJob?.cancel()
        session.value = session.value.copy(suggestions = emptyList())
    }

    /**
     * AI 回合收尾后为用户拟建议回复。独立 job：不阻塞流式收尾，切会话/新发送
     * 时取消；生成失败静默丢弃（建议属于锦上添花，不值得打扰）。
     */
    private fun refreshSuggestions(persona: PersonaEntity, conversationId: Long) {
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            if (!settingsRepository.suggestionRepliesEnabled.first()) return@launch
            val suggestions = runCatching {
                suggestionService.suggest(
                    personaName = persona.name,
                    bookTitle = chatContext.value.bookTitle,
                    history = chatRepository.getMessages(conversationId)
                )
            }.getOrDefault(emptyList())
            if (suggestions.isNotEmpty() &&
                session.value.conversationId == conversationId &&
                !session.value.isStreaming
            ) {
                session.value = session.value.copy(suggestions = suggestions)
            }
        }
    }

    private suspend fun createConversation(book: Long, persona: PersonaEntity): Long {
        val conversationId = chatRepository.startConversation(
            bookId = book,
            title = AiChatRepository.NEW_CONVERSATION_TITLE,
            type = CONVERSATION_TYPE,
            // 落库的 system 只是快照；每轮真正生效的由 ContextBuilder 重建。
            systemPrompt = contextBuilder.build(
                persona = persona,
                bookId = book,
                spoilerProtectionEnabled = settingsRepository.companionSpoilerProtectionEnabled.first()
            ),
            firstUserMessage = null,
            personaId = persona.id
        )
        if (persona.greeting.isNotBlank()) {
            chatRepository.appendAssistantMessage(conversationId, persona.greeting)
        }
        activateConversation(conversationId)
        return conversationId
    }

    private fun activateConversation(conversationId: Long) {
        streamJob?.cancel()
        suggestionJob?.cancel()
        streamBuffer.setLength(0)
        session.value = session.value.copy(
            conversationId = conversationId,
            messages = emptyList(),
            streamingText = null,
            // 上一次离开时这轮生成可能还在后台跑；接着显示等待态，也别让用户再发一条。
            isStreaming = generationTracker.isActive(conversationId),
            toolStatus = null,
            executionSteps = emptyList(),
            suggestions = emptyList(),
            error = null
        )
        observeMessages(conversationId)
        observeGeneration(conversationId)
    }

    /** 后台那轮跑完时把等待态收掉；本地正在流式时以本地状态为准，不受它干扰。 */
    private fun observeGeneration(conversationId: Long) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            generationTracker.observe(conversationId).collect { active ->
                if (!active && streamJob?.isActive != true) {
                    session.value = session.value.copy(isStreaming = false, toolStatus = null)
                } else if (active && streamJob?.isActive != true) {
                    session.value = session.value.copy(isStreaming = true)
                }
            }
        }
    }

    private fun observeConversations(book: Long, personaId: Long) {
        conversationsJob?.cancel()
        conversationsJob = viewModelScope.launch {
            chatRepository.observeConversations(book, personaId, CONVERSATION_TYPE)
                .collect { conversations ->
                    session.value = session.value.copy(conversations = conversations)
                }
        }
    }

    private fun observeMessages(conversationId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(conversationId).collect { messages ->
                // 保留隐藏 tool 管道，界面会把它重建为可折叠执行时间线；system 仍不展示。
                session.value = session.value.copy(
                    messages = messages.filter { it.role != "system" }
                )
            }
        }
    }

    private fun stream(
        book: Long,
        persona: PersonaEntity,
        conversationId: Long,
        sceneQuote: String,
        memoryQuery: String?,
        requiredTools: Set<String> = emptySet()
    ) {
        streamJob?.cancel()
        suggestionJob?.cancel()
        // 生成挂在应用作用域而不是 viewModelScope：退出聊天页不该把已经流了一半的回复
        // 连同它一起取消。AgentLoop 完成时会把回复落库，用户回来就能在列表里看到它。
        streamJob = applicationScope.launch {
            session.value = session.value.copy(
                isStreaming = true,
                streamingText = "",
                toolStatus = null,
                executionSteps = emptyList(),
                suggestions = emptyList(),
                error = null
            )
            streamBuffer.setLength(0)
            val ticker = viewModelScope.launchStreamingTicker(::publishStreamingSnapshot)
            try {
                val globallyEnabledTools = if (webSearchSettingsStore.current().enabled) {
                    setOf("web_search", "web_scrape")
                } else {
                    emptySet()
                }
                val spoilerProtectionEnabled = settingsRepository
                    .companionSpoilerProtectionEnabled
                    .first()
                val memorySettings = settingsRepository.companionMemorySettings.first()
                val tools = readerToolset.forBook(
                    bookId = book,
                    personaId = persona.id,
                    conversationId = conversationId,
                    enabledTools = persona.enabledTools().toSet() + requiredTools + globallyEnabledTools,
                    spoilerProtectionEnabled = spoilerProtectionEnabled,
                    memoryScope = MemoryScope(
                        longTermEnabled = memorySettings.longTermEnabled && persona.memoryEnabled,
                        crossBookChatSearch = memorySettings.crossBookChatSearchEnabled,
                        maskId = userMaskStore.activeMask()?.id ?: 0L
                    )
                )
                val systemPrompt = contextBuilder.build(
                    persona = persona,
                    bookId = book,
                    scene = sceneQuote.takeIf(String::isNotBlank),
                    memoryQuery = memoryQuery,
                    toolNames = tools.map { it.spec.name },
                    spoilerProtectionEnabled = spoilerProtectionEnabled
                )
                agentLoop.run(conversationId, tools, systemPrompt).collect { event ->
                    when (event) {
                        is AgentEvent.Text -> {
                            streamBuffer.append(event.text)
                            // 正文一到就撤下工具状态行；文本快照本身交给节拍器。
                            if (session.value.toolStatus != null) {
                                session.value = session.value.copy(toolStatus = null)
                            }
                        }
                        is AgentEvent.RoundCommitted -> {
                            streamBuffer.setLength(0)
                            val messages = session.value.messages
                            session.value = session.value.copy(
                                messages = if (messages.any { it.id == event.message.id }) {
                                    messages
                                } else {
                                    messages + event.message
                                },
                                streamingText = ""
                            )
                        }
                        is AgentEvent.ToolRun -> session.value = session.value.copy(
                            toolStatus = "正在${event.displayName}…",
                            executionSteps = session.value.executionSteps
                                .filterNot { it.callId == event.callId } + AgentExecutionStep(
                                callId = event.callId,
                                toolName = event.toolName,
                                displayName = event.displayName,
                                state = AgentStepState.RUNNING
                            )
                        )
                        is AgentEvent.ToolFinished -> session.value = session.value.copy(
                            toolStatus = null,
                            executionSteps = session.value.executionSteps.map { step ->
                                if (step.callId == event.callId) {
                                    step.copy(
                                        state = if (event.succeeded) {
                                            AgentStepState.SUCCEEDED
                                        } else {
                                            AgentStepState.FAILED
                                        },
                                        detail = event.detail
                                    )
                                } else {
                                    step
                                }
                            }
                        )
                    }
                }
                if ("save_plot_summary" in requiredTools && session.value.executionSteps.none {
                        it.toolName == "save_plot_summary" && it.state == AgentStepState.SUCCEEDED
                    }
                ) {
                    throw IllegalStateException("模型没有调用保存工具，剧情梗概尚未入库；可重试或换用支持工具调用的模型")
                }
                memoryScheduler.afterTurn(conversationId)
                streamBuffer.setLength(0)
                session.value = session.value.copy(
                    isStreaming = false,
                    streamingText = null,
                    toolStatus = null
                )
                refreshSuggestions(persona, conversationId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 已到达的残段完整亮出来，和错误行一起停留在气泡里等重试。
                session.value = session.value.copy(
                    isStreaming = false,
                    toolStatus = null,
                    streamingText = streamBuffer.toString().takeIf(String::isNotBlank),
                    error = error.userMessage()
                )
            } finally {
                ticker.cancel()
                generationTracker.end(conversationId)
            }
        }
        // 登记在册，退出界面后回来仍能看到等待态、也仍能按「停止」把它停下来。
        streamJob?.let { generationTracker.begin(conversationId, it) }
    }

    /**
     * 「跳到原文」：把引文在书里定位成 (章, 字符偏移)。
     *
     * 先只翻模型点名的那一章——命中率最高、代价最小；不中再退回扫描已读范围内的章节，
     * 并对总字符数设上限：一本 200MB 的 TXT 全量载入内存只为找一句话是不划算的。
     */
    fun locate(citation: CompanionCitation) {
        val book = bookId.value ?: return
        viewModelScope.launch {
            val located = runCatching { resolveCitation(book, citation) }.getOrNull()
            if (located == null) {
                eventChannel.send(CompanionChatEvent.Message("这段原文没能在书中定位到"))
            } else {
                eventChannel.send(
                    CompanionChatEvent.LocateInBook(
                        chapterIndex = located.chapterIndex,
                        startCharOffset = located.startCharOffset,
                        endCharOffset = located.endCharOffset
                    )
                )
            }
        }
    }

    private suspend fun resolveCitation(
        book: Long,
        citation: CompanionCitation
    ): QuoteLocation? {
        val chapters = libraryRepository.getChapters(book)
        val hintedIndex = citation.chapterNumber?.minus(1)
        hintedIndex
            ?.let { index -> chapters.firstOrNull { it.chapterIndex == index } }
            ?.let { chapter ->
                val body = libraryRepository.readChapterText(book, chapter)
                BookQuoteLocator
                    .locateBest(listOf(QuoteChapter(chapter.chapterIndex, body)), citation.quote)
                    ?.let { return it }
            }

        val readableThrough = libraryRepository.getBook(book)?.lastReadChapterIndex ?: 0
        var scanned = 0
        val candidates = buildList {
            chapters
                .filter { it.chapterIndex <= readableThrough && it.chapterIndex != hintedIndex }
                .forEach { chapter ->
                    if (scanned >= MAX_LOCATE_CHARS) return@forEach
                    val body = libraryRepository.readChapterText(book, chapter)
                    scanned += body.length
                    add(QuoteChapter(chapter.chapterIndex, body))
                }
        }
        return BookQuoteLocator.locateBest(candidates, citation.quote, hintedIndex)
    }

    /** 节拍器回调：把缓冲区快照发布给 UI，仅在流式进行且内容变化时触发重组。 */
    private fun publishStreamingSnapshot() {
        val current = session.value
        if (!current.isStreaming) return
        val text = streamBuffer.toString()
        if (current.streamingText != text) {
            session.value = current.copy(streamingText = text)
        }
    }

    override fun onCleared() {
        session.value.conversationId?.let(memoryScheduler::onConversationClosed)
        // streamJob 故意不取消：它在应用作用域里，要把这轮回复写完再退场。
        messagesJob?.cancel()
        generationJob?.cancel()
        conversationsJob?.cancel()
        suggestionJob?.cancel()
    }

    private fun Throwable.userMessage(): String = when (this) {
        is AiClientException -> message ?: "请求失败"
        else -> "请求失败：${message ?: javaClass.simpleName}"
    }

    private companion object {
        const val CONVERSATION_TYPE = "COMPANION"
        /** 定位兜底扫描的字符上限：约 60 万字，足够覆盖绝大多数书的已读部分。 */
        const val MAX_LOCATE_CHARS = 600_000
    }
}
