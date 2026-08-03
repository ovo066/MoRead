package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.agent.AnnotationDiscussionService
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.library.AnnotationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** AI 正在生成的讨论回复。 */
data class DiscussionStreaming(
    val personaId: Long,
    val text: String = "",
    val toolLabel: String? = null
)

data class DiscussionUiState(
    val replies: List<AnnotationReplyEntity> = emptyList(),
    val streaming: DiscussionStreaming? = null,
    val error: String? = null
)

/**
 * 段评讨论串状态：回复层观察 + 用户发言落库 + 单角色 AI 应答（批次一；
 * 「让大家聊聊」多角色编排在批次二）。讨论不进聊天会话。
 */
@HiltViewModel
class AnnotationDiscussionViewModel @Inject constructor(
    private val annotationRepository: AnnotationRepository,
    private val discussionService: AnnotationDiscussionService
) : ViewModel() {

    private val mutableState = MutableStateFlow(DiscussionUiState())
    val uiState = mutableState.asStateFlow()

    private var repliesJob: Job? = null
    private var respondJob: Job? = null

    /** 弹层打开时绑定该讨论串（同锚点的全部批注 id）。 */
    fun open(annotationIds: List<Long>) {
        repliesJob?.cancel()
        respondJob?.cancel()
        mutableState.value = DiscussionUiState()
        if (annotationIds.isEmpty()) return
        repliesJob = viewModelScope.launch {
            annotationRepository.observeReplies(annotationIds).collect { replies ->
                mutableState.update { it.copy(replies = replies) }
            }
        }
    }

    fun close() {
        repliesJob?.cancel()
        respondJob?.cancel()
        mutableState.value = DiscussionUiState()
    }

    /**
     * 用户发言：纯高亮的自有批注先把发言写进楼主层（note），其余情况落回复层；
     * [respondPersonaId] 非空时随后让该角色应答。
     */
    fun sendUserReply(
        bookId: Long,
        target: AnnotationEntity,
        text: String,
        respondPersonaId: Long?
    ) {
        val content = text.trim()
        if (content.isEmpty()) return
        viewModelScope.launch {
            if (target.personaId == null && target.note.isBlank()) {
                annotationRepository.updateNote(target.id, content)
            } else {
                annotationRepository.addReply(target.id, personaId = null, contentMarkdown = content)
            }
            if (respondPersonaId != null) {
                respond(bookId, target.id, respondPersonaId)
            }
        }
    }

    /** 单角色应答；已在生成时忽略新请求（一次一条，防连点失控）。 */
    fun respond(bookId: Long, annotationId: Long, personaId: Long) {
        if (mutableState.value.streaming != null) return
        respondJob = viewModelScope.launch {
            mutableState.update {
                it.copy(streaming = DiscussionStreaming(personaId), error = null)
            }
            discussionService.respond(bookId, annotationId, personaId).collect { event ->
                when (event) {
                    is AnnotationDiscussionService.Event.Text -> mutableState.update { state ->
                        val current = state.streaming ?: return@update state
                        state.copy(
                            streaming = current.copy(
                                text = current.text + event.delta,
                                toolLabel = null
                            )
                        )
                    }
                    is AnnotationDiscussionService.Event.ToolActivity -> mutableState.update { state ->
                        val current = state.streaming ?: return@update state
                        state.copy(streaming = current.copy(toolLabel = event.label))
                    }
                    is AnnotationDiscussionService.Event.Done -> mutableState.update {
                        it.copy(streaming = null)
                    }
                    is AnnotationDiscussionService.Event.Failed -> mutableState.update {
                        it.copy(streaming = null, error = event.message)
                    }
                }
            }
        }
    }

    /** 停止生成：丢弃半截回复（半条讨论没有保存价值）。 */
    fun cancelStreaming() {
        respondJob?.cancel()
        mutableState.update { it.copy(streaming = null) }
    }

    fun deleteReply(replyId: Long) {
        viewModelScope.launch { annotationRepository.deleteReply(replyId) }
    }
}
