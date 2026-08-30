package com.mozhi.reader.feature.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.CompanionToolRouter
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.chat.CompanionGenerationTracker
import com.mozhi.reader.ai.chat.ReplySuggestionService
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.agent.MemoryScope
import com.mozhi.reader.ai.memory.MemoryConsolidationScheduler
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.ai.prompt.CompanionContextBuilder
import com.mozhi.reader.ai.prompt.ConversationShape
import com.mozhi.reader.ai.search.WebSearchSettingsStore
import com.mozhi.reader.core.database.entity.ConversationEntity
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.chatAppearance
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.database.entity.enabledTools
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.datastore.UserMaskStore
import com.mozhi.reader.core.library.AttachmentStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.MessageAttachment
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import com.mozhi.reader.core.library.QuoteChapter
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
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
    val detail: String = "",
    /** 模型给出的原始参数 JSON；界面用 ToolCallSummary 压成一行摘要。 */
    val arguments: String = "{}",
    /** 工具返回的结果预览（已截断），只在「过程」卡展开后显示。 */
    val resultPreview: String = ""
)

/** 输入区待发送的附件（尚未落盘）；发送时经 AttachmentStore 压缩/保存。 */
data class PendingAttachment(
    val uri: Uri,
    val isImage: Boolean,
    val name: String
)

