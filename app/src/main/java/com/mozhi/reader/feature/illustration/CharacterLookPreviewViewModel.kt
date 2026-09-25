package com.mozhi.reader.feature.illustration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.media.visibleLook
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.ImageConsistencyRepository
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class CharacterLookPreviewViewModel @Inject constructor(private val images: ImageConsistencyRepository,
    private val settings: ReaderSettingsRepository, private val library: LibraryRepository) : ViewModel() {
    val thumbnails = MutableStateFlow<Map<String, String>>(emptyMap())
    private var observation: Job? = null
    fun bind(bookId: Long) {
        observation?.cancel()
        observation = viewModelScope.launch {
            combine(images.observeLooks(bookId), settings.settings, library.observeBook(bookId)) { looks, preferences, book ->
                if (book == null) emptyMap() else looks.map { it.characterKey }.distinct().mapNotNull { key ->
                    val id = visibleLook(looks, key, book.maxReachedChapterIndex)?.referenceIds?.firstOrNull()
                    preferences.imageLibrary.firstOrNull { it.id == id }?.let { key to it.filePath }
                }.toMap()
            }.collect { thumbnails.value = it }
        }
    }
}
