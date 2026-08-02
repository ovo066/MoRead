package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.agent.AgentLoop
import com.mozhi.reader.ai.agent.ReaderToolset
import com.mozhi.reader.ai.chat.AiChatRepository
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.prompt.SelectionAiAction
import com.mozhi.reader.ai.prompt.SelectionPrompts
import com.mozhi.reader.core.database.entity.MessageEntity
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
    private val agentLoop: AgentLoop,
    private val readerToolset: ReaderToolset
) : ViewModel() {

    private val mutableState = MutableStateFlow(ReaderAiUiState())
    val uiState = mutableState.asStateFlow()

    private var startedRequest: ReaderAiRequest? = null
    private var streamJob: Job? = null
    private var messagesJob: Job? = null

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
        val partial = mutableState.value.streamingText
        val conversationId = mutableState.value.conversationId
        streamJob?.cancel()
        streamJob = null
        mutableState.value = mutableState.value.copy(isStreaming = false, streamingText = null)
        if (!partial.isNullOrBlank() && conversationId != null) {
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
        mutableState.value = ReaderAiUiState()
    }

    private fun observeMessages(conversationId: Long) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            chatRepository.observeMessages(conversationId).collect { messages ->
                mutableState.value = mutableState.value.copy(
                    // Tool plumbing stays out of the transcript: hide tool results and
                    // assistant turns that only carried tool calls.
                    messages = messages.filter {
                        it.role != "system" && it.role != "tool" && it.content.isNotBlank()
                    }
                )
            }
        }
    }

    private fun stream(conversationId: Long) {
        val bookId = startedRequest?.bookId
        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                isStreaming = true,
                streamingText = "",
                toolStatus = null,
                executionSteps = emptyList(),
                error = null
            )
            try {
                val tools = bookId?.let { readerToolset.forBook(it) }.orEmpty()
                agentLoop.run(conversationId, tools).collect { event ->
                    when (event) {
                        is AgentEvent.Text -> mutableState.value = mutableState.value.copy(
                            streamingText = (mutableState.value.streamingText ?: "") + event.text,
                            toolStatus = null
                        )
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
                mutableState.value = mutableState.value.copy(
                    isStreaming = false,
                    streamingText = null,
                    toolStatus = null
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    isStreaming = false,
                    toolStatus = null,
                    error = error.userMessage()
                )
            }
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
