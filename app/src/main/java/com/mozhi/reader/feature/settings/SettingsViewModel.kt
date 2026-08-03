package com.mozhi.reader.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.embedding.EmbeddingProgressTracker
import com.mozhi.reader.ai.embedding.LibraryEmbeddingProgress
import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val providers: List<AiProviderEntity> = emptyList(),
    /** All models across providers, in provider order. */
    val models: List<AiModelEntity> = emptyList(),
    /** role → assigned modelId. */
    val assignments: Map<ModelRole, Long?> = emptyMap(),
    val embeddingProgress: LibraryEmbeddingProgress = LibraryEmbeddingProgress(),
    val appearance: AppearanceSettings = AppearanceSettings(),
    val shelfLayout: ShelfLayout = ShelfLayout.GRID,
    val suggestionRepliesEnabled: Boolean = true,
    val showAiAnnotations: Boolean = true,
    val isWorking: Boolean = false,
    /** 封面缓存占用字节数；null = 还没统计。 */
    val coverCacheBytes: Long? = null,
    val bookStorageBytes: Long? = null
)

sealed interface SettingsEvent {
    data class ShowMessage(val message: String) : SettingsEvent
}

/** 设置主页：Provider 列表（编辑与模型管理在 provider/{id} 二级页）、模型分配、外观、应用。 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val providerRepository: AiProviderRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val embeddingProgressTracker: EmbeddingProgressTracker
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val storage = MutableStateFlow<StorageUsage?>(null)
    private val eventChannel = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private data class AppPrefs(
        val shelfLayout: ShelfLayout,
        val suggestionRepliesEnabled: Boolean,
        val showAiAnnotations: Boolean
    )

    private val appPrefs = combine(
        readerSettingsRepository.settings.map { it.shelfLayout },
        readerSettingsRepository.suggestionRepliesEnabled,
        readerSettingsRepository.showAiAnnotations
    ) { layout, suggestions, aiAnnotations -> AppPrefs(layout, suggestions, aiAnnotations) }

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
            providers = ai.providers,
            models = ai.models,
            assignments = ai.assignments.associate { it.role to it.modelId },
            embeddingProgress = ai.embeddingProgress,
            appearance = appearance,
            shelfLayout = prefs.shelfLayout,
            suggestionRepliesEnabled = prefs.suggestionRepliesEnabled,
            showAiAnnotations = prefs.showAiAnnotations,
            isWorking = isWorking,
            coverCacheBytes = usage?.coverBytes,
            bookStorageBytes = usage?.bookBytes
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    init {
        refreshStorageUsage()
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
        embeddingProgressTracker.retryAll()
    }

    fun rebuildEmbedding() {
        embeddingProgressTracker.rebuildAll()
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

    fun setShowAiAnnotations(enabled: Boolean) {
        viewModelScope.launch { readerSettingsRepository.setShowAiAnnotations(enabled) }
    }

    fun refreshStorageUsage() {
        viewModelScope.launch {
            storage.value = withContext(Dispatchers.IO) {
                StorageUsage(
                    coverBytes = coversDirectory().directorySize(),
                    bookBytes = File(context.filesDir, "books").directorySize() +
                        File(context.filesDir, "book-text").directorySize() +
                        File(context.filesDir, "book-media").directorySize() +
                        File(context.filesDir, "illustrations").directorySize()
                )
            }
        }
    }

    /**
     * 清理封面缓存。只删「可重建」的 EPUB 封面：删文件、置空 coverPath、并抹掉补齐任务的
     * marker，这样下次启动 `backfillMissingCovers` 会从 EPUB 里重新抽取 —— 是真的缓存，
     * 而不是永久删除。用户手选的封面和 TXT 书封面不在范围内。
     */
    fun clearCoverCache() {
        viewModelScope.launch {
            working.value = true
            val freed = runCatching {
                val bytes = libraryRepository.clearReExtractableCovers()
                withContext(Dispatchers.IO) {
                    File(coversDirectory(), COVER_BACKFILL_MARKER).delete()
                }
                bytes
            }.getOrDefault(0L)
            refreshStorageUsage()
            working.value = false
            eventChannel.send(
                SettingsEvent.ShowMessage(
                    if (freed > 0) {
                        "已清理 ${formatBytes(freed)}，下次启动会自动重建"
                    } else {
                        "没有可清理的封面缓存"
                    }
                )
            )
        }
    }

    private fun coversDirectory(): File = File(context.filesDir, "covers")

    private data class StorageUsage(val coverBytes: Long, val bookBytes: Long)

    private companion object {
        /** 与 ImportCoordinator 里的 marker 同名，清理后补齐任务才会重跑。 */
        const val COVER_BACKFILL_MARKER = ".epub-cover-backfill-v1"
    }
}

private fun File.directorySize(): Long = when {
    !exists() -> 0L
    isFile -> length()
    else -> walkBottomUp().filter(File::isFile).sumOf(File::length)
}

internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 ->
        String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
