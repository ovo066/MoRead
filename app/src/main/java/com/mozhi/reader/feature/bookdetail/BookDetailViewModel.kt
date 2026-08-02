package com.mozhi.reader.feature.bookdetail

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookCoverStore
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.NoteExporter
import com.mozhi.reader.core.library.NoteRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BookDetailUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val illustrations: List<IllustrationEntity> = emptyList(),
    val totalDurationMs: Long = 0,
    val readingDays: Int = 0,
    val streakDays: Int = 0,
    val durationsByEpochDay: Map<Long, Long> = emptyMap(),
    val isLoading: Boolean = true,
    val isWorking: Boolean = false
)

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
    private val coverStore: BookCoverStore,
    private val noteExporter: NoteExporter
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
        val illustrations: List<IllustrationEntity>
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

    private val content = combine(libraryContent, readingAssets) { library, assets ->
        BookContent(
            book = library.first,
            chapters = library.second,
            bookmarks = library.third,
            notes = assets.first,
            annotations = assets.second,
            illustrations = assets.third
        )
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { illustrationRepository.backfillLegacyFiles(bookId) }
        }
    }

    val uiState = combine(
        content,
        libraryRepository.observeReadingDays(bookId),
        working
    ) { content, days, isWorking ->
        BookDetailUiState(
            book = content.book,
            chapters = content.chapters,
            bookmarks = content.bookmarks,
            notes = content.notes,
            annotations = content.annotations,
            illustrations = content.illustrations,
            totalDurationMs = days.sumOf(ReadingDailyEntity::durationMs),
            readingDays = days.count { it.durationMs > 0 },
            streakDays = days.streakDays(),
            durationsByEpochDay = days
                .groupBy(ReadingDailyEntity::epochDay)
                .mapValues { (_, list) -> list.sumOf(ReadingDailyEntity::durationMs) },
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

    /** 保存用户手改的书名/作者/标签。校验规则与 TXT 导入路径一致。 */
    fun saveMetadata(title: String, author: String, tags: List<String>) {
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
                    tags = tags
                )
            }
                .onSuccess { eventChannel.send(BookDetailEvent.ShowMessage("已保存")) }
                .onFailure { eventChannel.send(BookDetailEvent.ShowMessage("保存失败")) }
        }
    }

    /**
     * 用户从相册选的图先压缩落盘再入库。写新文件成功后才改数据库，
     * 中途失败时旧封面仍然有效。
     */
    fun replaceCover(source: Uri) {
        viewModelScope.launch {
            working.value = true
            val saved = withContext(Dispatchers.IO) {
                runCatching { coverStore.save(bookId, source) }.getOrNull()
            }
            working.value = false
            if (saved == null) {
                eventChannel.send(BookDetailEvent.ShowMessage("无法读取所选图片"))
                return@launch
            }
            libraryRepository.replaceBookCover(bookId, saved.absolutePath)
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