data class VoiceClipState(
    val path: String? = null,
    val loading: Boolean = false,
    val missing: Boolean = false,
    val failed: Boolean = false
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
    /** 当前进程内给流式回复分配的稳定 UI key；落库后仍沿用，避免 LazyColumn 重建气泡。 */
    val messageUiKeys: Map<Long, String> = emptyMap(),
    val liveEntryId: String = "live-initial",
    /** 仅包含已在本书已读正文中实际命中的引用，按消息 id 索引。 */
    val locatedCitations: Map<Long, List<LocatedCompanionCitation>> = emptyMap(),
    val streamingText: String? = null,
    /** 本轮正在流的思维链；null = 该模型不产出 reasoning，界面整条不出现。 */
    val streamingReasoning: String? = null,
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
    /** 多气泡：AI 回复按行拆成独立气泡。 */
    val multiBubbleEnabled: Boolean = false,
    /** 「自主发语音」开关开着且当前角色已绑音色；两者缺一即为 false。 */
    val voiceRepliesEnabled: Boolean = false,
    val voiceClips: Map<String, VoiceClipState> = emptyMap(),
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
    private val mediaService: AiMediaGenerationService,
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
    private val nextLiveEntrySequence = AtomicLong(0L)
    private val eventChannel = Channel<CompanionChatEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /**
     * 流式正文的真源。SSE 增量只追加到这里（主线程独占），UI 快照由节拍器按
     * 显示帧节拍发布到 session，避免逐 token 重组整页与 O(n²) 拼串。
     */
    private val streamBuffer = StringBuilder()

    /** 思维链的真源，与 [streamBuffer] 同一纪律：只在节拍上发布快照，不逐 token 重组。 */
    private val reasoningBuffer = StringBuilder()

    private data class SessionState(
        val conversationId: Long? = null,
        val conversations: List<ConversationEntity> = emptyList(),
        val messages: List<MessageEntity> = emptyList(),
        val messageUiKeys: Map<Long, String> = emptyMap(),
        val liveEntryId: String = "live-initial",
        val locatedCitations: Map<Long, List<LocatedCompanionCitation>> = emptyMap(),
        val streamingText: String? = null,
        val streamingReasoning: String? = null,
        val isStreaming: Boolean = false,
        val toolStatus: String? = null,
        val executionSteps: List<AgentExecutionStep> = emptyList(),
        val suggestions: List<String> = emptyList(),
        val voiceClips: Map<String, VoiceClipState> = emptyMap(),
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

    /** 界面用得到的开关：多气泡决定气泡怎么拆，语音决定语音气泡要不要合成。 */
    private data class CompanionToggles(
        val multiBubble: Boolean,
        val autonomy: CompanionAutonomySettings
    )

    private val toggles = combine(
        settingsRepository.companionMultiBubbleEnabled,
        settingsRepository.companionAutonomySettings
    ) { multiBubble, autonomy -> CompanionToggles(multiBubble, autonomy) }

    val uiState = combine(
        activePersona,
        session,
        embeddingProgress,
        settingsRepository.companionSpoilerProtectionEnabled,
        settingsRepository.settings,
        toggles
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val (personas, active) = values[0] as Pair<List<PersonaEntity>, PersonaEntity?>
        val session = values[1] as SessionState
        val embedding = values[2] as BookEmbeddingProgress?
        val spoilerProtectionEnabled = values[3] as Boolean
        val readerSettings = values[4] as ReaderSettings
        val toggles = values[5] as CompanionToggles
        val appearance = active?.chatAppearance() ?: PersonaChatAppearance.DEFAULT
        CompanionChatUiState(
            personas = personas,
            activePersona = active,
            conversationId = session.conversationId,
            conversations = session.conversations,
            messages = session.messages,
            messageUiKeys = session.messageUiKeys,
            liveEntryId = session.liveEntryId,
            locatedCitations = session.locatedCitations,
            streamingText = session.streamingText,
            streamingReasoning = session.streamingReasoning,
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
            multiBubbleEnabled = toggles.multiBubble,
            // 角色没绑音色就等于没有这项能力，界面也不该显示语音入口。
            voiceRepliesEnabled = toggles.autonomy.voiceRepliesEnabled &&
                active?.voiceId?.isNotBlank() == true,
            voiceClips = session.voiceClips,
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

    fun setMultiBubbleEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setCompanionMultiBubbleEnabled(value) }
    }

    /**
     * 手动朗读某条气泡：AI 自主发语音之外的兜底入口。用当前角色绑定的音色合成，
     * 命中缓存就直接播（同文本同音色永不重复计费）。
     */
    fun speak(text: String, onReady: (String) -> Unit) {
        val book = bookId.value ?: return
        val clean = text.trim().take(MAX_SPEAK_CHARS)
        if (clean.isEmpty()) return
        val persona = uiState.value.activePersona
        viewModelScope.launch {
            runCatching {
                mediaService.synthesizeSpeech(
                    bookId = book,
                    text = clean,
                    voiceId = persona?.voiceId?.takeIf(String::isNotBlank),
                    emotion = persona?.voiceEmotion?.takeIf(String::isNotBlank)
                )
            }.onSuccess { onReady(it.path) }
                .onFailure { error ->
                    eventChannel.send(
                        CompanionChatEvent.Message(error.userMessage())
                    )
                }
        }
    }

    /** 历史语音先只查缓存；自主语音开启时才允许后台补合成。 */
    fun prepareVoiceClip(key: String, text: String) {
        if (session.value.voiceClips.containsKey(key)) return
        loadVoiceClip(key, text, allowGenerate = uiState.value.voiceRepliesEnabled)
    }

    /** 用户点“重新合成”属于显式操作，不受自主语音开关约束。 */
    fun regenerateVoiceClip(key: String, text: String) {
        loadVoiceClip(key, text, allowGenerate = true)
    }

    private fun loadVoiceClip(key: String, text: String, allowGenerate: Boolean) {
        val book = bookId.value ?: return
        val persona = uiState.value.activePersona ?: return
        if (persona.voiceId.isBlank()) {
            session.value = session.value.copy(
                voiceClips = session.value.voiceClips + (key to VoiceClipState(failed = true))
            )
            return
        }
        session.value = session.value.copy(
            voiceClips = session.value.voiceClips + (key to VoiceClipState(loading = true))
        )
        viewModelScope.launch {
            val cached = runCatching {
                mediaService.peekCachedSpeech(
                    bookId = book,
                    text = text,
                    voiceId = persona.voiceId,
                    emotion = persona.voiceEmotion.takeIf(String::isNotBlank)
                )
            }.getOrNull()
            val generated = if (cached == null && allowGenerate) {
                runCatching {
                    mediaService.synthesizeSpeech(
                        bookId = book,
                        text = text,
                        voiceId = persona.voiceId,
                        emotion = persona.voiceEmotion.takeIf(String::isNotBlank)
                    )
                }
            } else {
                null
            }
            val resolved = cached ?: generated?.getOrNull()
            session.value = session.value.copy(
                voiceClips = session.value.voiceClips + (
                    key to when {
                        resolved != null -> VoiceClipState(path = resolved.path)
                        generated?.isFailure == true -> VoiceClipState(failed = true)
                        else -> VoiceClipState(missing = true)
                    }
                )
            )
        }
    }

    /**
     * 用户显式要一张插图。走 requiredTools 强制注册 generate_image——
     * 这是用户点的按钮，不受「自主生图」开关约束（那个管的是 AI 自己决定的调用）。
     */
    fun requestIllustration(sceneQuote: String) {
        sendWithRequiredTools(
            text = "请为我当前读到的场景画一张插图：先确认我的阅读进度与场景，再调用 generate_image 生成并保存。",
            sceneQuote = sceneQuote,
            requiredTools = setOf("get_reading_progress", "generate_image")
        )
    }

    fun retryEmbedding() {
        val book = bookId.value ?: return
        viewModelScope.launch {
            if (uiState.value.embeddingProgress?.stage == EmbeddingIndexStage.DISABLED) {
                embeddingProgressTracker.enable(book)
            } else {
                embeddingProgressTracker.retry(book)
            }
        }
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
        reasoningBuffer.setLength(0)
        session.value = session.value.copy(
            isStreaming = false,
            streamingText = null,
            streamingReasoning = null,
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
        reasoningBuffer.setLength(0)
        session.value = session.value.copy(
            conversationId = conversationId,
            messages = emptyList(),
            messageUiKeys = emptyMap(),
            liveEntryId = newLiveEntryId(conversationId),
            streamingText = null,
            streamingReasoning = null,
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
            chatRepository.observeMessages(conversationId).collectLatest { messages ->
                // 保留隐藏 tool 管道，界面会把它重建为可折叠执行时间线；system 仍不展示。
                val visibleMessages = messages.filter { it.role != "system" }
                session.value = session.value.copy(
                    messages = visibleMessages,
                    locatedCitations = emptyMap()
                )
                val located = try {
                    locateMessageCitations(visibleMessages)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyMap()
                }
                if (session.value.conversationId == conversationId && session.value.messages == visibleMessages) {
                    session.value = session.value.copy(locatedCitations = located)
                }
            }
        }
    }

    private suspend fun locateMessageCitations(
        messages: List<MessageEntity>
    ): Map<Long, List<LocatedCompanionCitation>> {
        val candidates = messages.mapNotNull { message ->
            if (message.role != "assistant") return@mapNotNull null
            CompanionCitationParser.parse(message.content).citations
                .takeIf { it.isNotEmpty() }
                ?.let { message.id to it }
        }
        if (candidates.isEmpty()) return emptyMap()

        val currentBookId = bookId.value ?: return emptyMap()
        val book = libraryRepository.getBook(currentBookId) ?: return emptyMap()
        val chapters = libraryRepository.getChapters(currentBookId)
            .filter { it.chapterIndex <= book.lastReadChapterIndex }
        val requestedChapterIndexes = candidates
            .flatMap { (_, citations) -> citations.mapNotNull { it.chapterNumber?.minus(1) } }
            .toSet()
        val (requestedChapters, remainingChapters) = chapters.partition {
            it.chapterIndex in requestedChapterIndexes
        }
        val orderedChapters = requestedChapters + remainingChapters
        var scannedChars = 0
        val quoteChapters = buildList {
            orderedChapters.forEach { chapter ->
                val explicitlyRequested = chapter.chapterIndex in requestedChapterIndexes
                if (!explicitlyRequested && scannedChars >= MAX_LOCATE_CHARS) return@forEach
                val fullText = libraryRepository.readChapterText(currentBookId, chapter)
                val readableText = if (chapter.chapterIndex == book.lastReadChapterIndex) {
                    fullText.take(book.lastReadCharOffset.coerceIn(0, fullText.length))
                } else {
                    fullText
                }
                scannedChars += readableText.length
                add(QuoteChapter(chapter.chapterIndex, readableText))
            }
        }

        return candidates.mapNotNull { (messageId, citations) ->
            CompanionCitationVerifier.locate(citations, quoteChapters)
                .takeIf { it.isNotEmpty() }
                ?.let { messageId to it }
        }.toMap()
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
            var currentLiveEntryId = newLiveEntryId(conversationId)
            session.value = session.value.copy(
                isStreaming = true,
                streamingText = "",
                streamingReasoning = null,
                liveEntryId = currentLiveEntryId,
                toolStatus = null,
                executionSteps = emptyList(),
                suggestions = emptyList(),
                error = null
            )
            streamBuffer.setLength(0)
            reasoningBuffer.setLength(0)
            val ticker = viewModelScope.launchStreamingTicker(::publishStreamingSnapshot)
            try {
                val webSearchEnabled = webSearchSettingsStore.current().enabled
                val spoilerProtectionEnabled = settingsRepository
                    .companionSpoilerProtectionEnabled
                    .first()
                val memorySettings = settingsRepository.companionMemorySettings.first()
                val autonomy = settingsRepository.companionAutonomySettings.first()
                val multiBubble = settingsRepository.companionMultiBubbleEnabled.first()
                // 语音要「开关开着」且「这个角色真绑了音色」两个条件都成立才算有这项能力。
                val voiceEnabled = autonomy.voiceRepliesEnabled && persona.voiceId.isNotBlank()
                // 主伴读会话始终把角色获准的完整工具集交给模型，让模型自己决定是否调用。
                // 之前按关键词裁剪会让大量正常说法落成空 tools，模型只能回答“没有工具”。
                val enabledTools = CompanionToolRouter.available(
                    personaEnabledTools = persona.enabledTools().toSet(),
                    requiredTools = requiredTools,
                    webSearchEnabled = webSearchEnabled,
                    longTermMemoryEnabled = memorySettings.longTermEnabled && persona.memoryEnabled
                ).let { selected ->
                    // 自主生图关着就直接把工具摘掉：留一个用了会被拒的工具只会诱导模型去调。
                    // 用户在扩展面板里显式点「生成插图」时走 requiredTools，不受此限。
                    if (autonomy.imageRepliesEnabled || "generate_image" in requiredTools) {
                        selected
                    } else {
                        selected - "generate_image"
                    }
                }
                val tools = readerToolset.forBook(
                    bookId = book,
                    personaId = persona.id,
                    conversationId = conversationId,
                    enabledTools = enabledTools,
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
                    spoilerProtectionEnabled = spoilerProtectionEnabled,
                    conversationShape = ConversationShape(
                        multiBubble = multiBubble,
                        voiceEnabled = voiceEnabled
                    )
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
                        is AgentEvent.Reasoning -> reasoningBuffer.append(event.text)
                        is AgentEvent.RoundCommitted -> {
                            streamBuffer.setLength(0)
                            reasoningBuffer.setLength(0)
                            val messages = session.value.messages
                            val committedUiKeys = session.value.messageUiKeys +
                                (event.message.id to currentLiveEntryId)
                            currentLiveEntryId = newLiveEntryId(conversationId)
                            session.value = session.value.copy(
                                messages = if (messages.any { it.id == event.message.id }) {
                                    messages
                                } else {
                                    messages + event.message
                                },
                                messageUiKeys = committedUiKeys,
                                liveEntryId = currentLiveEntryId,
                                streamingText = "",
                                streamingReasoning = null
                            )
                        }
                        is AgentEvent.ToolRun -> session.value = session.value.copy(
                            toolStatus = "正在${event.displayName}…",
                            executionSteps = session.value.executionSteps
                                .filterNot { it.callId == event.callId } + AgentExecutionStep(
                                callId = event.callId,
                                toolName = event.toolName,
                                displayName = event.displayName,
                                state = AgentStepState.RUNNING,
                                arguments = event.arguments
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
                                        detail = event.detail,
                                        resultPreview = event.resultPreview
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
                reasoningBuffer.setLength(0)
                session.value = session.value.copy(
                    isStreaming = false,
                    streamingText = null,
                    streamingReasoning = null,
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
                    // 失败时思维链尤其有用：它常常说明模型卡在了哪一步。
                    streamingReasoning = reasoningBuffer.toString().takeIf(String::isNotBlank),
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

    /** 引用胶囊展示前已经完成定位，点击时只分发坐标，不再重复扫描正文。 */
    fun locate(citation: LocatedCompanionCitation) {
        viewModelScope.launch {
            eventChannel.send(
                CompanionChatEvent.LocateInBook(
                    chapterIndex = citation.chapterIndex,
                    startCharOffset = citation.startCharOffset,
                    endCharOffset = citation.endCharOffset
                )
            )
        }
    }

    /** 节拍器回调：把缓冲区快照发布给 UI，仅在流式进行且内容变化时触发重组。 */
    private fun publishStreamingSnapshot() {
        val current = session.value
        if (!current.isStreaming) return
        val text = nextStreamingFrame(current.streamingText.orEmpty(), streamBuffer.toString())
        val reasoningTarget = reasoningBuffer.toString()
        val reasoning = nextStreamingFrame(
            current = current.streamingReasoning.orEmpty(),
            target = reasoningTarget
        ).takeIf(String::isNotBlank)
        if (current.streamingText != text || current.streamingReasoning != reasoning) {
            session.value = current.copy(streamingText = text, streamingReasoning = reasoning)
        }
    }

    private fun newLiveEntryId(conversationId: Long): String =
        "live-$conversationId-${nextLiveEntrySequence.incrementAndGet()}"

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
        /** 手动朗读单条气泡的字数上限：再长就不是「听一句」了。 */
        const val MAX_SPEAK_CHARS = 2_000
    }
}
