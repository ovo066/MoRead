package com.mozhi.reader.feature.importer

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ImportPreviewUiState(
    val preview: TxtImportPreview? = null,
    val title: String = "",
    val author: String = "",
    val customRegex: String = "",
    val isWorking: Boolean = false,
    val progressMessage: String? = null,
    val progressFraction: Float? = null,
    val aiRuleProposal: AiChapterRuleProposal? = null,
    val errorMessage: String? = null
)

sealed interface ImportPreviewEvent {
    data class Imported(val bookId: Long) : ImportPreviewEvent
}

@HiltViewModel
class ImportPreviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val coordinator: ImportCoordinator,
    private val aiChapterRuleAgent: AiChapterRuleAgent,
    @ApplicationContext context: Context
) : ViewModel() {
    private val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    private val workManager = WorkManager.getInstance(context)
    private val mutableState = MutableStateFlow(ImportPreviewUiState())
    val uiState = mutableState.asStateFlow()
    private val eventChannel = Channel<ImportPreviewEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()
    private var deliveredBookId: Long? = null

    init {
        viewModelScope.launch {
            val preview = coordinator.preview(sessionId)
            mutableState.update { current ->
                if (preview == null) {
                    if (current.isWorking || deliveredBookId != null) {
                        current
                    } else {
                        current.copy(
                            errorMessage = current.errorMessage
                                ?: "导入会话已失效，请重新选择文件"
                        )
                    }
                } else {
                    current.copy(
                        preview = preview,
                        title = current.title.ifBlank { preview.suggestedTitle },
                        author = current.author.ifBlank { preview.suggestedAuthor }
                    )
                }
            }
        }
        observeImportWork()
    }

    fun setTitle(value: String) {
        mutableState.update { it.copy(title = value) }
    }

    fun setAuthor(value: String) {
        mutableState.update { it.copy(author = value) }
    }

    fun setCustomRegex(value: String) {
        mutableState.update { it.copy(customRegex = value) }
    }

    fun selectRule(ruleId: Long) {
        runWorking { coordinator.selectRule(sessionId, ruleId) }
    }

    fun applyCustomRegex() {
        val regex = mutableState.value.customRegex
        if (regex.isBlank()) {
            mutableState.update { it.copy(errorMessage = "请输入自定义正则") }
            return
        }
        runWorking { coordinator.applyCustomRegex(sessionId, regex) }
    }

    fun detectChapterRuleWithAi() {
        if (mutableState.value.isWorking) return
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isWorking = true,
                    progressMessage = "AI 正在分析章节结构",
                    progressFraction = null,
                    aiRuleProposal = null,
                    errorMessage = null
                )
            }
            runCatching {
                aiChapterRuleAgent.propose(sessionId) { attempt ->
                    mutableState.update {
                        it.copy(progressMessage = "AI 正在探寻规则 · 第 $attempt/3 轮")
                    }
                }
            }.onSuccess { proposal ->
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        progressMessage = null,
                        aiRuleProposal = proposal
                    )
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        progressMessage = null,
                        errorMessage = error.message ?: "AI 章节识别失败"
                    )
                }
            }
        }
    }

    fun dismissAiRuleProposal() {
        mutableState.update { it.copy(aiRuleProposal = null) }
    }

    fun confirmAiRuleProposal() {
        val proposal = mutableState.value.aiRuleProposal ?: return
        mutableState.update {
            it.copy(aiRuleProposal = null, customRegex = proposal.regex)
        }
        runWorking { coordinator.applyCustomRegex(sessionId, proposal.regex) }
    }

    fun confirm() {
        val current = mutableState.value
        when {
            current.title.isBlank() -> {
                mutableState.update { it.copy(errorMessage = "书名不能为空") }
                return
            }
            current.title.length > 200 -> {
                mutableState.update { it.copy(errorMessage = "书名不能超过 200 个字符") }
                return
            }
            current.author.length > 120 -> {
                mutableState.update { it.copy(errorMessage = "作者不能超过 120 个字符") }
                return
            }
        }

        val request = OneTimeWorkRequestBuilder<TxtImportWorker>()
            .setInputData(
                workDataOf(
                    TxtImportWorker.KEY_SESSION_ID to sessionId,
                    TxtImportWorker.KEY_TITLE to current.title.trim(),
                    TxtImportWorker.KEY_AUTHOR to current.author.trim()
                )
            )
            .addTag(TxtImportWorker.uniqueWorkName(sessionId))
            .build()
        mutableState.update {
            it.copy(
                isWorking = true,
                progressMessage = "等待后台导入",
                progressFraction = null,
                errorMessage = null
            )
        }
        workManager.enqueueUniqueWork(
            TxtImportWorker.uniqueWorkName(sessionId),
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun observeImportWork() {
        viewModelScope.launch {
            workManager.getWorkInfosForUniqueWorkFlow(
                TxtImportWorker.uniqueWorkName(sessionId)
            ).collectLatest { workInfos ->
                val workInfo = workInfos.lastOrNull { !it.state.isFinished }
                    ?: workInfos.lastOrNull()
                    ?: return@collectLatest
                handleWorkInfo(workInfo)
            }
        }
    }

    private suspend fun handleWorkInfo(workInfo: WorkInfo) {
        when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> mutableState.update {
                it.copy(
                    isWorking = true,
                    progressMessage = "等待后台导入",
                    progressFraction = null,
                    errorMessage = null
                )
            }
            WorkInfo.State.RUNNING -> {
                val completed = workInfo.progress.getInt(
                    TxtImportWorker.KEY_PROGRESS_COMPLETED,
                    0
                )
                val total = workInfo.progress.getInt(TxtImportWorker.KEY_PROGRESS_TOTAL, 0)
                val fraction = total.takeIf { it > 0 }
                    ?.let { completed.coerceIn(0, it).toFloat() / it }
                mutableState.update {
                    it.copy(
                        isWorking = true,
                        progressMessage = workInfo.progress.getString(
                            TxtImportWorker.KEY_PROGRESS_MESSAGE
                        ) ?: "正在后台导入",
                        progressFraction = fraction,
                        errorMessage = null
                    )
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val bookId = workInfo.outputData.getLong(TxtImportWorker.KEY_BOOK_ID, -1L)
                mutableState.update {
                    it.copy(
                        isWorking = false,
                        progressMessage = "导入完成",
                        progressFraction = 1f,
                        errorMessage = null
                    )
                }
                if (bookId > 0 && deliveredBookId != bookId) {
                    deliveredBookId = bookId
                    eventChannel.send(ImportPreviewEvent.Imported(bookId))
                }
            }
            WorkInfo.State.FAILED -> mutableState.update {
                it.copy(
                    isWorking = false,
                    progressMessage = null,
                    progressFraction = null,
                    errorMessage = workInfo.outputData.getString(
                        TxtImportWorker.KEY_ERROR_MESSAGE
                    ) ?: "后台导入失败，请重试"
                )
            }
            WorkInfo.State.CANCELLED -> mutableState.update {
                it.copy(
                    isWorking = false,
                    progressMessage = null,
                    progressFraction = null,
                    errorMessage = "后台导入已取消"
                )
            }
        }
    }

    private fun runWorking(block: suspend () -> TxtImportPreview) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    isWorking = true,
                    progressMessage = "正在重新识别章节",
                    progressFraction = null,
                    errorMessage = null
                )
            }
            runCatching { block() }
                .onSuccess { preview ->
                    mutableState.update {
                        it.copy(
                            preview = preview,
                            isWorking = false,
                            progressMessage = null,
                            progressFraction = null
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.update {
                        it.copy(
                            isWorking = false,
                            progressMessage = null,
                            progressFraction = null,
                            errorMessage = error.message ?: "重新识别失败"
                        )
                    }
                }
        }
    }
}
