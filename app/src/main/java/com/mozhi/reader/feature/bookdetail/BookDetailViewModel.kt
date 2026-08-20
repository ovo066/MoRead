package com.mozhi.reader.feature.bookdetail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderImageImporter
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteExporter
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.core.library.ShelfOrganizationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
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

data class BookDetailUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val illustrations: List<IllustrationEntity> = emptyList(),
    /** personaId → 角色名，批注列表按作者筛选/署名用。 */
    val personaNames: Map<Long, String> = emptyMap(),
    val totalDurationMs: Long = 0,
    val readingDays: Int = 0,
    val streakDays: Int = 0,
    val durationsByEpochDay: Map<Long, Long> = emptyMap(),
    val imageLibrary: List<ReaderImageAsset> = emptyList(),
    val shelfGroups: List<ShelfGroupEntity> = emptyList(),
    val shelfTags: List<BookTagEntity> = emptyList(),
    val shelfTagRefs: List<BookTagRefEntity> = emptyList(),
    val audiobookReadyChapters: Int = 0,
    val audiobookTotalMillis: Long = 0,
    val audiobookRoleNames: List<String> = emptyList(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false
) {
    val selectedTags: List<BookTagEntity>
        get() = shelfTags.filter { tag ->
            shelfTagRefs.any { ref -> ref.bookId == book?.id && ref.tagId == tag.id }
        }

    val selectedGroupName: String
        get() = shelfGroups.firstOrNull { it.id == book?.groupId }?.name ?: "未分组"
}

sealed interface BookDetailEvent {
    data class ShowMessage(val message: String) : BookDetailEvent

    /** 交给界面 startActivity 的分享意图（笔记导出）。 */
    data class LaunchIntent(val intent: android.content.Intent) : BookDetailEvent
}

