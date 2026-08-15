package com.mozhi.reader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.database.entity.tagList
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.importer.BatchImportScheduler
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
    val filter: ShelfFilter = ShelfFilter(),
    /** 全部书上出现过的标签，供筛选菜单列出。 */
    val tags: List<String> = emptyList(),
    /** 未经筛选的书籍总数，用于工具栏「N 本中的 M 本」这类文案。 */
    val totalBooks: Int = 0,
    /** 「正在阅读」卡的书；取全量最近阅读，不受筛选影响——筛选是给下面的书格用的。 */
    val recentBook: BookEntity? = null,
    /** 最近在读那本书当前章的章名，给「正在阅读」播放卡用。 */
    val recentChapterTitle: String = "",
    val isImporting: Boolean = false
)

/** 书架筛选条件；会话级状态，不落 DataStore——下次进来还是看全部书更符合直觉。 */
data class ShelfFilter(
    val readState: BookReadState? = null,
    val tag: String? = null
) {
    val isActive: Boolean get() = readState != null || tag != null
}

sealed interface BookshelfEvent {
    data class OpenImportPreview(val sessionId: String) : BookshelfEvent
    data class OpenBook(val bookId: Long) : BookshelfEvent
    data class ShowMessage(val message: String) : BookshelfEvent
}

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val importGateway: BookImportGateway,
    private val batchImportScheduler: BatchImportScheduler
) : ViewModel() {
    private val importing = kotlinx.coroutines.flow.MutableStateFlow(false)
    private val filter = kotlinx.coroutines.flow.MutableStateFlow(ShelfFilter())
    private val eventChannel = Channel<BookshelfEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    // 书架与「最近在读」共用同一条 Room 查询。之前分别 observeBooks() 会在每次
    // 阅读进度更新时做两次相同的全表查询，固定开销与界面数据量无关。
    private val books = libraryRepository.observeBooks().shareIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
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
        importing,
        filter
    ) { books, settings, chapterTitle, isImporting, filter ->
        BookshelfUiState(
            books = books.applyFilter(filter).sortedForShelf(),
            layout = settings.shelfLayout,
            filter = filter,
            tags = books.flatMap(BookEntity::tagList).distinct().sorted(),
            totalBooks = books.size,
            recentBook = books.filter { it.lastReadAt > 0L }.maxByOrNull(BookEntity::lastReadAt),
            recentChapterTitle = chapterTitle,
            isImporting = isImporting
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
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

    /**
     * 多选导入：排进批量导入任务逐本处理。TXT 自动取最佳分章规则，不逐本弹预览——
     * 真要调整，阅读页的「重新分章」随时可用。
     */
    fun importBatch(uris: List<Uri>) {
        if (uris.isEmpty()) return
        batchImportScheduler.enqueue(uris)
        viewModelScope.launch {
            eventChannel.send(
                BookshelfEvent.ShowMessage("已开始导入 ${uris.size} 本，完成后会出现在书架")
            )
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

    fun setLayout(layout: ShelfLayout) {
        viewModelScope.launch { settingsRepository.setShelfLayout(layout) }
    }

    fun setReadStateFilter(state: BookReadState?) {
        filter.value = filter.value.copy(readState = state)
    }

    fun setTagFilter(tag: String?) {
        filter.value = filter.value.copy(tag = tag)
    }

    fun clearFilter() {
        filter.value = ShelfFilter()
    }

    /** 手动标记阅读状态；传 null 交还给按进度自动推导。 */
    fun setReadState(book: BookEntity, state: BookReadState?) {
        viewModelScope.launch {
            runCatching { repository.setReadState(book.id, state) }
                .onSuccess {
                    eventChannel.send(
                        BookshelfEvent.ShowMessage(
                            if (state == null) "已恢复按进度判断" else "已${state.actionLabel()}"
                        )
                    )
                }
                .onFailure { eventChannel.send(BookshelfEvent.ShowMessage("操作失败，请稍后重试")) }
        }
    }

    fun togglePinned(book: BookEntity) {
        viewModelScope.launch {
            val pinned = !book.isPinned
            runCatching { repository.setPinned(book.id, pinned) }
                .onSuccess {
                    eventChannel.send(
                        BookshelfEvent.ShowMessage(if (pinned) "已置顶" else "已取消置顶")
                    )
                }
                .onFailure { eventChannel.send(BookshelfEvent.ShowMessage("操作失败，请稍后重试")) }
        }
    }
}

private fun BookReadState.actionLabel(): String = when (this) {
    BookReadState.UNREAD -> "标为未读"
    BookReadState.READING -> "标为在读"
    BookReadState.FINISHED -> "标为已读完"
    BookReadState.SHELVED -> "标为搁置"
}

private fun List<BookEntity>.applyFilter(filter: ShelfFilter): List<BookEntity> = filter {
    (filter.readState == null || it.readState() == filter.readState) &&
        (filter.tag == null || filter.tag in it.tagList())
}

/** 置顶优先（按置顶时间倒序），其余按最近阅读，从未读过的按导入时间倒序垫底。 */
private fun List<BookEntity>.sortedForShelf(): List<BookEntity> = sortedWith(
    compareByDescending<BookEntity> { it.pinnedAt }
        .thenByDescending { it.lastReadAt }
        .thenByDescending { it.importedAt }
)
