package com.mozhi.reader.feature.listen

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
    val previewPath: String? = null
)

@HiltViewModel
class AudiobookRoleViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository,
    private val roleExtractor: AudiobookRoleExtractor,
    private val mediaService: AiMediaGenerationService,
    voiceRepository: TtsVoiceRepository
) : ViewModel() {
    val bookId = savedStateHandle.get<String>("bookId")?.toLongOrNull() ?: 0L
    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val previewPath = MutableStateFlow<String?>(null)

    val uiState = combine(
        libraryRepository.observeBook(bookId),
        audiobookRepository.observeRoles(bookId),
        voiceRepository.voices,
        combine(working, message, previewPath) { busy, notice, preview -> Triple(busy, notice, preview) }
    ) { book, roles, voices, transient ->
        AudiobookRoleUiState(book, roles, voices, transient.first, transient.second, transient.third)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookRoleUiState())

    fun extract(useAi: Boolean) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = if (useAi) "AI 正在识别角色…" else "正在按对白规则识别…"
            runCatching { roleExtractor.extract(bookId, useAi) }
                .onSuccess { result ->
                    audiobookRepository.replaceRoles(bookId, result.roles)
                    message.value = if (result.usedAi) "AI 角色提案已生成" else "规则角色提案已生成"
                }
                .onFailure { message.value = it.message ?: "角色识别失败" }
            working.value = false
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
                    engine = AudiobookEngine.AI.name,
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

    fun preview(voiceId: String) {
        if (voiceId.isBlank()) {
            message.value = "请先为角色选择 AI 音色"
            return
        }
        viewModelScope.launch {
            working.value = true
            runCatching {
                mediaService.synthesizeSpeech(bookId, PREVIEW_TEXT, voiceId = voiceId).path
            }.onSuccess { previewPath.value = it }
                .onFailure { message.value = it.message ?: "试听生成失败" }
            working.value = false
        }
    }

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
