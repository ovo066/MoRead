package com.mozhi.reader.feature.reader

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

data class SelectionMediaUiState(
    val status: String? = null,
    val isWorking: Boolean = false,
    val isPlaying: Boolean = false,
    val imagePath: String? = null,
    val imagePrompt: String? = null,
    /** Non-null only for an image created from the current text selection, enabling re-roll. */
    val imageGeneration: SelectionImageGeneration? = null
)

data class SelectionImageGeneration(
    val bookId: Long,
    val chapterIndex: Int,
    val charOffset: Int?,
    val sourceText: String,
    val basePrompt: String
)

sealed interface SelectionMediaEvent {
    data class Message(val text: String) : SelectionMediaEvent
}

/** 选区 TTS 与生图：TTS 按「语音朗读」设置路由到系统引擎或云端模型，生图走分配的模型。 */
@HiltViewModel
class ReaderSelectionMediaViewModel @Inject constructor(
    private val mediaService: AiMediaGenerationService,
    private val ttsSettingsStore: TtsSettingsStore,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val listenEngine: com.mozhi.reader.ai.listen.ListenEngine
) : ViewModel() {
    private val mutableState = MutableStateFlow(SelectionMediaUiState())
    val uiState = mutableState.asStateFlow()
    private val eventChannel = Channel<SelectionMediaEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var operationJob: Job? = null
    private var player: MediaPlayer? = null

    /** 只反映本 VM 自己发起的系统朗读；连续听书共用同一 Speaker，不应点亮此状态条。 */
    private var ownsSystemSpeech = false

    init {
        // 系统 TTS 的播放状态由 UtteranceProgressListener 汇报，转成统一的 UI 状态条
        viewModelScope.launch {
            systemTtsSpeaker.isSpeaking.collect { speaking ->
                val state = mutableState.value
                if (speaking && ownsSystemSpeech) {
                    mutableState.value = state.copy(
                        status = "正在朗读（系统 TTS）",
                        isWorking = false,
                        isPlaying = true
                    )
                } else if (!speaking) {
                    ownsSystemSpeech = false
                    if (state.isPlaying && player == null) {
                        mutableState.value = state.copy(status = null, isPlaying = false)
                    }
                }
            }
        }
    }

    fun speak(
        bookId: Long,
        selection: String,
        voiceId: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        pitch: Int? = null
    ) {
        val text = selection.trim().take(MAX_TTS_CHARS)
        if (text.isEmpty()) return
        operationJob?.cancel()
        stopAudio()
        operationJob = viewModelScope.launch {
            val settings = ttsSettingsStore.current()
            if (settings.engineMode == TtsEngineMode.SYSTEM) {
                // 系统引擎是共享队列：选区朗读会 QUEUE_FLUSH 掉听书批次，先停掉听书会话。
                if (listenEngine.isActive) listenEngine.stop()
                mutableState.value = mutableState.value.copy(
                    status = "正在启动系统朗读…",
                    isWorking = true
                )
                ownsSystemSpeech = true
                val ok = systemTtsSpeaker.speak(text, settings)
                if (!ok) {
                    ownsSystemSpeech = false
                    mutableState.value = mutableState.value.copy(
                        status = null,
                        isWorking = false,
                        isPlaying = false
                    )
                    eventChannel.send(
                        SelectionMediaEvent.Message("系统 TTS 启动失败，请在设置 › 语音朗读中检查引擎")
                    )
                }
                return@launch
            }
            mutableState.value = mutableState.value.copy(
                status = "正在合成选区语音…",
                isWorking = true
            )
            try {
                val speech = mediaService.synthesizeSpeech(
                    bookId = bookId,
                    text = text,
                    voiceId = voiceId ?: settings.aiVoiceId.takeIf(String::isNotBlank),
                    speed = speed ?: settings.aiSpeed.takeIf { it != 1f },
                    volume = volume ?: settings.aiVolume.takeIf { it != 1f },
                    pitch = pitch ?: settings.aiPitch.takeIf { it != 0 }
                )
                play(File(speech.path))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    status = null,
                    isWorking = false,
                    isPlaying = false
                )
                eventChannel.send(SelectionMediaEvent.Message(error.userMessage("语音合成失败")))
            }
        }
    }

    fun stopAudio() {
        if (ownsSystemSpeech) {
            ownsSystemSpeech = false
            systemTtsSpeaker.stop()
        }
        player?.runCatching { stop() }
        player?.release()
        player = null
        mutableState.value = mutableState.value.copy(
            status = null,
            isWorking = false,
            isPlaying = false
        )
    }

    fun generateImage(
        bookId: Long,
        bookTitle: String,
        chapterTitle: String,
        chapterIndex: Int,
        charOffset: Int?,
        selection: String,
        contextText: String
    ) {
        val selected = selection.trim().take(MAX_IMAGE_SOURCE_CHARS)
        if (selected.isEmpty()) return
        val nearby = contextText.trim().take(MAX_IMAGE_CONTEXT_CHARS)
        val fallbackPrompt = buildString {
            append("小说插画，忠实表现以下选段；无文字、无水印。")
            if (bookTitle.isNotBlank()) append("书名《").append(bookTitle).append("》。")
            if (chapterTitle.isNotBlank()) append("章节：").append(chapterTitle).append("。")
            append("画面内容：").append(selected)
            if (nearby.isNotBlank()) append("。附近语境：").append(nearby)
        }
        generateImage(
            SelectionImageGeneration(
                bookId = bookId,
                chapterIndex = chapterIndex,
                charOffset = charOffset,
                sourceText = selected,
                basePrompt = fallbackPrompt
            ),
            prompt = fallbackPrompt,
            keepPreview = false
        )
    }

    /** Reuses the original selection/anchor while letting the user re-roll or revise the prompt. */
    fun rerollImage(prompt: String, improvement: String = "") {
        val generation = mutableState.value.imageGeneration ?: return
        val revised = buildString {
            append(prompt.trim().ifBlank { generation.basePrompt })
            improvement.trim().takeIf(String::isNotBlank)?.let {
                append("\n\n改进要求：").append(it)
            }
        }
        generateImage(generation.copy(basePrompt = revised), revised, keepPreview = true)
    }

    private fun generateImage(
        generation: SelectionImageGeneration,
        prompt: String,
        keepPreview: Boolean
    ) {
        operationJob?.cancel()
        stopAudio()
        operationJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                status = "正在把选段整理成画面…",
                isWorking = true,
                imagePath = if (keepPreview) mutableState.value.imagePath else null,
                imagePrompt = if (keepPreview) mutableState.value.imagePrompt else null,
                imageGeneration = generation
            )
            try {
                mutableState.value = mutableState.value.copy(status = "正在生成插图…")
                val illustration = mediaService.generateIllustration(
                    bookId = generation.bookId,
                    chapterIndex = generation.chapterIndex,
                    charOffset = generation.charOffset,
                    sourceText = generation.sourceText,
                    prompt = prompt,
                    personaId = null
                )
                mutableState.value = mutableState.value.copy(
                    status = null,
                    isWorking = false,
                    imagePath = illustration.imagePath,
                    imagePrompt = illustration.prompt,
                    imageGeneration = generation.copy(basePrompt = prompt)
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(status = null, isWorking = false)
                eventChannel.send(SelectionMediaEvent.Message(error.userMessage("生成插图失败")))
            }
        }
    }

    fun playCachedSpeech(path: String) {
        val file = File(path)
        if (!file.isFile) {
            viewModelScope.launch { eventChannel.send(SelectionMediaEvent.Message("语音缓存已被清理，请重新生成")) }
            return
        }
        stopAudio()
        mutableState.value = mutableState.value.copy(status = "正在载入语音…", isWorking = true)
        play(file)
    }

    fun showSavedImage(path: String, prompt: String? = null) {
        if (!File(path).isFile) {
            viewModelScope.launch { eventChannel.send(SelectionMediaEvent.Message("插图文件不存在")) }
            return
        }
        mutableState.value = mutableState.value.copy(
            imagePath = path,
            imagePrompt = prompt,
            imageGeneration = null
        )
    }

    fun dismissImage() {
        mutableState.value = mutableState.value.copy(
            imagePath = null,
            imagePrompt = null,
            imageGeneration = null
        )
    }

    fun cancelOperation() {
        operationJob?.cancel()
        operationJob = null
        mutableState.value = mutableState.value.copy(status = null, isWorking = false)
    }

    private fun play(file: File) {
        val next = MediaPlayer()
        player = next
        next.setDataSource(file.absolutePath)
        next.setOnPreparedListener { prepared ->
            prepared.start()
            mutableState.value = mutableState.value.copy(
                status = "正在朗读选区",
                isWorking = false,
                isPlaying = true
            )
        }
        next.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            mutableState.value = mutableState.value.copy(
                status = null,
                isPlaying = false
            )
        }
        next.setOnErrorListener { mediaPlayer, _, _ ->
            mediaPlayer.release()
            if (player === mediaPlayer) player = null
            mutableState.value = mutableState.value.copy(
                status = null,
                isWorking = false,
                isPlaying = false
            )
            viewModelScope.launch {
                eventChannel.send(SelectionMediaEvent.Message("无法播放生成的语音"))
            }
            true
        }
        next.prepareAsync()
    }

    override fun onCleared() {
        operationJob?.cancel()
        player?.release()
        player = null
    }

    private fun Throwable.userMessage(fallback: String): String = when (this) {
        is AiClientException -> message ?: fallback
        is IllegalArgumentException -> message ?: fallback
        else -> "$fallback：${message ?: javaClass.simpleName}"
    }

    private companion object {
        const val MAX_TTS_CHARS = 5_000
        const val MAX_IMAGE_SOURCE_CHARS = 2_000
        const val MAX_IMAGE_CONTEXT_CHARS = 1_200
    }
}
