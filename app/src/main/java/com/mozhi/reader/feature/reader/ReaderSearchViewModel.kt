package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.chineseConversionModeFor
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.feature.reader.engine.ChineseChapterPresenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hits: List<BookSearchHit> = emptyList(),
    /** 已完成一轮完整扫描（区分「没搜到」与「还没搜」）。 */
    val completed: Boolean = false
)

/** 书内关键词搜索：IO 上逐章扫描、边扫边出结果；新查询取消旧任务。 */
@HiltViewModel
class ReaderSearchViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val layoutStore: BookLayoutStore,
    private val chapterPresenter: ChineseChapterPresenter,
    private val chineseTextConverter: ChineseTextConverter
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderSearchUiState())
    val uiState = _uiState.asStateFlow()

    private var bookId: Long = -1
    private var searchJob: Job? = null

    fun bind(bookId: Long) {
        this.bookId = bookId
    }

    fun search(query: String) {
        searchJob?.cancel()
        val clean = query.trim()
        _uiState.value = ReaderSearchUiState(query = query)
        if (clean.length < MIN_QUERY_CHARS || bookId <= 0) return
        _uiState.value = _uiState.value.copy(isSearching = true)
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            val mode = settingsRepository.settings.first().chineseConversionModeFor(bookId)
            val chapters = libraryRepository.getChapters(bookId)
            for (chapter in chapters) {
                val raw = runCatching {
                    libraryRepository.readChapterText(bookId, chapter)
                }.getOrNull() ?: continue
                val layout = layoutStore.readChapter(bookId, chapter.chapterIndex)
                val body = withContext(Dispatchers.Default) {
                    chapterPresenter.present(
                        body = raw,
                        layout = layout,
                        images = emptyList(),
                        mode = mode
                    ).body
                }
                val hits = searchChapterText(
                    chapterIndex = chapter.chapterIndex,
                    chapterTitle = chineseTextConverter.convert(chapter.title, mode),
                    body = body,
                    query = clean
                )
                if (hits.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(hits = _uiState.value.hits + hits)
                }
                if (_uiState.value.hits.size >= MAX_TOTAL_HITS) break
            }
            _uiState.value = _uiState.value.copy(isSearching = false, completed = true)
        }
    }

    fun clear() {
        searchJob?.cancel()
        _uiState.value = ReaderSearchUiState()
    }

    private companion object {
        const val MIN_QUERY_CHARS = 1
        const val MAX_TOTAL_HITS = 300
    }
}
