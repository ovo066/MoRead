package com.mozhi.reader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.datastore.ReaderFontImporter
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FontLibraryUiState(
    val fonts: List<ReaderFontAsset> = emptyList(),
    val selectedBodyFontId: String? = null,
    val isWorking: Boolean = false
)

sealed interface FontLibraryEvent {
    data class ConfirmImport(val pending: PendingReaderFont) : FontLibraryEvent
    data class Message(val text: String) : FontLibraryEvent
}

@HiltViewModel
class FontLibraryViewModel @Inject constructor(
    private val settingsRepository: ReaderSettingsRepository,
    private val fontImporter: ReaderFontImporter
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val eventChannel = Channel<FontLibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val uiState = combine(settingsRepository.settings, working) { settings, isWorking ->
        FontLibraryUiState(
            fonts = settings.fontLibrary.sortedWith(
                compareByDescending<ReaderFontAsset> { it.importedAt }.thenBy { it.displayName }
            ),
            selectedBodyFontId = settings.selectedCustomFontId
                .takeIf { settings.font == com.mozhi.reader.core.datastore.ReaderFont.CUSTOM },
            isWorking = isWorking
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, FontLibraryUiState())

    fun prepareImport(uri: Uri) = launchWorking {
        eventChannel.send(FontLibraryEvent.ConfirmImport(fontImporter.prepare(uri)))
    }

    fun confirmImport(pending: PendingReaderFont, displayName: String) = launchWorking {
        val asset = fontImporter.confirm(pending, displayName)
        eventChannel.send(FontLibraryEvent.Message("${asset.displayName} 已加入字体库并设为正文"))
    }

    fun cancelImport(pending: PendingReaderFont) {
        viewModelScope.launch { fontImporter.discard(pending) }
    }

    fun selectForBody(fontId: String) = launchWorking {
        settingsRepository.selectCustomFont(fontId)
        eventChannel.send(FontLibraryEvent.Message("正文默认字体已更新"))
    }

    fun rename(fontId: String, displayName: String) = launchWorking {
        fontImporter.rename(fontId, displayName)
        eventChannel.send(FontLibraryEvent.Message("字体名称已更新"))
    }

    fun delete(font: ReaderFontAsset) = launchWorking {
        fontImporter.delete(font)
        eventChannel.send(FontLibraryEvent.Message("${font.displayName} 已从字体库删除"))
    }

    private fun launchWorking(block: suspend () -> Unit) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            runCatching { block() }
                .onFailure { error ->
                    eventChannel.send(
                        FontLibraryEvent.Message(error.message ?: "操作失败，请重试")
                    )
                }
            working.value = false
        }
    }
}
