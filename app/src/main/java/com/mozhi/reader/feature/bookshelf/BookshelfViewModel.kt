package com.mozhi.reader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val books: List<BookEntity> = emptyList(),
    val layout: ShelfLayout = ShelfLayout.GRID,
    /** 最近在读那本书当前章的章名，给「正在阅读」播放卡用。 */
    val recentChapterTitle: String = "",
    val isImporting: Boolean = false
)

sealed interface BookshelfEvent {
    data class OpenImportPreview(val sessionId: String) : BookshelfEvent
    data class OpenBook(val bookId: Long) : BookshelfEvent
    data class ShowMessage(val message: String) : BookshelfEvent
}

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val importGateway: BookImportGateway
) : ViewModel() {
    private val importing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val eventChannel = Channel<BookshelfEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    // 书架与「最近在读」共用同一条 Room 查询。之前分别 observeBooks() 会在每次
    // 阅读进度更新时做两次相同的全表查询，固定开销与界面数据量无关。
    private val books = libraryRepository.observeBooks().shareIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        replay = 1
    )

    private val recentChapterTitle = books
        .map { books ->
            books.filter { it.lastReadAt > 0L }.maxByOrNull(BookEntity::lastReadAt)
        }
        .map { book ->
            book?.let { it.id to it.lastReadChapterIndex }
        }
        .distinctUntilChanged()
        .map { key ->
            key?.let { (bookId, chapterIndex) ->
                libraryRepository.getChapterTitle(bookId, chapterIndex)
            }.orEmpty()
        }

    val uiState = combine(
        books,
        settingsRepository.settings,
        recentChapterTitle,
        importing
    ) { books, settings, chapterTitle, isImporting ->
        BookshelfUiState(
            books = books,
            layout = settings.shelfLayout,
            recentChapterTitle = chapterTitle,
            isImporting = isImporting
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookshelfUiState()
    )

    private val repository = libraryRepository

    init {
        viewModelScope.launch {
            runCatching { importGateway.backfillMissingCovers() }
        }
    }

    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            importing.value = true
            runCatching { importGateway.prepare(uri) }
                .onSuccess { prepared ->
                    when (prepared) {
                        is PreparedImport.PreviewReady ->
                            eventChannel.send(BookshelfEvent.OpenImportPreview(prepared.sessionId))
                        is PreparedImport.BookImported ->
                            eventChannel.send(BookshelfEvent.OpenBook(prepared.bookId))
                    }
                }
                .onFailure { error ->
                    eventChannel.send(
                        BookshelfEvent.ShowMessage(error.message ?: "导入失败，请检查文件")
                    )
                }
            importing.value = false
        }
    }

    fun deleteBook(book: BookEntity) {
        viewModelScope.launch {
            runCatching { repository.deleteBook(book) }
                .onFailure {
                    eventChannel.send(BookshelfEvent.ShowMessage("删除失败，请稍后重试"))
                }
        }
    }

    fun toggleLayout() {
        viewModelScope.launch {
            val target = if (uiState.value.layout == ShelfLayout.GRID) {
                ShelfLayout.LIST
            } else {
                ShelfLayout.GRID
            }
            settingsRepository.setShelfLayout(target)
        }
    }
}
