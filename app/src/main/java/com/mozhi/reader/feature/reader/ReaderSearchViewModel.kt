package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.feature.reader.engine.ChineseChapterPresenter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ReaderSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val hits: List<BookSearchHit> = emptyList(),
    /** 已完成一轮完整扫描（区分「没搜到」与「还没搜」）。 */
    val completed: Boolean = false
)

internal typealias ReaderSearchScan = suspend (
    bookId: Long,
    mode: ChineseConversionMode,
    query: String,
    publish: (List<BookSearchHit>) -> Boolean
) -> Unit

private data class ReaderSearchIdentity(
    val bookId: Long,
    val mode: ChineseConversionMode,
    val query: String
)

/** 书内关键词搜索：逐章扫描、边扫边出结果；新查询取消旧任务。 */
@HiltViewModel
class ReaderSearchViewModel internal constructor(
    private val scanBook: ReaderSearchScan
) : ViewModel() {

    @Inject
    constructor(
        libraryRepository: LibraryRepository,
        layoutStore: BookLayoutStore,
        chapterPresenter: ChineseChapterPresenter,
        chineseTextConverter: ChineseTextConverter
    ) : this(
        scanBook = { bookId, mode, query, publish ->
            val chapters = libraryRepository.getChapters(bookId)
            for (chapter in chapters) {
                val raw = searchChapterOrNull {
                    libraryRepository.readChapterText(bookId, chapter)
                } ?: continue
                val layout = layoutStore.readChapter(bookId, chapter.chapterIndex)
                val hits = withContext(Dispatchers.Default) {
                    val body = chapterPresenter.present(
                        body = raw,
                        layout = layout,
                        images = emptyList(),
                        mode = mode
                    ).body
                    searchChapterText(
                        chapterIndex = chapter.chapterIndex,
                        chapterTitle = chineseTextConverter.convert(chapter.title, mode),
                        body = body,
                        query = query
                    )
                }
                if (hits.isNotEmpty() && !publish(hits)) break
            }
        }
    )

    private val _uiState = MutableStateFlow(ReaderSearchUiState())
    val uiState = _uiState.asStateFlow()

    private var bookId: Long = -1
    private var mode = ChineseConversionMode.OFF
    private var searchJob: Job? = null
    private var activeScan: ReaderSearchIdentity? = null

    fun bind(bookId: Long, mode: ChineseConversionMode) {
        if (this.bookId == bookId && this.mode == mode) return
        searchJob?.cancel()
        activeScan = null
        this.bookId = bookId
        this.mode = mode
        _uiState.value = ReaderSearchUiState()
    }

    fun search(query: String) {
        searchJob?.cancel()
        val clean = query.trim()
        activeScan = null
        _uiState.value = ReaderSearchUiState(query = query)
        if (clean.length < MIN_QUERY_CHARS || bookId <= 0) return
        val identity = ReaderSearchIdentity(bookId, mode, clean)
        activeScan = identity
        _uiState.value = _uiState.value.copy(isSearching = true)
        searchJob = viewModelScope.launch {
            scanBook(identity.bookId, identity.mode, identity.query) { hits ->
                if (activeScan != identity) {
                    false
                } else {
                    _uiState.value = _uiState.value.copy(hits = _uiState.value.hits + hits)
                    _uiState.value.hits.size < MAX_TOTAL_HITS
                }
            }
            if (activeScan == identity) {
                _uiState.value = _uiState.value.copy(isSearching = false, completed = true)
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        activeScan = null
        _uiState.value = ReaderSearchUiState()
    }

    private companion object {
        const val MIN_QUERY_CHARS = 1
        const val MAX_TOTAL_HITS = 300
    }
}

internal suspend fun <T> searchChapterOrNull(block: suspend () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Throwable) {
    null
}
