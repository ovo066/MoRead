package com.mozhi.reader.feature.bookshelf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.importer.BookImportGateway
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.importer.BatchImportScheduler
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookshelfUiState(
    val books: List<BookEntity> = emptyList(),
    val allBooks: List<BookEntity> = emptyList(),
    val layout: ShelfLayout = ShelfLayout.GRID,
    val filter: ShelfFilter = ShelfFilter(),
    val tags: List<BookTagEntity> = emptyList(),
    val groups: List<ShelfGroupEntity> = emptyList(),
    val groupCounts: Map<Long?, Int> = emptyMap(),
    val tagCounts: Map<Long, Int> = emptyMap(),
    val tagRefs: List<BookTagRefEntity> = emptyList(),
    val collections: List<BookCollectionEntity> = emptyList(),
    /** 未经筛选的书籍总数，用于工具栏「N 本中的 M 本」这类文案。 */
    val totalBooks: Int = 0,
    /** 「正在阅读」卡的书；取全量最近阅读，不受筛选影响——筛选是给下面的书格用的。 */
    val recentBook: BookEntity? = null,
    /** 最近在读那本书当前章的章名，给「正在阅读」播放卡用。 */
    val recentChapterTitle: String = "",
    val isImporting: Boolean = false,
    val selectionActive: Boolean = false,
    val selectedBookIds: Set<Long> = emptySet()
) {
    val isSelectionMode: Boolean get() = selectionActive
    val selectedCount: Int get() = selectedBookIds.size
    val selectedGroupName: String
        get() = when {
            filter.ungroupedOnly -> "未分组"
            filter.groupId != null -> groups.firstOrNull { it.id == filter.groupId }?.name ?: "全部"
            else -> "全部"
        }

    fun tagIdsFor(bookId: Long): Set<Long> = tagRefs.asSequence()
        .filter { it.bookId == bookId }
        .map(BookTagRefEntity::tagId)
        .toSet()
}

