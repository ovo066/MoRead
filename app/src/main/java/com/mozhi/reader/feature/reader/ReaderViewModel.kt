package com.mozhi.reader.feature.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderFontImporter
import com.mozhi.reader.core.datastore.ReaderImageImporter
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.feature.reader.engine.ChapterMeta
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReaderUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val illustrations: List<IllustrationEntity> = emptyList(),
    /** 有讨论回复的批注 id：纯高亮有讨论时也要出「评」标记。 */
    val repliedAnnotationIds: Set<Long> = emptySet(),
    val showAiAnnotations: Boolean = true,
    /** 即划即改：上次使用的划线样式与颜色。 */
    val lastAnnotationStyle: AnnotationStyle = AnnotationStyle.HIGHLIGHT,
    val lastAnnotationColor: String = AnnotationColors.AMBER,
    val settings: ReaderSettings = ReaderSettings(),
    val currentChapterIndex: Int = 0,
    val currentCharOffset: Int = 0,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val readingProgress: Float = 0f,
    val chapterProgress: Float = 0f,
    val readingStats: ReaderStatistics = ReaderStatistics(),
    val isLoading: Boolean = true,
    val isPreparingText: Boolean = false,
    /** 当前章已排完版、首页可画；进场揭示以它为准，不再掐固定表。 */
    val isContentReady: Boolean = false,
    val errorMessage: String? = null
)

data class ReadingDayStat(
    val epochDay: Long,
    val durationMs: Long
)

data class ReaderStatistics(
    val totalDurationMs: Long = 0,
    val readingDays: Int = 0,
    val streakDays: Int = 0,
    val lastSevenDays: List<ReadingDayStat> = emptyList()
)

enum class PageTurnDirection {
    PREVIOUS,
    NEXT
}

