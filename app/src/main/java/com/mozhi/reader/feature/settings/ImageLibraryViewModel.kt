package com.mozhi.reader.feature.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.PendingReaderImage
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderImageImporter
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ImageLibraryUiState(
    val images: List<ReaderImageAsset> = emptyList(),
    val selectedBackgroundImageId: String? = null,
    val isWorking: Boolean = false
)

sealed interface ImageLibraryEvent {
    data class ConfirmImport(val pending: PendingReaderImage) : ImageLibraryEvent
    data class Message(val text: String) : ImageLibraryEvent
}

@HiltViewModel
class ImageLibraryViewModel @Inject constructor(
    private val settingsRepository: ReaderSettingsRepository,
    private val libraryRepository: LibraryRepository,
    private val imageImporter: ReaderImageImporter
) : ViewModel() {
    private val working = MutableStateFlow(false)
    private val eventChannel = Channel<ImageLibraryEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val uiState = combine(settingsRepository.settings, working) { settings, isWorking ->
        ImageLibraryUiState(
            images = settings.imageLibrary.sortedWith(
                compareByDescending<ReaderImageAsset> { it.importedAt }.thenBy { it.displayName }
            ),
            selectedBackgroundImageId = settings.selectedBackgroundImageId,
            isWorking = isWorking
        )
    }.stateIn(viewModelScope, SharingStarted.Lazily, ImageLibraryUiState())

    fun prepareImport(uri: Uri) = launchWorking {
        eventChannel.send(ImageLibraryEvent.ConfirmImport(imageImporter.prepare(uri)))
    }

    fun confirmImport(pending: PendingReaderImage, displayName: String) = launchWorking {
        val asset = imageImporter.confirm(pending, displayName)
        eventChannel.send(ImageLibraryEvent.Message("${asset.displayName} 已加入图片库"))
    }

    fun cancelImport(pending: PendingReaderImage) {
        viewModelScope.launch { imageImporter.discard(pending) }
    }

    fun selectForBackground(imageId: String) = launchWorking {
        settingsRepository.selectBackgroundImage(imageId)
        eventChannel.send(ImageLibraryEvent.Message("阅读背景已更新"))
    }

    fun rename(imageId: String, displayName: String) = launchWorking {
        imageImporter.rename(imageId, displayName)
        eventChannel.send(ImageLibraryEvent.Message("图片名称已更新"))
    }

    fun delete(image: ReaderImageAsset) = launchWorking {
        val settings = settingsRepository.settings.first()
        val usedAsBackground = settings.backgroundImagePath == image.filePath
        val coverCount = libraryRepository.getBooks().count { it.coverPath == image.filePath }
        require(!usedAsBackground && coverCount == 0) {
            buildString {
                append("图片正在用作")
                if (usedAsBackground) append("阅读背景")
                if (usedAsBackground && coverCount > 0) append("和")
                if (coverCount > 0) append(" $coverCount 本书的封面")
                append("，请先更换后再删除")
            }
        }
        imageImporter.delete(image)
        eventChannel.send(ImageLibraryEvent.Message("${image.displayName} 已从图片库删除"))
    }

    fun clearBackground() = launchWorking {
        settingsRepository.setBackgroundImagePath(null)
        eventChannel.send(ImageLibraryEvent.Message("已恢复主题底色"))
    }

    private fun launchWorking(block: suspend () -> Unit) {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            runCatching { block() }
                .onFailure { error ->
                    eventChannel.send(
                        ImageLibraryEvent.Message(error.message ?: "操作失败，请重试")
                    )
                }
            working.value = false
        }
    }
}
