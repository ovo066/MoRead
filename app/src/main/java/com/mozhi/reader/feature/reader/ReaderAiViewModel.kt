package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.prompt.SelectionAiAction
import com.mozhi.reader.ai.prompt.SelectionPrompts
import com.mozhi.reader.core.database.entity.MessageEntity
import com.mozhi.reader.core.database.entity.ModelRole
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One selection-action invocation of the AI panel. */
data class ReaderAiRequest(
    val action: SelectionAiAction,
    val selection: String,
    val context: String,
    val bookId: Long,
    val bookTitle: String,
    val chapterTitle: String
)

data class ReaderAiUiState(
    val request: ReaderAiRequest? = null,
    val conversationId: Long? = null,
    val messages: List<MessageEntity> = emptyList(),
    val streamingText: String? = null,
    val isStreaming: Boolean = false,
    /** Status line while a tool runs, e.g. "正在查询阅读进度…". */
    val toolStatus: String? = null,
    val executionSteps: List<AgentExecutionStep> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class ReaderAiViewModel @Inject constructor(
    private val chatRepository: AiChatRepository,
    private val agentLoop: AgentLoop
) : ViewModel() {

    private val mutableState = MutableStateFlow(ReaderAiUiState())
    val uiState = mutableState.asStateFlow()

    private var startedRequest: ReaderAiRequest? = null
    private var streamJob: Job? = null
    private var messagesJob: Job? = null

    /** 流式正文真源；UI 快照按 [STREAM_UI_TICK_MS] 节拍发布，避免逐 token 重组。 */
    private val streamBuffer = StringBuilder()

    /** Idempotent per request instance; a new selection action starts a fresh conversation. */
    fun start(request: ReaderAiRequest) {
        if (startedRequest === request) return
        startedRequest = request
        streamJob?.cancel()
        messagesJob?.cancel()
        mutableState.value = ReaderAiUiState(request = request)
        viewModelScope.launch {
            try {
                val conversationId = chatRepository.startConversation(
                    bookId = request.bookId,
                    title = "${request.action.label}：${request.selection.take(24)}",
                    type = CONVERSATION_TYPE,
                    systemPrompt = SelectionPrompts.system(request.bookTitle, request.chapterTitle),
                    firstUserMessage = SelectionPrompts.firstMessage(
                        action = request.action,
                        selection = request.selection,
                        context = request.context
                    ).takeIf { request.action != SelectionAiAction.ASK }
                )
                mutableState.value = mutableState.value.copy(conversationId = conversationId)
                observeMessages(conversationId)
                if (request.action != SelectionAiAction.ASK) stream(conversationId)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(error = error.userMessage())
            }
        }
    }

    fun send(text: String) {
        val conversationId = mutableState.value.conversationId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || mutableState.value.isStreaming) return
        viewModelScope.launch {
            val request = startedRequest
            val content = if (
                mutableState.value.messages.none { it.role == "user" } &&
                request?.action == SelectionAiAction.ASK
            ) {
                // The first question of an ASK conversation carries the selection context.
                SelectionPrompts.firstMessage(
                    SelectionAiAction.ASK,
                    request.selection,
                    request.context
                ) + "\n\n【问题】\n$trimmed"
            } else {
                trimmed
            }
            chatRepository.appendUserMessage(conversationId, content)
            stream(conversationId)
        }
    }

    /** Cancels the stream and keeps whatever arrived as a persisted partial reply. */
    fun stop() {
        val partial = streamBuffer.toString()
        val conversationId = mutableState.value.conversationId
        streamJob?.cancel()
        streamJob = null
        streamBuffer.setLength(0)
        mutableState.value = mutableState.value.copy(isStreaming = false, streamingText = null)
        if (partial.isNotBlank() && conversationId != null) {
            viewModelScope.launch { chatRepository.appendAssistantMessage(conversationId, partial) }
        }
    }

    fun retry() {
        val conversationId = mutableState.value.conversationId ?: return
        if (mutableState.value.isStreaming) return
        mutableState.value = mutableState.value.copy(error = null)
        stream(conversationId)
    }

    fun reset() {
        streamJob?.cancel()
        messagesJob?.cancel()
        streamJob = null
        messagesJob = null
        startedRequest = null
        streamBuffer.setLength(0)
        mutableState.value = ReaderAiUiState()
    }

    private fun observeMessages(conversationId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(conversationId).collect { messages ->
                mutableState.value = mutableState.value.copy(
                    // Keep tool rows here so the UI can place execution cards immediately after
                    // their originating assistant round instead of collecting them at the tail.
                    messages = messages.filter { it.role != "system" }
                )
            }
        }
    }

    private fun stream(conversationId: Long) {
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isStreaming = true,
                streamingText = "",
                toolStatus = null,
                executionSteps = emptyList(),
                error = null
            )
            streamBuffer.setLength(0)
            val ticker = launchStreamingTicker(::publishStreamingSnapshot)
            try {
                agentLoop.run(
                    conversationId = conversationId,
                    tools = emptyList(),
                    modelRole = ModelRole.CHEAP
                ).collect { event ->
                    when (event) {
                        is AgentEvent.Text -> {
                            streamBuffer.append(event.text)
                            if (mutableState.value.toolStatus != null) {
                                mutableState.value = mutableState.value.copy(toolStatus = null)
                            }
                        }
                        is AgentEvent.RoundCommitted -> {
                            streamBuffer.setLength(0)
                            val messages = mutableState.value.messages
                            val committed = event.message.takeIf { it.content.isNotBlank() }
                            mutableState.value = mutableState.value.copy(
                                messages = if (committed == null || messages.any { it.id == committed.id }) {
                                    messages
                                } else {
                                    messages + committed
                                },
                                streamingText = ""
                            )
                        }
                        is AgentEvent.ToolRun -> mutableState.value = mutableState.value.copy(
                            toolStatus = "正在${event.displayName}…",
                            executionSteps = mutableState.value.executionSteps
                                .filterNot { it.callId == event.callId } + AgentExecutionStep(
                                callId = event.callId,
                                toolName = event.toolName,
                                displayName = event.displayName,
                                state = AgentStepState.RUNNING
                            )
                        )
                        is AgentEvent.ToolFinished -> mutableState.value = mutableState.value.copy(
                            toolStatus = null,
                            executionSteps = mutableState.value.executionSteps.map { step ->
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
                // The full reply is persisted by the loop; the live buffer hands over to it.
                streamBuffer.setLength(0)
                mutableState.value = mutableState.value.copy(
                    isStreaming = false,
                    streamingText = null,
                    toolStatus = null
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                // 已到达的残段完整亮出来，和错误行一起停留在面板里等重试。
                mutableState.value = mutableState.value.copy(
                    isStreaming = false,
                    toolStatus = null,
                    streamingText = streamBuffer.toString().takeIf(String::isNotBlank),
                    error = error.userMessage()
                )
            } finally {
                ticker.cancel()
            }
        }
    }

    /** 节拍器回调：缓冲区快照发布给 UI，仅流式进行且内容变化时触发重组。 */
    private fun publishStreamingSnapshot() {
        val current = mutableState.value
        if (!current.isStreaming) return
        val text = streamBuffer.toString()
        if (current.streamingText != text) {
            mutableState.value = current.copy(streamingText = text)
        }
    }

    override fun onCleared() {
        streamJob?.cancel()
        messagesJob?.cancel()
    }

    private fun Throwable.userMessage(): String = when (this) {
        is AiClientException -> message ?: "请求失败"
        else -> "请求失败：${message ?: javaClass.simpleName}"
    }

    private companion object {
        const val CONVERSATION_TYPE = "SELECTION"
    }
}