sealed interface ReaderEvent {
    data class ShowMessage(val message: String) : ReaderEvent
    data class ConfirmFontImport(val pending: PendingReaderFont) : ReaderEvent
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val illustrationRepository: IllustrationRepository,
    private val mediaStore: BookMediaStore,
    private val settingsRepository: ReaderSettingsRepository,
    private val fontImporter: ReaderFontImporter,
    private val imageImporter: ReaderImageImporter
) : ViewModel(), ReaderContentController.Listener {
    private val bookId: Long = when (val value: Any? = savedStateHandle["bookId"]) {
        is Long -> value
        is Int -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    } ?: error("缺少 bookId")

    // 首帧就用热缓存里的真实设置：默认值画一帧再换纸色，进场会可见地跳一下。
    private val mutableState = MutableStateFlow(
        ReaderUiState(settings = settingsRepository.cachedSettings.value)
    )
    val uiState = mutableState.asStateFlow()
    private val eventChannel = Channel<ReaderEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val contentController = ReaderContentController(
        scope = viewModelScope,
        bodyLoader = ::loadChapterBody,
        listener = this
    )

    private var chapterEntities: List<ChapterEntity> = emptyList()
    private var contentHook: ((Int) -> Unit)? = null
    private var progressSaveJob: Job? = null
    private var readingResumedAt: Long? = null

    /** Guards against overwriting stored progress from a session that never opened a position. */
    private var hasOpenedPosition = false

    init {
        viewModelScope.launch {
            libraryRepository.observeBookmarks(bookId).collect { bookmarks ->
                mutableState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            annotationRepository.observeForBook(bookId).collect { annotations ->
                mutableState.update { it.copy(annotations = annotations) }
            }
        }
        viewModelScope.launch {
            illustrationRepository.observeForBook(bookId).collect { illustrations ->
                mutableState.update { it.copy(illustrations = illustrations) }
            }
        }
        viewModelScope.launch {
            annotationRepository.observeRepliedAnnotationIds(bookId).collect { ids ->
                mutableState.update { it.copy(repliedAnnotationIds = ids.toSet()) }
            }
        }
        viewModelScope.launch {
            settingsRepository.showAiAnnotations.collect { enabled ->
                mutableState.update { it.copy(showAiAnnotations = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.lastAnnotationStyle.collect { style ->
                mutableState.update { it.copy(lastAnnotationStyle = AnnotationStyle.fromWire(style)) }
            }
        }
        viewModelScope.launch {
            settingsRepository.lastAnnotationColor.collect { color ->
                mutableState.update { it.copy(lastAnnotationColor = AnnotationColors.normalize(color)) }
            }
        }
        viewModelScope.launch {
            libraryRepository.observeReadingDays(bookId).collect { days ->
                mutableState.update { it.copy(readingStats = days.toReaderStatistics()) }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update { it.copy(settings = settings) }
            }
        }
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            // 四件事互不依赖，串行做等于把进书前的等待叠四份 —— 长书的 getChapters
            // 要拉几千行，串在最后就把排版的起跑时间整个推后了。
            val bookAsync = async { libraryRepository.getBook(bookId) }
            val chaptersAsync = async { libraryRepository.getChapters(bookId) }
            val settingsAsync = async { settingsRepository.settings.first() }
            val imagesAsync = async { runCatching { loadInlineImages() }.getOrDefault(emptyMap()) }

            val book = bookAsync.await()
            if (book == null) {
                mutableState.update { it.copy(isLoading = false, errorMessage = "书籍不存在") }
                return@launch
            }
            mutableState.update { it.copy(book = book, settings = settingsAsync.await()) }
            var resolved = book
            if (book.textVersion < 1) {
                // Imported before plain text existed; the backfill worker runs at app start.
                mutableState.update { it.copy(isPreparingText = true) }
                val ready = awaitTextMaterialized()
                mutableState.update { it.copy(isPreparingText = false) }
                if (!ready) {
                    mutableState.update {
                        it.copy(isLoading = false, errorMessage = "正文还在准备中，请稍后再试")
                    }
                    return@launch
                }
                // 只有走过补齐这条慢路径才需要重读，正常进书不该多打一次库。
                resolved = libraryRepository.getBook(bookId) ?: book
            }
            val chapters = chaptersAsync.await()
            if (chapters.isEmpty()) {
                mutableState.update { it.copy(isLoading = false, errorMessage = "本书没有章节") }
                return@launch
            }
            chapterEntities = chapters
            contentController.setInlineImages(imagesAsync.await())
            contentController.setChapters(
                chapters.map { ChapterMeta(it.chapterIndex, it.title, it.charCount) }
            )
            mutableState.update {
                it.copy(
                    book = resolved,
                    chapters = chapters,
                    currentChapterIndex = resolved.lastReadChapterIndex,
                    currentCharOffset = resolved.lastReadCharOffset,
                    isLoading = false
                )
            }
            hasOpenedPosition = true
            contentController.openPosition(
                chapterIndex = resolved.lastReadChapterIndex,
                charOffset = resolved.lastReadCharOffset
            )
            if (resolved.textVersion < LibraryRepository.CURRENT_TEXT_VERSION) {
                // v1 正文可立即阅读；后台补齐 EPUB 图片 sidecar 后原位重排，不要求用户重开书。
                viewModelScope.launch {
                    val upgraded = libraryRepository.observeBook(bookId).first { observed ->
                        observed == null || observed.textVersion >= LibraryRepository.CURRENT_TEXT_VERSION
                    }
                    if (upgraded != null) contentController.setInlineImages(loadInlineImages())
                }
            }
        }
    }

    private suspend fun loadInlineImages(): Map<Int, List<InlineImageSource>> =
        mediaStore.read(bookId)
            .groupBy { it.chapterIndex }
            .mapValues { (_, images) ->
                images.map { image ->
                    InlineImageSource(
                        charOffset = image.charOffset,
                        imagePath = image.imagePath,
                        pixelWidth = image.pixelWidth,
                        pixelHeight = image.pixelHeight,
                        altText = image.altText
                    )
                }
            }

    private suspend fun awaitTextMaterialized(): Boolean {
        repeat(TEXT_WAIT_ATTEMPTS) {
            val book = libraryRepository.getBook(bookId) ?: return false
            if (book.textVersion >= 1) return true
            delay(TEXT_WAIT_INTERVAL_MS)
        }
        return false
    }

    private suspend fun loadChapterBody(chapterIndex: Int): String? {
        val chapter = chapterEntities.getOrNull(chapterIndex) ?: return null
        return libraryRepository.readChapterText(bookId, chapter)
    }

    // ---- ReaderContentController.Listener ----

    override fun onContentChanged(relativePosition: Int) {
        contentHook?.invoke(relativePosition)
        // relativePosition == 0 且窗口已排版 = 当前章首页可画，正文可以露脸了。
        if (relativePosition == 0 && contentController.isReady && !mutableState.value.isContentReady) {
            mutableState.update { it.copy(isContentReady = true) }
        }
    }

    override fun onPositionChanged(
        chapterIndex: Int,
        charOffset: Int,
        pageIndex: Int,
        pageCount: Int,
        bookProgress: Float
    ) {
        mutableState.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentCharOffset = charOffset,
                pageIndex = pageIndex,
                pageCount = pageCount,
                readingProgress = bookProgress,
                chapterProgress = contentController.chapterProgress()
            )
        }
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistPosition(chapterIndex, charOffset)
        }
    }

    /** The pane registers here so content changes re-render its bitmaps synchronously. */
    fun setContentHook(hook: ((Int) -> Unit)?) {
        contentHook = hook
        if (hook != null && contentController.isReady) hook(0)
    }

    fun onBoundaryHit(direction: PageTurnDirection) {
        viewModelScope.launch {
            eventChannel.send(
                ReaderEvent.ShowMessage(
                    if (direction == PageTurnDirection.NEXT) "已经是最后一页" else "已经是第一页"
                )
            )
        }
    }

    /**
     * Runs [NonCancellable] so backing out of the reader (which clears the ViewModel a frame
     * later) cannot cancel the final write — otherwise the last page turn would be lost.
     */
    fun flushProgress() {
        progressSaveJob?.cancel()
        val chapterIndex = contentController.chapterIndex
        val charOffset = contentController.charOffset
        viewModelScope.launch(kotlinx.coroutines.NonCancellable) {
            persistPosition(chapterIndex, charOffset)
        }
    }

    private suspend fun persistPosition(chapterIndex: Int, charOffset: Int) {
        // Never write from a session that failed to open (e.g. text still materializing) —
        // clearing locatorJson would permanently break the pending legacy migration.
        if (!hasOpenedPosition) return
        libraryRepository.saveProgress(
            bookId = bookId,
            locatorJson = "",
            chapterIndex = chapterIndex,
            charOffset = charOffset
        )
    }

    // ---- navigation ----

    fun goToChapter(chapterIndex: Int) {
        contentController.jumpToChapter(chapterIndex)
    }

    fun goToPrevChapter() {
        val target = contentController.chapterIndex - 1
        if (target < 0) {
            onBoundaryHit(PageTurnDirection.PREVIOUS)
            return
        }
        contentController.jumpToChapter(target)
    }

    fun goToNextChapter() {
        val target = contentController.chapterIndex + 1
        if (target >= chapterEntities.size) {
            onBoundaryHit(PageTurnDirection.NEXT)
            return
        }
        contentController.jumpToChapter(target)
    }

    fun seekWithinChapter(fraction: Float) {
        contentController.seekWithinChapter(fraction)
    }

    fun goToProgress(progress: Float) {
        contentController.jumpToProgress(progress)
    }

    fun goToBookmark(bookmark: BookmarkEntity) {
        contentController.jumpToChapter(bookmark.chapterIndex, bookmark.charOffset)
    }

    /** 书内搜索命中跳转：charOffset 为章内 UTF-16 偏移，与书签同轨。 */
    fun goToPosition(chapterIndex: Int, charOffset: Int) {
        contentController.jumpToChapter(chapterIndex, charOffset)
    }

    /** 听书自动翻页：位置已在当前显示页时不跳，避免逐句抖动。 */
    fun isShowingPosition(chapterIndex: Int, charOffset: Int): Boolean =
        contentController.isDisplaying(chapterIndex, charOffset)

    fun currentPageText(): String = (contentController.curPage() as? RenderPage.Laid)
        ?.page?.lines
        ?.asSequence()
        ?.filter { it.charLength > 0 && it.inlineImage == null }
        ?.joinToString(separator = "\n") { it.text }
        ?.trim()
        .orEmpty()

    fun addBookmark() {
        val chapterIndex = contentController.chapterIndex
        val charOffset = contentController.charOffset
        val label = chapterEntities.getOrNull(chapterIndex)?.title ?: "阅读书签"
        val excerpt = (contentController.curPage() as? RenderPage.Laid)
            ?.page?.lines
            ?.firstOrNull { it.charLength > 0 && !it.isTitle }
            ?.text?.trim()?.take(48)
            .orEmpty()
        viewModelScope.launch {
            libraryRepository.addBookmark(
                bookId = bookId,
                chapterIndex = chapterIndex,
                charOffset = charOffset,
                excerpt = excerpt,
                label = label
            )
            eventChannel.send(ReaderEvent.ShowMessage("已添加书签"))
        }
    }

    fun deleteBookmark(bookmarkId: Long) {
        viewModelScope.launch { libraryRepository.deleteBookmark(bookmarkId) }
    }

    /**
     * 即划即改第一步：一击落一条纯高亮（上次样式+颜色），返回 id 供浮条实时改写。
     * 想法内容走讨论串弹层补写，这里不再弹输入框。
     */
    suspend fun quickAnnotate(
        chapterIndex: Int,
        selectedText: String,
        range: IntRange
    ): Long? {
        if (range.isEmpty() || selectedText.isBlank()) return null
        val state = mutableState.value
        return annotationRepository.add(
            bookId = bookId,
            personaId = null,
            chapterIndex = chapterIndex,
            startCharOffset = range.first,
            endCharOffset = range.last + 1,
            selectedText = selectedText,
            note = "",
            colorTag = state.lastAnnotationColor,
            style = state.lastAnnotationStyle
        )
    }

    /** 浮条/讨论串里改样式；同时记为下次一击的默认。 */
    fun updateAnnotationStyle(annotationId: Long, style: AnnotationStyle, colorTag: String) {
        viewModelScope.launch {
            annotationRepository.updateStyle(annotationId, style, colorTag)
            settingsRepository.setLastAnnotationInk(style.wire, AnnotationColors.normalize(colorTag))
        }
    }

    /** 给纯高亮补写想法（讨论串楼主层）。 */
    fun updateAnnotationNote(annotationId: Long, note: String) {
        if (note.isBlank()) return
        viewModelScope.launch { annotationRepository.updateNote(annotationId, note.trim()) }
    }

    fun deleteAnnotation(annotationId: Long) {
        viewModelScope.launch { annotationRepository.delete(annotationId) }
    }

    // ---- settings ----

    fun setFontScale(value: Float) {
        viewModelScope.launch { settingsRepository.setFontScale(value) }
    }

    fun setFont(value: ReaderFont) {
        viewModelScope.launch { settingsRepository.setFont(value) }
    }

    fun selectCustomFont(id: String) {
        viewModelScope.launch { settingsRepository.selectCustomFont(id) }
    }

    fun importCustomFont(uri: Uri) {
        viewModelScope.launch {
            try {
                eventChannel.send(ReaderEvent.ConfirmFontImport(fontImporter.prepare(uri)))
            } catch (error: Throwable) {
                eventChannel.send(
                    ReaderEvent.ShowMessage("字体读取失败：${error.message ?: "文件格式不受支持"}")
                )
            }
        }
    }

    fun confirmCustomFont(pending: PendingReaderFont, displayName: String) {
        viewModelScope.launch {
            try {
                fontImporter.confirm(pending, displayName)
                eventChannel.send(ReaderEvent.ShowMessage("字体已导入并应用"))
            } catch (error: Throwable) {
                eventChannel.send(
                    ReaderEvent.ShowMessage("字体导入失败：${error.message ?: "文件格式不受支持"}")
                )
            }
        }
    }

    fun cancelCustomFontImport(pending: PendingReaderFont) {
        viewModelScope.launch { fontImporter.discard(pending) }
    }

    fun clearCustomFont() {
        viewModelScope.launch { settingsRepository.setFont(ReaderFont.SYSTEM) }
    }

    fun setLineHeight(value: Float) {
        viewModelScope.launch { settingsRepository.setLineHeight(value) }
    }

    fun setPageMargin(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMargin(value) }
    }

    fun setFontWeight(value: Int) {
        viewModelScope.launch { settingsRepository.setFontWeight(value) }
    }

    fun setLetterSpacing(value: Float) {
        viewModelScope.launch { settingsRepository.setLetterSpacingEm(value) }
    }

    fun setParagraphSpacing(value: Float) {
        viewModelScope.launch { settingsRepository.setParagraphSpacingEm(value) }
    }

    fun setFirstLineIndent(value: Float) {
        viewModelScope.launch { settingsRepository.setFirstLineIndentEm(value) }
    }

    fun setTitleScale(value: Float) {
        viewModelScope.launch { settingsRepository.setTitleScale(value) }
    }

    fun setTextJustification(value: Boolean) {
        viewModelScope.launch { settingsRepository.setTextJustification(value) }
    }

    fun setShowHeader(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowHeader(value) }
    }

    fun setShowFooter(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowFooter(value) }
    }

    fun setTheme(value: ReaderTheme) {
        viewModelScope.launch { settingsRepository.setTheme(value) }
    }

    fun selectCustomTheme(id: Long) {
        viewModelScope.launch { settingsRepository.selectCustomTheme(id) }
    }

    fun saveCustomTheme(theme: com.mozhi.reader.core.datastore.CustomReaderTheme) {
        viewModelScope.launch { settingsRepository.saveCustomTheme(theme) }
    }

    fun deleteCustomTheme(id: Long) {
        viewModelScope.launch { settingsRepository.deleteCustomTheme(id) }
    }

    fun setPageTurnAnimation(value: PageTurnAnimation) {
        viewModelScope.launch { settingsRepository.setPageTurnAnimation(value) }
    }

    fun setPageMode(value: com.mozhi.reader.core.datastore.PageMode) {
        viewModelScope.launch { settingsRepository.setPageMode(value) }
    }

    fun setKeepScreenOn(value: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(value) }
    }

    fun setImmersiveReading(value: Boolean) {
        viewModelScope.launch { settingsRepository.setImmersiveReading(value) }
    }

    fun setVolumeKeysPageTurn(value: Boolean) {
        viewModelScope.launch { settingsRepository.setVolumeKeysPageTurn(value) }
    }

    fun importBackgroundImage(uri: Uri) {
        viewModelScope.launch {
            runCatching { imageImporter.importImage(uri, selectAsBackground = true) }
                .onSuccess {
                    eventChannel.send(ReaderEvent.ShowMessage("已加入图片库并设为阅读背景"))
                }
                .onFailure { error ->
                    eventChannel.send(
                        ReaderEvent.ShowMessage(
                            "导入失败：${error.message ?: "文件格式不受支持"}"
                        )
                    )
                }
        }
    }

    fun selectBackgroundImage(imageId: String) {
        viewModelScope.launch { settingsRepository.selectBackgroundImage(imageId) }
    }

    fun clearBackgroundImage() {
        viewModelScope.launch { settingsRepository.setBackgroundImagePath(null) }
    }

    fun setBackgroundImageOpacity(value: Float) {
        viewModelScope.launch { settingsRepository.setBackgroundImageOpacity(value) }
    }

    fun setSyntaxHighlightEnabled(value: Boolean) {
        viewModelScope.launch { settingsRepository.setSyntaxHighlightEnabled(value) }
    }

    fun saveSyntaxHighlightRule(rule: com.mozhi.reader.core.datastore.ReaderSyntaxRule) {
        viewModelScope.launch { settingsRepository.saveSyntaxHighlightRule(rule) }
    }

    fun deleteSyntaxHighlightRule(id: Long) {
        viewModelScope.launch { settingsRepository.deleteSyntaxHighlightRule(id) }
    }

    // ---- reading-time accounting ----

    fun onReaderResumed() {
        if (readingResumedAt == null) {
            readingResumedAt = System.currentTimeMillis()
        }
    }

    fun onReaderPaused() {
        val resumedAt = readingResumedAt ?: return
        readingResumedAt = null
        val recordedAt = System.currentTimeMillis()
        val durationMs = (recordedAt - resumedAt).coerceAtLeast(0)
        viewModelScope.launch {
            libraryRepository.recordReadingDuration(
                bookId = bookId,
                durationMs = durationMs,
                recordedAt = recordedAt
            )
        }
    }

    override fun onCleared() {
        progressSaveJob?.cancel()
    }

    private companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS = 750L
        const val TEXT_WAIT_ATTEMPTS = 20
        const val TEXT_WAIT_INTERVAL_MS = 1500L
    }
}

private fun List<ReadingDailyEntity>.toReaderStatistics(): ReaderStatistics {
    val today = LocalDate.now().toEpochDay()
    val durationByDay = associate { it.epochDay to it.durationMs }
    val lastSevenDays = (6 downTo 0).map { offset ->
        val epochDay = today - offset
        ReadingDayStat(
            epochDay = epochDay,
            durationMs = durationByDay[epochDay] ?: 0
        )
    }
    var streakCursor = if ((durationByDay[today] ?: 0) > 0) today else today - 1
    var streakDays = 0
    while ((durationByDay[streakCursor] ?: 0) > 0) {
        streakDays += 1
        streakCursor -= 1
    }
    return ReaderStatistics(
        totalDurationMs = sumOf(ReadingDailyEntity::durationMs),
        readingDays = count { it.durationMs > 0 },
        streakDays = streakDays,
        lastSevenDays = lastSevenDays
    )
}
