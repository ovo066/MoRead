package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.ai.embedding.LibraryEmbeddingProgress
import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.BookProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.CompanionMemorySettings
import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import com.mozhi.reader.core.storage.StorageRepository
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val isLoaded: Boolean = false,
    val providers: List<AiProviderEntity> = emptyList(),
    /** All models across providers, in provider order. */
    val models: List<AiModelEntity> = emptyList(),
    /** role → assigned modelId. */
    val assignments: Map<ModelRole, Long?> = emptyMap(),
    val embeddingProgress: LibraryEmbeddingProgress = LibraryEmbeddingProgress(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
    val suggestionRepliesEnabled: Boolean = true,
    val memory: CompanionMemorySettings = CompanionMemorySettings(),
    val showAiAnnotations: Boolean = true,
    /** 多气泡回复；默认关。 */
    val multiBubbleEnabled: Boolean = false,
    /** agent 主动调用开关矩阵；全部默认关。 */
    val autonomy: CompanionAutonomySettings = CompanionAutonomySettings(),
    val isWorking: Boolean = false,
    /** All local data, using the same inventory as the data-management page. */
    val localStorageBytes: Long? = null
)

sealed interface SettingsEvent {
    data class ShowMessage(val message: String) : SettingsEvent
}

/** 设置主页：Provider 列表（编辑与模型管理在 provider/{id} 二级页）、模型分配、外观、应用。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val providerRepository: AiProviderRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val embeddingProgressTracker: EmbeddingProgressTracker,
    private val storageRepository: StorageRepository
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val storage = storageRepository.snapshot
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private data class AppPrefs(
        val shelfLayout: ShelfLayout,
        val suggestionRepliesEnabled: Boolean,
        val showAiAnnotations: Boolean,
        val memory: CompanionMemorySettings,
        val multiBubbleEnabled: Boolean,
        val autonomy: CompanionAutonomySettings
    )

    private val appPrefs = combine(
        readerSettingsRepository.settings.map { it.shelfLayout },
        readerSettingsRepository.suggestionRepliesEnabled,
        readerSettingsRepository.showAiAnnotations,
        readerSettingsRepository.companionMemorySettings,
        readerSettingsRepository.companionMultiBubbleEnabled,
        readerSettingsRepository.companionAutonomySettings
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        AppPrefs(
            shelfLayout = values[0] as ShelfLayout,
            suggestionRepliesEnabled = values[1] as Boolean,
            showAiAnnotations = values[2] as Boolean,
            memory = values[3] as CompanionMemorySettings,
            multiBubbleEnabled = values[4] as Boolean,
            autonomy = values[5] as CompanionAutonomySettings
        )
    }

    private data class AiConfig(
        val providers: List<AiProviderEntity>,
        val models: List<AiModelEntity>,
        val assignments: List<com.mozhi.reader.core.database.entity.ModelAssignmentEntity>,
        val embeddingProgress: LibraryEmbeddingProgress
    )

    private val aiConfig = combine(
        providerRepository.observeProviders(),
        providerRepository.observeModels(),
        providerRepository.observeAssignments(),
        embeddingProgressTracker.observeLibrary()
    ) { providers, models, assignments, embeddingProgress ->
        AiConfig(providers, models, assignments, embeddingProgress)
    }

    val uiState = combine(
        aiConfig,
        readerSettingsRepository.appearance,
        appPrefs,
        working,
        storage
    ) { ai, appearance, prefs, isWorking, usage ->
        SettingsUiState(
            isLoaded = true,
            providers = ai.providers,
            models = ai.models,
            assignments = ai.assignments.associate { it.role to it.modelId },
            embeddingProgress = ai.embeddingProgress,
            appearance = appearance,
            shelfLayout = prefs.shelfLayout,
            suggestionRepliesEnabled = prefs.suggestionRepliesEnabled,
            showAiAnnotations = prefs.showAiAnnotations,
            memory = prefs.memory,
            multiBubbleEnabled = prefs.multiBubbleEnabled,
            autonomy = prefs.autonomy,
            isWorking = isWorking,
            localStorageBytes = usage?.totalBytes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = SettingsUiState()
    )

    init {
        viewModelScope.launch {
            // 先把设置页首帧交给 UI；目录统计不是首屏必需信息，稍后后台补齐。
            delay(STORAGE_SCAN_DEFER_MS)
            runCatching { storageRepository.refresh() }
        }
    }

    fun assignModel(role: ModelRole, modelId: Long?) {
        viewModelScope.launch {
            runCatching { providerRepository.assign(role, modelId) }
                .onFailure { error ->
                    eventChannel.send(
                        SettingsEvent.ShowMessage(error.message ?: "该模型与角色能力不兼容")
                    )
                }
        }
    }

    fun retryEmbedding() {
        viewModelScope.launch { embeddingProgressTracker.retryAll() }
    }

    fun rebuildEmbedding() {
        viewModelScope.launch { embeddingProgressTracker.rebuildAll() }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { readerSettingsRepository.setThemeMode(mode) }
    }

    fun setAccentPreset(preset: AccentPreset) {
        viewModelScope.launch { readerSettingsRepository.setAccentPreset(preset) }
    }

    fun setCustomAccent(argb: Int) {
        viewModelScope.launch { readerSettingsRepository.setCustomAccent(argb) }
    }

    fun setShelfLayout(layout: ShelfLayout) {
        viewModelScope.launch { readerSettingsRepository.setShelfLayout(layout) }
    }

    fun setSuggestionReplies(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setSuggestionRepliesEnabled(enabled) }
    }

    fun setLongTermMemory(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionLongTermMemory(enabled) }
    }

    fun setCrossBookMemory(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionCrossBookMemory(enabled) }
    }

    fun setCrossBookChatSearch(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionCrossBookChatSearch(enabled) }
    }

    fun setShowAiAnnotations(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setShowAiAnnotations(enabled) }
    }

    fun setMultiBubble(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionMultiBubbleEnabled(enabled) }
    }

    fun setVoiceReplies(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionVoiceReplies(enabled) }
    }

    fun setImageReplies(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionImageReplies(enabled) }
    }

    fun setProactiveAnnotations(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setCompanionProactiveAnnotations(enabled) }
    }

    fun setProactiveAnnotationVoice(enabled: Boolean) {
        viewModelScope.launch {
            readerSettingsRepository.setCompanionProactiveAnnotationVoice(enabled)
        }
    }

    fun setProactiveAnnotationImage(enabled: Boolean) {
        viewModelScope.launch {
            readerSettingsRepository.setCompanionProactiveAnnotationImage(enabled)
        }
    }

    fun setAnnotationNotice(value: com.mozhi.reader.core.datastore.ProactiveAnnotationNotice) {
        viewModelScope.launch { readerSettingsRepository.setCompanionAnnotationNotice(value) }
    }

    fun setAnnotationLimits(limits: ProactiveAnnotationLimits) {
        viewModelScope.launch { readerSettingsRepository.setCompanionAnnotationLimits(limits) }
    }

    /** 传 null 让这本书交还给全局默认。 */
    fun setBookAnnotationLimits(bookId: Long, override: BookProactiveAnnotationLimits?) {
        viewModelScope.launch {
            readerSettingsRepository.setCompanionAnnotationLimitsForBook(bookId, override)
        }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            runCatching { storageRepository.refresh() }
        }
    }

    private companion object { const val STORAGE_SCAN_DEFER_MS = 400L }
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
