package com.mozhi.reader.feature.listen

import com.mozhi.reader.ui.bookIdOrNull
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.audiobook.AudiobookRoleExtractor
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookEnginePolicy
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.AudiobookRoleKind
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.TtsVoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.SystemTtsVoiceInfo
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.withSystemVoiceId
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AudiobookRoleUiState(
    val book: BookEntity? = null,
    val roles: List<AudiobookRoleEntity> = emptyList(),
    val voices: List<TtsVoiceEntity> = emptyList(),
    val isWorking: Boolean = false,
    val message: String? = null,
    val previewPath: String? = null,
    val systemVoices: List<SystemTtsVoiceInfo> = emptyList()
)

@HiltViewModel
class AudiobookRoleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository,
    private val roleExtractor: AudiobookRoleExtractor,
    private val mediaService: AiMediaGenerationService,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val settingsStore: TtsSettingsStore,
    voiceRepository: TtsVoiceRepository
) : ViewModel() {
    val bookId = savedStateHandle.bookIdOrNull() ?: 0L
    private val localVoices = MutableStateFlow<List<SystemTtsVoiceInfo>>(emptyList())
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val previewPath = MutableStateFlow<String?>(null)

    val uiState = combine(
        libraryRepository.observeBook(bookId),
        audiobookRepository.observeRoles(bookId),
        combine(voiceRepository.voices, localVoices) { cloud, local -> cloud to local },
        combine(working, message, previewPath) { busy, notice, preview -> Triple(busy, notice, preview) }
    ) { book, roles, voices, transient ->
        AudiobookRoleUiState(book, roles, voices.first, transient.first, transient.second, transient.third, voices.second)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookRoleUiState())

    init { refreshVoices() }

    fun refreshVoices() {
        viewModelScope.launch {
            try {
                localVoices.value = systemTtsSpeaker.voices(settingsStore.current().systemEnginePackage)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (settingsStore.current().engineMode == com.mozhi.reader.core.speech.TtsEngineMode.SYSTEM)
                    message.value = error.message ?: "读取本地音色失败"
            }
        }
    }

    fun extract(useAi: Boolean) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = if (useAi) "AI 正在识别角色…" else "正在按对白规则识别…"
            runCatching { roleExtractor.extract(bookId, useAi, existingRoles = audiobookRepository.getRoles(bookId)) }
                .onSuccess { result ->
                    audiobookRepository.replaceRoles(bookId, result.roles)
                    message.value = if (result.usedAi) "AI 角色提案已生成" else "规则角色提案已生成"
                }
                .onFailure { message.value = it.message ?: "角色识别失败" }
            working.value = false
        }
    }

    fun assignVoices() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = "AI 正在选择角色音色…"
            try {
                val roles = audiobookRepository.getRoles(bookId)
                val assigned = roleExtractor.assignVoices(roles)
                audiobookRepository.updateCasting(assigned)
                message.value = "已分配音色，分镜保留；受影响的音频需重新制作"
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                message.value = error.message ?: "音色分配失败"
            } finally { working.value = false }
        }
    }

    fun saveRole(role: AudiobookRoleEntity) {
        viewModelScope.launch {
            runCatching {
                if (role.id == 0L) audiobookRepository.addRole(role) else audiobookRepository.updateRole(role)
            }.onFailure { message.value = it.message ?: "角色保存失败" }
        }
    }

    fun addRole() {
        viewModelScope.launch {
            audiobookRepository.addRole(
                AudiobookRoleEntity(
                    bookId = bookId,
                    name = "新角色",
                    kind = AudiobookRoleKind.CHARACTER.name,
                    engine = if (settingsStore.current().engineMode == com.mozhi.reader.core.speech.TtsEngineMode.SYSTEM) AudiobookEngine.SYSTEM.name else AudiobookEngine.AI.name,
                    color = ROLE_COLORS[(uiState.value.roles.size + 1) % ROLE_COLORS.size],
                    sortOrder = uiState.value.roles.size
                )
            )
        }
    }

    fun deleteRole(role: AudiobookRoleEntity) {
        if (role.kind == AudiobookRoleKind.NARRATOR.name) return
        viewModelScope.launch { audiobookRepository.deleteRole(role.id) }
    }

    fun applyPolicy(policy: AudiobookEnginePolicy) {
        viewModelScope.launch {
            audiobookRepository.applyEnginePolicy(bookId, policy)
            message.value = "已应用${policy.label()}"
        }
    }

    fun preview(role: AudiobookRoleEntity) {
        if (working.value) return
        val voiceId = role.voiceId
        if (role.engine == AudiobookEngine.AI.name && voiceId.isBlank()) {
            message.value = "请先为角色选择 AI 音色"
            return
        }
        viewModelScope.launch {
            working.value = true
            runCatching {
                if (role.engine == AudiobookEngine.SYSTEM.name) {
                    check(systemTtsSpeaker.speak(PREVIEW_TEXT, settingsStore.current().withSystemVoiceId(voiceId))) {
                        "本地音色不可用，请刷新音色或重新选择引擎"
                    }
                    null
                } else mediaService.synthesizeSpeech(bookId, PREVIEW_TEXT, voiceId = voiceId).path
            }.onSuccess { previewPath.value = it }
                .onFailure { if (it is CancellationException) throw it; message.value = it.message ?: "试听生成失败" }
            working.value = false
        }
    }

    override fun onCleared() { systemTtsSpeaker.stop() }

    fun consumePreview() { previewPath.value = null }
    fun clearMessage() { message.value = null }

    private companion object {
        const val PREVIEW_TEXT = "山风掠过檐角，故事从这一刻开始。"
        val ROLE_COLORS = listOf("#607D8B", "#5C6BC0", "#26A69A", "#EC407A", "#AB47BC", "#FF7043")
    }
}

fun AudiobookEnginePolicy.label(): String = when (this) {
    AudiobookEnginePolicy.ALL_SYSTEM -> "全部系统 TTS"
    AudiobookEnginePolicy.NARRATOR_SYSTEM_CHARACTERS_AI -> "旁白系统 · 角色 AI"
    AudiobookEnginePolicy.ALL_AI -> "全部 AI"
    AudiobookEnginePolicy.CUSTOM -> "自定义"
}
