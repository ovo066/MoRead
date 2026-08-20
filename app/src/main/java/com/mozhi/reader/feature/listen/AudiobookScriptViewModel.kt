package com.mozhi.reader.feature.listen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.audiobook.AudiobookCostEstimate
import com.mozhi.reader.ai.audiobook.AudiobookCostEstimator
import com.mozhi.reader.ai.audiobook.AudiobookScriptAgent
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AudiobookScriptUiState(
    val book: BookEntity? = null,
    val chapter: ChapterEntity? = null,
    val body: String = "",
    val roles: List<AudiobookRoleEntity> = emptyList(),
    val segments: List<AudiobookSegmentEntity> = emptyList(),
    val chapterState: AudiobookChapterEntity? = null,
    val estimate: AudiobookCostEstimate = AudiobookCostEstimate(0, 0, 0, 0, 0.0),
    val isWorking: Boolean = false,
    val message: String? = null,
    val previewPath: String? = null
)

sealed interface AudiobookScriptEvent {
    data object Confirmed : AudiobookScriptEvent
}

@HiltViewModel
class AudiobookScriptViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository,
    private val scriptAgent: AudiobookScriptAgent,
    private val mediaService: AiMediaGenerationService
) : ViewModel() {
    val bookId = savedStateHandle.get<String>("bookId")?.toLongOrNull() ?: 0L
    val chapterIndex = savedStateHandle.get<String>("chapter")?.toIntOrNull() ?: 0
    private val body = MutableStateFlow("")
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val previewPath = MutableStateFlow<String?>(null)
    private val eventsChannel = Channel<AudiobookScriptEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()

    private data class Content(
        val book: BookEntity?,
        val chapters: List<ChapterEntity>,
        val roles: List<AudiobookRoleEntity>,
        val segments: List<AudiobookSegmentEntity>,
        val chapterStates: List<AudiobookChapterEntity>
    )

    private val content = combine(
        libraryRepository.observeBook(bookId),
        libraryRepository.observeChapters(bookId),
        audiobookRepository.observeRoles(bookId),
        audiobookRepository.observeSegments(bookId, chapterIndex),
        audiobookRepository.observeChapters(bookId)
    ) { book, chapters, roles, segments, chapterStates ->
        Content(book, chapters, roles, segments, chapterStates)
    }

    val uiState = combine(
        content,
        body,
        combine(working, message, previewPath) { busy, notice, preview -> Triple(busy, notice, preview) }
    ) { content, chapterBody, transient ->
        val roleMap = content.roles.associateBy(AudiobookRoleEntity::id)
        val counts = content.segments.map { segment ->
            (segment.endCharOffset - segment.startCharOffset).coerceAtLeast(0)
        }
        val engines = content.segments.map { segment ->
            roleMap[segment.roleId]?.engine ?: AudiobookEngine.SYSTEM.name
        }
        AudiobookScriptUiState(
            book = content.book,
            chapter = content.chapters.firstOrNull { it.chapterIndex == chapterIndex },
            body = chapterBody,
            roles = content.roles,
            segments = content.segments,
            chapterState = content.chapterStates.firstOrNull { it.chapterIndex == chapterIndex },
            estimate = AudiobookCostEstimator.estimate(counts, engines, DEFAULT_PRICE_PER_10K),
            isWorking = transient.first,
            message = transient.second,
            previewPath = transient.third
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookScriptUiState())

    init {
        viewModelScope.launch {
            val chapter = libraryRepository.getChapters(bookId)
                .firstOrNull { it.chapterIndex == chapterIndex } ?: return@launch
            body.value = libraryRepository.readChapterText(bookId, chapter)
            if (audiobookRepository.getSegments(bookId, chapterIndex).isEmpty()) generate(false)
        }
    }

    fun generate(useAi: Boolean) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = if (useAi) "AI 正在精排本章…" else "正在按规则排剧本…"
            runCatching { scriptAgent.generate(bookId, chapterIndex, useAi) }
                .onSuccess { result ->
                    body.value = result.body
                    message.value = if (result.usedAi) "AI 精排完成，请逐段确认" else "规则排版完成，请逐段确认"
                }
                .onFailure { message.value = it.message ?: "剧本生成失败" }
            working.value = false
        }
    }

    fun setRole(segment: AudiobookSegmentEntity, roleId: Long?) {
        viewModelScope.launch { audiobookRepository.updateSegment(segment.copy(roleId = roleId, audioPath = null, audioMillis = 0)) }
    }

    fun setEmotion(segment: AudiobookSegmentEntity, emotion: String) {
        viewModelScope.launch { audiobookRepository.updateSegment(segment.copy(emotion = emotion, audioPath = null, audioMillis = 0)) }
    }

    fun preview(segment: AudiobookSegmentEntity) {
        val current = uiState.value
        val role = current.roles.firstOrNull { it.id == segment.roleId } ?: return
        val text = current.body.substring(
            segment.startCharOffset.coerceIn(0, current.body.length),
            segment.endCharOffset.coerceIn(0, current.body.length)
        ).trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            working.value = true
            runCatching {
                mediaService.synthesizeSpeech(
                    bookId = bookId,
                    text = text,
                    voiceId = role.voiceId.takeIf(String::isNotBlank),
                    emotion = segment.emotion,
                    instruction = segment.instruction
                ).path
            }.onSuccess { previewPath.value = it }
                .onFailure { message.value = it.message ?: "试听生成失败" }
            working.value = false
        }
    }

    fun confirm(applyToNextChapters: Boolean) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            runCatching {
                audiobookRepository.confirmScript(bookId, chapterIndex)
                if (applyToNextChapters) {
                    val chapters = libraryRepository.getChapters(bookId)
                        .filter { it.chapterIndex in (chapterIndex + 1)..(chapterIndex + 3) }
                    chapters.forEach { chapter ->
                        scriptAgent.generate(bookId, chapter.chapterIndex, useAi = false)
                        audiobookRepository.confirmScript(bookId, chapter.chapterIndex)
                    }
                }
            }.onSuccess { eventsChannel.send(AudiobookScriptEvent.Confirmed) }
                .onFailure { message.value = it.message ?: "确认剧本失败" }
            working.value = false
        }
    }

    fun consumePreview() { previewPath.value = null }
    fun clearMessage() { message.value = null }

    companion object {
        val EMOTIONS = listOf("中性", "开心", "悲伤", "愤怒", "恐惧", "厌恶", "惊讶")
        const val DEFAULT_PRICE_PER_10K = 0.10
    }
}
