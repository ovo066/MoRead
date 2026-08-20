package com.mozhi.reader.feature.listen

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.mozhi.reader.ai.audiobook.AudiobookCostEstimate
import com.mozhi.reader.ai.audiobook.AudiobookCostEstimator
import com.mozhi.reader.ai.audiobook.AudiobookProductionWorker
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AudiobookProductionUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val chapterStates: List<AudiobookChapterEntity> = emptyList(),
    val startChapter: Int = 0,
    val endChapter: Int = 0,
    val estimate: AudiobookCostEstimate = AudiobookCostEstimate(0, 0, 0, 0, 0.0),
    val missingConfirmationChapter: Int? = null,
    val isRunning: Boolean = false,
    val progressText: String = "",
    val progress: Float? = null,
    val message: String? = null
)

sealed interface AudiobookProductionEvent {
    data class OpenScript(val chapterIndex: Int) : AudiobookProductionEvent
}

@HiltViewModel
class AudiobookProductionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository
) : ViewModel() {
    val bookId = savedStateHandle.get<String>("bookId")?.toLongOrNull() ?: 0L
    private val mutableState = MutableStateFlow(AudiobookProductionUiState())
    val uiState = mutableState
    private val eventChannel = Channel<AudiobookProductionEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private val workManager = WorkManager.getInstance(context)
    private var initializedRange = false

    init {
        viewModelScope.launch {
            combine(
                libraryRepository.observeBook(bookId),
                libraryRepository.observeChapters(bookId),
                audiobookRepository.observeChapters(bookId),
                audiobookRepository.observeRoles(bookId)
            ) { book, chapters, chapterStates, roles ->
                LibrarySnapshot(book, chapters, chapterStates, roles)
            }.collectLatest { snapshot -> updateSnapshot(snapshot) }
        }
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(
                AudiobookProductionWorker.uniqueWorkName(bookId)
            ).collectLatest { infos -> infos.lastOrNull()?.let(::updateWorkInfo) }
        }
    }

    fun setEndChapter(chapterIndex: Int) {
        mutableState.update { current ->
            val end = chapterIndex.coerceIn(current.startChapter, (current.chapters.size - 1).coerceAtLeast(0))
            current.copy(endChapter = end)
        }
        viewModelScope.launch { recalculateEstimate() }
    }

    fun startProduction() {
        viewModelScope.launch {
            val state = mutableState.value
            val range = (state.startChapter..state.endChapter).toList()
            val confirmed = state.chapterStates.associateBy(AudiobookChapterEntity::chapterIndex)
            val missing = range.firstOrNull { index ->
                confirmed[index]?.state !in setOf(
                    AudiobookChapterState.CONFIRMED.name,
                    AudiobookChapterState.SYNTHESIZING.name,
                    AudiobookChapterState.READY.name
                )
            }
            if (missing != null) {
                mutableState.update { it.copy(missingConfirmationChapter = missing, message = "请先确认第 ${missing + 1} 章剧本") }
                eventChannel.send(AudiobookProductionEvent.OpenScript(missing))
                return@launch
            }
            AudiobookProductionWorker.enqueue(context, bookId, range)
        }
    }

    fun pause() = AudiobookProductionWorker.pause(context, bookId)
    fun clearMessage() = mutableState.update { it.copy(message = null) }

    private suspend fun updateSnapshot(snapshot: LibrarySnapshot) {
        mutableState.update { current ->
            val start = snapshot.book?.lastReadChapterIndex
                ?.coerceIn(0, (snapshot.chapters.size - 1).coerceAtLeast(0)) ?: 0
            val end = if (!initializedRange) {
                (start + 3).coerceAtMost((snapshot.chapters.size - 1).coerceAtLeast(0))
            } else {
                current.endChapter.coerceIn(start, (snapshot.chapters.size - 1).coerceAtLeast(0))
            }
            if (snapshot.chapters.isNotEmpty()) initializedRange = true
            current.copy(
                book = snapshot.book,
                chapters = snapshot.chapters,
                chapterStates = snapshot.chapterStates,
                startChapter = start,
                endChapter = end
            )
        }
        recalculateEstimate(snapshot.roles)
    }

    private suspend fun recalculateEstimate(knownRoles: List<AudiobookRoleEntity>? = null) {
        val state = mutableState.value
        val roles = (knownRoles ?: audiobookRepository.getRoles(bookId)).associateBy(AudiobookRoleEntity::id)
        val segments = (state.startChapter..state.endChapter).flatMap { index ->
            audiobookRepository.getSegments(bookId, index)
        }
        mutableState.update {
            it.copy(
                estimate = AudiobookCostEstimator.estimate(
                    characterCounts = segments.map { segment ->
                        (segment.endCharOffset - segment.startCharOffset).coerceAtLeast(0)
                    },
                    engines = segments.map { segment ->
                        roles[segment.roleId]?.engine ?: AudiobookEngine.SYSTEM.name
                    },
                    pricePerTenThousandChars = AudiobookScriptViewModel.DEFAULT_PRICE_PER_10K
                )
            )
        }
    }

    private fun updateWorkInfo(workInfo: WorkInfo) {
        val progressData = if (workInfo.state == WorkInfo.State.SUCCEEDED) workInfo.outputData else workInfo.progress
        val completed = progressData.getInt(AudiobookProductionWorker.KEY_COMPLETED, 0)
        val total = progressData.getInt(AudiobookProductionWorker.KEY_TOTAL, 0)
        val title = progressData.getString(AudiobookProductionWorker.KEY_CHAPTER_TITLE).orEmpty()
        mutableState.update { current ->
            when (workInfo.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> current.copy(
                    isRunning = true,
                    progressText = "等待开始制作",
                    progress = null,
                    message = null
                )
                WorkInfo.State.RUNNING -> current.copy(
                    isRunning = true,
                    progressText = if (title.isBlank()) "正在合成" else "$title · 已合成 $completed/$total 段",
                    progress = total.takeIf { it > 0 }?.let { completed.coerceIn(0, it).toFloat() / it },
                    message = null
                )
                WorkInfo.State.SUCCEEDED -> current.copy(
                    isRunning = false,
                    progressText = "制作完成",
                    progress = 1f,
                    message = "所选章节已制作完成"
                )
                WorkInfo.State.FAILED -> current.copy(
                    isRunning = false,
                    progressText = "制作失败",
                    progress = null,
                    message = workInfo.outputData.getString(AudiobookProductionWorker.KEY_ERROR) ?: "制作失败"
                )
                WorkInfo.State.CANCELLED -> current.copy(
                    isRunning = false,
                    progressText = "已暂停，可继续制作",
                    progress = null
                )
            }
        }
    }

    private data class LibrarySnapshot(
        val book: BookEntity?,
        val chapters: List<ChapterEntity>,
        val chapterStates: List<AudiobookChapterEntity>,
        val roles: List<AudiobookRoleEntity>
    )
}