@HiltViewModel
class BookDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val noteRepository: NoteRepository,
    private val annotationRepository: AnnotationRepository,
    private val illustrationRepository: IllustrationRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val imageImporter: ReaderImageImporter,
    private val noteExporter: NoteExporter,
    private val shelfRepository: ShelfOrganizationRepository,
    private val audiobookRepository: AudiobookRepository,
    personaDao: com.mozhi.reader.core.database.dao.PersonaDao
) : ViewModel() {
    private val bookId: Long = when (val value: Any? = savedStateHandle["bookId"]) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    } ?: error("缺少 bookId")

    private val working = MutableStateFlow(false)
    private val eventChannel = Channel<BookDetailEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private data class BookContent(
        val book: BookEntity?,
        val chapters: List<ChapterEntity>,
        val bookmarks: List<BookmarkEntity>,
        val notes: List<NoteEntity>,
        val annotations: List<AnnotationEntity>,
        val illustrations: List<IllustrationEntity>,
        val personaNames: Map<Long, String>
    )

    private val libraryContent = combine(
        libraryRepository.observeBook(bookId),
        libraryRepository.observeChapters(bookId),
        libraryRepository.observeBookmarks(bookId)
    ) { book, chapters, bookmarks -> Triple(book, chapters, bookmarks) }

    private val readingAssets = combine(
        noteRepository.observeForBook(bookId),
        annotationRepository.observeForBook(bookId),
        illustrationRepository.observeForBook(bookId)
    ) { notes, annotations, illustrations -> Triple(notes, annotations, illustrations) }

    private val personaNames = personaDao.observePersonas()
        .map { personas -> personas.associate { it.id to it.name } }

    private val content = combine(libraryContent, readingAssets, personaNames) { library, assets, names ->
        BookContent(
            book = library.first,
            chapters = library.second,
            bookmarks = library.third,
            notes = assets.first,
            annotations = assets.second,
            illustrations = assets.third,
            personaNames = names
        )
    }

    private data class DetailExtras(
        val settings: com.mozhi.reader.core.datastore.ReaderSettings,
        val shelf: com.mozhi.reader.core.library.ShelfOrganizationSnapshot,
        val audiobookChapters: List<com.mozhi.reader.core.database.entity.AudiobookChapterEntity>,
        val audiobookRoles: List<com.mozhi.reader.core.database.entity.AudiobookRoleEntity>
    )

    private val detailExtras = combine(
        settingsRepository.settings,
        shelfRepository.snapshot,
        audiobookRepository.observeChapters(bookId),
        audiobookRepository.observeRoles(bookId)
    ) { settings, shelf, audiobookChapters, audiobookRoles ->
        DetailExtras(settings, shelf, audiobookChapters, audiobookRoles)
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { illustrationRepository.backfillLegacyFiles(bookId) }
        }
    }

    val uiState = combine(
        content,
        libraryRepository.observeReadingDays(bookId),
        working,
        detailExtras
    ) { content, days, isWorking, extras ->
        BookDetailUiState(
            book = content.book,
            chapters = content.chapters,
            bookmarks = content.bookmarks,
            notes = content.notes,
            annotations = content.annotations,
            illustrations = content.illustrations,
            personaNames = content.personaNames,
            totalDurationMs = days.sumOf(ReadingDailyEntity::durationMs),
            readingDays = days.count { it.durationMs > 0 },
            streakDays = days.streakDays(),
            durationsByEpochDay = days
                .groupBy(ReadingDailyEntity::epochDay)
                .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) },
            imageLibrary = extras.settings.imageLibrary,
            shelfGroups = extras.shelf.groups,
            shelfTags = extras.shelf.tags,
            shelfTagRefs = extras.shelf.tagRefs,
            audiobookReadyChapters = extras.audiobookChapters.count { it.state == "READY" },
            audiobookTotalMillis = extras.audiobookChapters
                .filter { it.state == "READY" }
                .sumOf { it.totalMillis },
            audiobookRoleNames = extras.audiobookRoles.map { it.name },
            isLoading = false,
            isWorking = isWorking
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = BookDetailUiState()
    )

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch { libraryRepository.deleteBookmark(bookmarkId) }
    }

    fun createNote(title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        viewModelScope.launch {
            val book = uiState.value.book
            noteRepository.create(
                bookId = bookId,
                personaId = null,
                title = title.trim().ifBlank { "读书笔记" },
                contentMarkdown = content.trim(),
                relatedChapterIndex = book?.lastReadChapterIndex,
                relatedCharOffset = book?.lastReadCharOffset
            )
            eventChannel.send(BookDetailEvent.ShowMessage("读书笔记已保存"))
        }
    }

    fun updateNote(noteId: Long, title: String, content: String) {
        if (title.isBlank() && content.isBlank()) return
        viewModelScope.launch {
            noteRepository.updateContent(noteId, title.trim().ifBlank { "读书笔记" }, content.trim())
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch { noteRepository.delete(noteId) }
    }

    /** 导出全部笔记为 Markdown 到系统 Documents/墨知。 */
    fun exportNotes() {
        viewModelScope.launch {
            val notes = uiState.value.notes
            if (notes.isEmpty()) {
                eventChannel.send(BookDetailEvent.ShowMessage("还没有可导出的笔记"))
                return@launch
            }
            val path = noteExporter.exportToDocuments(
                bookTitle = uiState.value.book?.title.orEmpty(),
                notes = notes
            )
            eventChannel.send(
                BookDetailEvent.ShowMessage(
                    if (path != null) "已导出到 $path" else "导出失败"
                )
            )
        }
    }

    /** 分享全部笔记（Markdown 文件，ACTION_SEND）。 */
    fun shareNotes() {
        viewModelScope.launch {
            val notes = uiState.value.notes
            if (notes.isEmpty()) {
                eventChannel.send(BookDetailEvent.ShowMessage("还没有可分享的笔记"))
                return@launch
            }
            val intent = noteExporter.buildShareIntent(
                bookTitle = uiState.value.book?.title.orEmpty(),
                notes = notes
            )
            if (intent != null) {
                eventChannel.send(BookDetailEvent.LaunchIntent(intent))
            } else {
                eventChannel.send(BookDetailEvent.ShowMessage("分享失败"))
            }
        }
    }

    fun deleteAnnotation(annotationId: Long) {
        viewModelScope.launch { annotationRepository.delete(annotationId) }
    }

    fun deleteIllustration(illustration: IllustrationEntity) {
        viewModelScope.launch { illustrationRepository.delete(illustration) }
    }

    /** 手动标记阅读状态；传 null 交还给按进度自动推导。与书架长按菜单同一份写入。 */
    fun setReadState(state: BookReadState?) {
        viewModelScope.launch {
            runCatching { libraryRepository.setReadState(bookId, state) }
                .onFailure { eventChannel.send(BookDetailEvent.ShowMessage("操作失败，请稍后重试")) }
        }
    }

    /** 保存用户手改的书名/作者。标签由规范化标签选择器单独维护。 */
    fun saveMetadata(title: String, author: String) {
        val cleanTitle = title.trim()
        viewModelScope.launch {
            val message = when {
                cleanTitle.isEmpty() -> "书名不能为空"
                cleanTitle.length > MAX_TITLE_LENGTH -> "书名不能超过 $MAX_TITLE_LENGTH 个字符"
                author.trim().length > MAX_AUTHOR_LENGTH ->
                    "作者不能超过 $MAX_AUTHOR_LENGTH 个字符"
                else -> null
            }
            if (message != null) {
                eventChannel.send(BookDetailEvent.ShowMessage(message))
                return@launch
            }
            runCatching {
                libraryRepository.updateBookMetadata(
                    bookId = bookId,
                    title = cleanTitle,
                    author = author,
                    tags = uiState.value.selectedTags.map(BookTagEntity::name)
                )
            }
                .onSuccess { eventChannel.send(BookDetailEvent.ShowMessage("已保存")) }
                .onFailure { eventChannel.send(BookDetailEvent.ShowMessage("保存失败")) }
        }
    }

    fun setShelfGroup(groupId: Long?) {
        viewModelScope.launch {
            runCatching { shelfRepository.setBookGroup(listOf(bookId), groupId) }
                .onFailure { eventChannel.send(BookDetailEvent.ShowMessage("更新分组失败")) }
        }
    }

    fun setTag(tagId: Long, selected: Boolean) {
        viewModelScope.launch {
            runCatching {
                if (selected) shelfRepository.addTagToBooks(tagId, listOf(bookId))
                else shelfRepository.removeTagFromBooks(tagId, listOf(bookId))
            }.onFailure { eventChannel.send(BookDetailEvent.ShowMessage("更新标签失败")) }
        }
    }

    fun createAndApplyTag(name: String) {
        viewModelScope.launch {
            runCatching {
                val tagId = shelfRepository.createOrGetTag(name)
                shelfRepository.addTagToBooks(tagId, listOf(bookId))
            }.onFailure { error ->
                eventChannel.send(BookDetailEvent.ShowMessage(error.message ?: "新建标签失败"))
            }
        }
    }

    /** 从系统选择器导入的新图同时进入图片库，之后可被其他书籍与背景复用。 */
    fun replaceCover(source: Uri) {
        viewModelScope.launch {
            working.value = true
            val saved = runCatching { imageImporter.importImage(source) }.getOrNull()
            working.value = false
            if (saved == null) {
                eventChannel.send(BookDetailEvent.ShowMessage("无法读取所选图片"))
                return@launch
            }
            libraryRepository.replaceBookCover(bookId, saved.filePath)
            eventChannel.send(BookDetailEvent.ShowMessage("已加入图片库并设为封面"))
        }
    }

    fun selectCover(imageId: String) {
        viewModelScope.launch {
            val image = uiState.value.imageLibrary.firstOrNull { it.id == imageId }
            if (image == null) {
                eventChannel.send(BookDetailEvent.ShowMessage("图片不存在或已删除"))
                return@launch
            }
            libraryRepository.replaceBookCover(bookId, image.filePath)
            eventChannel.send(BookDetailEvent.ShowMessage("封面已更新"))
        }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 200
        const val MAX_AUTHOR_LENGTH = 120
    }
}

internal fun List<ReadingDailyEntity>.streakDays(): Int {
    val today = LocalDate.now().toEpochDay()
    val durationByDay = groupBy(ReadingDailyEntity::epochDay)
        .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) }
    var cursor = if ((durationByDay[today] ?: 0) > 0) today else today - 1
    var streak = 0
    while ((durationByDay[cursor] ?: 0) > 0) {
        streak += 1
        cursor -= 1
    }
    return streak
}