sealed interface BookshelfEvent {
    data class OpenImportPreview(val sessionId: String) : BookshelfEvent
    data class OpenBook(val bookId: Long) : BookshelfEvent
    data class ShowMessage(val message: String) : BookshelfEvent
}

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val shelfRepository: ShelfOrganizationRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val importGateway: BookImportGateway,
    private val batchImportScheduler: BatchImportScheduler
) : ViewModel() {
    private val importing = MutableStateFlow(false)
    private val filter = MutableStateFlow(ShelfFilter())
    private val selectionActive = MutableStateFlow(false)
    private val selectedBookIds = MutableStateFlow<Set<Long>>(emptySet())
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

    private val baseState = combine(
        books,
        settingsRepository.settings,
        recentChapterTitle,
        importing,
        filter
    ) { books, settings, chapterTitle, isImporting, filter ->
        BookshelfBaseState(
            books = books,
            layout = settings.shelfLayout,
            filter = filter,
            recentBook = books.filter { it.lastReadAt > 0L }.maxByOrNull(BookEntity::lastReadAt),
            recentChapterTitle = chapterTitle,
            isImporting = isImporting
        )
    }

    val uiState = combine(baseState, shelfRepository.snapshot, selectedBookIds, selectionActive) {
            base, organization, selected, isSelectionActive ->
        val effectiveFilter = base.filter.withExistingGroups(
            organization.groups.map(ShelfGroupEntity::id).toSet()
        )
        BookshelfUiState(
            books = filterShelfBooks(base.books, organization.tagRefs, effectiveFilter).sortedForShelf(),
            allBooks = base.books,
            layout = base.layout,
            filter = effectiveFilter,
            tags = organization.tags,
            groups = organization.groups,
            groupCounts = organization.groupCounts,
            tagCounts = organization.tagCounts,
            tagRefs = organization.tagRefs,
            collections = organization.collections,
            totalBooks = base.books.size,
            recentBook = base.recentBook,
            recentChapterTitle = base.recentChapterTitle,
            isImporting = base.isImporting,
            selectionActive = isSelectionActive,
            selectedBookIds = selected.intersect(base.books.map(BookEntity::id).toSet())
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = BookshelfUiState()
    )

    init {
        viewModelScope.launch {
            runCatching { importGateway.backfillMissingCovers() }
            runCatching { importGateway.backfillMissingEpubToc() }
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
            runCatching {
                libraryRepository.deleteBook(book)
                shelfRepository.deleteEmptyCollections()
            }
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

    fun selectGroup(groupId: Long?, ungroupedOnly: Boolean = false) {
        filter.value = filter.value.copy(groupId = groupId, ungroupedOnly = ungroupedOnly)
    }

    fun toggleTagFilter(tagId: Long) {
        val current = filter.value
        filter.value = current.copy(
            tagIds = current.tagIds.toMutableSet().apply {
                if (!add(tagId)) remove(tagId)
            }
        )
    }

    fun setTagMatchMode(mode: TagMatchMode) {
        filter.value = filter.value.copy(matchMode = mode)
    }

    fun clearFilter() {
        filter.value = ShelfFilter()
    }

    /** 手动标记阅读状态；传 null 交还给按进度自动推导。 */
    fun setReadState(book: BookEntity, state: BookReadState?) {
        viewModelScope.launch {
            runCatching { libraryRepository.setReadState(book.id, state) }
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
            runCatching { libraryRepository.setPinned(book.id, pinned) }
                .onSuccess {
                    eventChannel.send(
                        BookshelfEvent.ShowMessage(if (pinned) "已置顶" else "已取消置顶")
                    )
                }
                .onFailure { eventChannel.send(BookshelfEvent.ShowMessage("操作失败，请稍后重试")) }
        }
    }

    fun enterSelection(bookId: Long? = null) {
        selectionActive.value = true
        selectedBookIds.value = bookId?.let(::setOf).orEmpty()
    }

    fun toggleSelection(bookId: Long) {
        toggleSelection(setOf(bookId))
    }

    fun toggleSelection(bookIds: Set<Long>) {
        selectedBookIds.value = selectedBookIds.value.toMutableSet().apply {
            if (bookIds.all(::contains)) removeAll(bookIds) else addAll(bookIds)
        }
    }

    fun exitSelection() {
        selectionActive.value = false
        selectedBookIds.value = emptySet()
    }

    fun selectAllVisible(bookIds: Set<Long>) {
        selectedBookIds.value =
            if (selectedBookIds.value.containsAll(bookIds)) emptySet() else bookIds
    }

    fun moveSelectedToGroup(groupId: Long?) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            shelfRepository.setBookGroup(ids, groupId)
            eventChannel.send(BookshelfEvent.ShowMessage("已移动 ${ids.size} 本书"))
            exitSelection()
        }
    }

    fun applyTagToSelection(tagId: Long, selected: Boolean) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (selected) shelfRepository.addTagToBooks(tagId, ids)
            else shelfRepository.removeTagFromBooks(tagId, ids)
        }
    }

    fun createTagForSelection(name: String) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val tagId = shelfRepository.createOrGetTag(name)
                shelfRepository.addTagToBooks(tagId, ids)
            }.onFailure {
                eventChannel.send(BookshelfEvent.ShowMessage(it.message ?: "新建标签失败"))
            }
        }
    }

    fun deleteSelected() {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.mapNotNull { libraryRepository.getBook(it) }
                .forEach { libraryRepository.deleteBook(it) }
            shelfRepository.deleteEmptyCollections()
            eventChannel.send(BookshelfEvent.ShowMessage("已移除 ${ids.size} 本书"))
            exitSelection()
        }
    }

    fun setSelectedReadState(state: BookReadState?) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { libraryRepository.setReadState(it, state) }
            eventChannel.send(BookshelfEvent.ShowMessage("已更新 ${ids.size} 本书"))
            exitSelection()
        }
    }

    /** 批量置顶/取消置顶：全部已置顶时取消，否则统一置顶。 */
    fun setSelectedPinned(pinned: Boolean) {
        val ids = selectedBookIds.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            ids.forEach { libraryRepository.setPinned(it, pinned) }
            eventChannel.send(
                BookshelfEvent.ShowMessage(
                    if (pinned) "已置顶 ${ids.size} 本书" else "已取消置顶 ${ids.size} 本书"
                )
            )
            exitSelection()
        }
    }

    fun createCollection(name: String, bookIds: Set<Long>) {
        viewModelScope.launch {
            shelfRepository.createCollection(name, bookIds)
            eventChannel.send(BookshelfEvent.ShowMessage("已创建合集"))
            exitSelection()
        }
    }

    fun addBooksToCollection(collectionId: Long, bookIds: Set<Long>) {
        viewModelScope.launch {
            shelfRepository.addBooksToCollection(collectionId, bookIds)
            eventChannel.send(BookshelfEvent.ShowMessage("已加入合集"))
            exitSelection()
        }
    }

    fun removeBooksFromCollection(bookIds: Set<Long>) {
        viewModelScope.launch { shelfRepository.removeBooksFromCollection(bookIds) }
    }

    fun renameCollection(id: Long, name: String) {
        viewModelScope.launch { shelfRepository.renameCollection(id, name) }
    }

    fun dissolveCollection(id: Long) {
        viewModelScope.launch { shelfRepository.dissolveCollection(id) }
    }
}

private data class BookshelfBaseState(
    val books: List<BookEntity>,
    val layout: ShelfLayout,
    val filter: ShelfFilter,
    val recentBook: BookEntity?,
    val recentChapterTitle: String,
    val isImporting: Boolean
)

private fun BookReadState.actionLabel(): String = when (this) {
    BookReadState.UNREAD -> "标为未读"
    BookReadState.READING -> "标为在读"
    BookReadState.FINISHED -> "标为已读完"
    BookReadState.SHELVED -> "标为搁置"
}

/** 置顶优先（按置顶时间倒序），其余按最近阅读，从未读过的按导入时间倒序垫底。 */
private fun List<BookEntity>.sortedForShelf(): List<BookEntity> = sortedWith(
    compareByDescending<BookEntity> { it.pinnedAt }
        .thenByDescending { it.lastReadAt }
        .thenByDescending { it.importedAt }
)
