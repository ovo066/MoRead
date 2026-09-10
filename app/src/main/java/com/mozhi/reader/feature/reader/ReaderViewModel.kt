package com.mozhi.reader.feature.reader

import android.net.Uri
import androidx.annotation.StringRes
import com.mozhi.reader.R
import com.mozhi.reader.core.datastore.PageMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.companion.ProactiveAnnotationScheduler
import com.mozhi.reader.ai.companion.ProactiveAnnotationBatchResult
import com.mozhi.reader.ai.companion.ProactiveAnnotationNoticeComposer
import com.mozhi.reader.ai.companion.annotationNoticeEligible
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ReadingDailyEntity
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.PublisherStyleMode
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderFontImporter
import com.mozhi.reader.core.datastore.ReaderImageImporter
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.chineseConversionModeFor
import com.mozhi.reader.core.datastore.validationError
import com.mozhi.reader.core.library.AnnotationRepository
import com.mozhi.reader.core.epub.dom.EpubDomFragmentLocator
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.EditableChapterDraft
import com.mozhi.reader.core.library.EpubResourcePath
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.library.ReaderTextAnchor
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.library.ResolvedTextAnchor
import com.mozhi.reader.core.text.ChineseTextConverter
import com.mozhi.reader.feature.importer.TxtChapterSplitter
import com.mozhi.reader.feature.importer.TxtTocRuleLoader
import com.mozhi.reader.feature.reader.engine.ChapterMeta
import com.mozhi.reader.feature.reader.engine.ChineseChapterPresenter
import com.mozhi.reader.feature.reader.engine.InlineImageSource
import com.mozhi.reader.feature.reader.engine.ReaderChapterContent
import com.mozhi.reader.feature.reader.engine.ReaderChapterSource
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.ReaderPageLink
import com.mozhi.reader.feature.reader.engine.RenderPage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
import kotlinx.coroutines.withContext

data class ReaderUiState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val tocEntries: List<BookTocEntryEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val annotations: List<AnnotationEntity> = emptyList(),
    val illustrations: List<IllustrationEntity> = emptyList(),
    /** 有讨论回复的批注 id：纯高亮有讨论时也要出「评」标记。 */
    val repliedAnnotationIds: Set<Long> = emptySet(),
    val showAiAnnotations: Boolean = true,
    val annotationNotice: ReaderAnnotationNotice? = null,
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
    val contentRevision: Int = 0,
    val errorMessage: String? = null
)

data class ReaderAnnotationNotice(val result: ProactiveAnnotationBatchResult, val message: String)

data class ReadingDayStat(
    val epochDay: Long,
    val durationMs: Long
)

data class ReaderSourceSelection(
    val start: Int,
    val end: Int,
    val text: String,
    val textAnchorJson: String
)

data class EpubLinkPreview(
    val sourceChapterIndex: Int,
    val href: String,
    val label: String,
    val targetChapterIndex: Int?,
    val targetCharOffset: Int,
    val targetTitle: String,
    val content: String,
    val externalUrl: String? = null,
    val presentedMode: ChineseConversionMode? = null
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
    data class ShowLocalizedMessage(@param:StringRes val resourceId: Int) : ReaderEvent
    data class ConfirmFontImport(val pending: PendingReaderFont) : ReaderEvent
    data class TextReplacementRuleSuggested(val rule: ReaderTextReplacementRule) : ReaderEvent
}

@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val illustrationRepository: IllustrationRepository,
    private val mediaStore: BookMediaStore,
    private val layoutStore: BookLayoutStore,
    private val settingsRepository: ReaderSettingsRepository,
    private val fontImporter: ReaderFontImporter,
    private val imageImporter: ReaderImageImporter,
    private val chapterSplitter: TxtChapterSplitter,
    private val tocRuleLoader: TxtTocRuleLoader,
    private val textReplacementRuleAgent: TextReplacementRuleAgent,
    private val annotationScheduler: ProactiveAnnotationScheduler,
    private val annotationNoticeComposer: ProactiveAnnotationNoticeComposer,
    private val chapterPresenter: ChineseChapterPresenter,
    private val chineseTextConverter: ChineseTextConverter
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
    private val bookmarkMutex = Mutex()
    private val eventChannel = Channel<ReaderEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    val contentController = ReaderContentController(
        scope = viewModelScope,
        chapterLoader = ::loadPresentedChapter,
        listener = this
    )

    // One bounded three-page render cache per reader back-stack entry, never per chat visit.
    private val paneHolderDelegate = lazy { ReaderPaneHolder(contentController) }
    internal val paneHolder: ReaderPaneHolder by paneHolderDelegate
    private val scrollPaneHolderDelegate = lazy { ScrollPaneHolder(contentController) }
    internal val scrollPaneHolder: ScrollPaneHolder by scrollPaneHolderDelegate

    private var chapterEntities: List<ChapterEntity> = emptyList()
    private var conversionMode = ChineseConversionMode.OFF
    private var rawInlineImages: Map<Int, List<InlineImageSource>> = emptyMap()
    private var rawTocEntries: List<BookTocEntryEntity> = emptyList()
    private var contentHook: ((Int) -> Unit)? = null
    private var progressSaveJob: Job? = null
    private var readingResumedAt: Long? = null
    private var previousPosition: ReaderPositionSnapshot? = null
    private var readerVisible = false
    private var readerVisibilityEpoch = 0L
    private var enqueuedReadyChapter: Int? = null
    private var visibleReadJob: Job? = null

    /** 用户已看到书末；只改完成度，不改变保存的续读锚点。 */
    private var reachedEnd = false

    private data class PendingAnchorJump(
        val chapterIndex: Int,
        val anchor: ReaderTextAnchor?,
        /** Fallbacks use the same original coordinates as persisted position columns. */
        val fallbackOffset: Int
    )

    private var pendingAnchorJump: PendingAnchorJump? = null
    private var suspendedNavigationJob: Job? = null

    /** Guards against overwriting stored progress from a session that never opened a position. */
    private var hasOpenedPosition = false

    init {
        viewModelScope.launch {
            libraryRepository.observeBook(bookId).collect { book ->
                if (book != null) mutableState.update { it.copy(book = book) }
            }
        }
        viewModelScope.launch {
            settingsRepository.companionAutonomySettings.collect { autonomy ->
                if (!autonomy.noticeActive) dismissAnnotationNotice()
            }
        }
        viewModelScope.launch {
            annotationScheduler.results.collect { result ->
                if (result.bookId != bookId) return@collect
                val epoch = readerVisibilityEpoch
                val autonomy = settingsRepository.companionAutonomySettings.first()
                if (result.personaId != settingsRepository.activePersonaId.first()) return@collect
                if (!annotationNoticeEligible(autonomy, result.createdCount, readerVisible)) return@collect
                val text = annotationNoticeComposer.compose(result, autonomy.annotationNotice) ?: return@collect
                val latest = settingsRepository.companionAutonomySettings.first()
                if (readerVisible && readerVisibilityEpoch == epoch && latest.noticeActive &&
                    latest.annotationNotice == autonomy.annotationNotice &&
                    result.personaId == settingsRepository.activePersonaId.first()) {
                    mutableState.update { it.copy(annotationNotice = ReaderAnnotationNotice(result, text)) }
                }
            }
        }
        viewModelScope.launch {
            libraryRepository.observeBookmarks(bookId).collect { bookmarks ->
                mutableState.update { it.copy(bookmarks = bookmarks) }
            }
        }
        viewModelScope.launch {
            libraryRepository.observeTocEntries(bookId).collect { entries ->
                rawTocEntries = entries
                mutableState.update { it.copy(tocEntries = displayTocEntries()) }
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
            val settings = settingsAsync.await()
            conversionMode = settings.chineseConversionModeFor(bookId)
            mutableState.update {
                it.copy(book = book, settings = settings, tocEntries = displayTocEntries())
            }
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
            rawInlineImages = imagesAsync.await()
            val mode = conversionMode
            val shownChapters = displayChapters(mode)
            contentController.setChapters(
                shownChapters.map { ChapterMeta(it.chapterIndex, it.title, it.charCount) }
            )
            val sourceOffset = resolved.lastReadCharOffset.coerceAtLeast(0)
            val displayFallback = if (mode == ChineseConversionMode.OFF) {
                sourceOffset
            } else {
                val chapter = chapters.firstOrNull {
                    it.chapterIndex == resolved.lastReadChapterIndex
                }
                if (chapter == null) {
                    0
                } else {
                    val sourceBody = libraryRepository.readChapterText(bookId, chapter)
                    val layout = layoutStore.readChapter(bookId, chapter.chapterIndex)
                    val images = rawInlineImages[chapter.chapterIndex].orEmpty()
                    withContext(Dispatchers.Default) {
                        chapterPresenter.resolveDisplayedPoint(
                            body = sourceBody,
                            layout = layout,
                            images = images,
                            sourceOffset = sourceOffset,
                            mode = mode
                        )
                    } ?: 0
                }
            }
            reachedEnd = resolved.reachedEnd
            mutableState.update {
                it.copy(
                    book = resolved,
                    chapters = shownChapters,
                    currentChapterIndex = resolved.lastReadChapterIndex,
                    currentCharOffset = displayFallback,
                    readingProgress = if (resolved.reachedEnd) 1f else 0f,
                    isLoading = false
                )
            }
            ReaderTextAnchorCodec.decode(resolved.lastReadLocator)?.let { anchor ->
                pendingAnchorJump = PendingAnchorJump(
                    resolved.lastReadChapterIndex,
                    anchor,
                    sourceOffset
                )
            }
            hasOpenedPosition = true
            contentController.openPosition(
                chapterIndex = resolved.lastReadChapterIndex,
                charOffset = displayFallback
            )
            if (resolved.sourceType == BookSourceType.EPUB ||
                resolved.textVersion < LibraryRepository.CURRENT_TEXT_VERSION
            ) {
                viewModelScope.launch {
                    val chapterTextLengths = chapters
                        .sortedBy(ChapterEntity::chapterIndex)
                        .map(ChapterEntity::charCount)
                    val layoutReady = resolved.sourceType != BookSourceType.EPUB ||
                        layoutStore.hasCurrentLayout(bookId, chapterTextLengths)
                    if (resolved.textVersion >= LibraryRepository.CURRENT_TEXT_VERSION && layoutReady) {
                        // The sidecar is committed before an import is published. Reloading here on
                        // every normal reader entry discarded the just-finished native layout and
                        // typeset the three visible chapters twice.
                        return@launch
                    }
                    if (awaitMaterializedAssets(resolved.sourceType, chapterTextLengths)) {
                        rawInlineImages = loadInlineImages()
                        contentController.reloadFromSource(resetBookEnd = false)
                    }
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

    private suspend fun awaitMaterializedAssets(
        sourceType: BookSourceType,
        expectedTextLengths: List<Int>
    ): Boolean {
        repeat(ASSET_WAIT_ATTEMPTS) {
            val book = libraryRepository.getBook(bookId) ?: return false
            val textReady = book.textVersion >= LibraryRepository.CURRENT_TEXT_VERSION
            val layoutReady = sourceType != BookSourceType.EPUB ||
                layoutStore.hasCurrentLayout(bookId, expectedTextLengths)
            if (textReady && layoutReady) return true
            delay(TEXT_WAIT_INTERVAL_MS)
        }
        return false
    }

    private fun displayChapters(mode: ChineseConversionMode = conversionMode) =
        chapterEntities.map { chapter ->
            chapter.copy(title = chineseTextConverter.convert(chapter.title, mode))
        }

    private fun displayTocEntries(mode: ChineseConversionMode = conversionMode) =
        rawTocEntries.map { entry ->
            entry.copy(title = chineseTextConverter.convert(entry.title, mode))
        }

    private suspend fun loadPresentedChapter(chapterIndex: Int): ReaderChapterContent? {
        val chapter = chapterEntities.getOrNull(chapterIndex) ?: return null
        val mode = conversionMode
        val body = libraryRepository.readChapterText(bookId, chapter)
        val layout = layoutStore.readChapter(bookId, chapterIndex)
        return withContext(Dispatchers.Default) {
            chapterPresenter.present(
                body = body,
                layout = layout,
                images = rawInlineImages[chapterIndex].orEmpty(),
                mode = mode
            )
        }
    }

    private fun currentAnchor(
        chapterIndex: Int,
        start: Int,
        end: Int = start
    ): ReaderTextAnchor? = contentController.chapterBody(chapterIndex)?.let { body ->
        ReaderTextAnchors.create(body, start, end, conversionMode)
    }

    /** Source-backed consumers (listen/citations) cross into the current presentation here. */
    suspend fun resolveSourceRange(
        chapterIndex: Int,
        start: Int,
        end: Int,
        sourceAnchorJson: String = ""
    ): ResolvedTextAnchor? {
        val chapter = chapterEntities.firstOrNull { it.chapterIndex == chapterIndex }
            ?: return null
        val source = libraryRepository.readChapterText(bookId, chapter)
        val anchor = ReaderTextAnchorCodec.decode(sourceAnchorJson)
            ?.takeIf { it.mode == ChineseConversionMode.OFF }
            ?: ReaderTextAnchors.create(
                source,
                start,
                end,
                ChineseConversionMode.OFF
            )
        return resolveSourceAnchor(chapterIndex, anchor, start, end, source)
    }

    private suspend fun resolveSourceAnchor(
        chapterIndex: Int,
        anchor: ReaderTextAnchor,
        fallbackStart: Int,
        fallbackEnd: Int,
        sourceBody: String,
        retryOnModeChange: Boolean = true
    ): ResolvedTextAnchor? {
        val mode = conversionMode
        val images = rawInlineImages[chapterIndex].orEmpty()
        val layout = layoutStore.readChapter(bookId, chapterIndex)
        val resolved = withContext(Dispatchers.Default) {
            val sourceStart = fallbackStart.coerceIn(0, sourceBody.length)
            val sourceRange = ReaderTextAnchors.resolveTextMatch(
                sourceBody,
                anchor,
                ChineseConversionMode.OFF,
                chineseTextConverter
            ) ?: ResolvedTextAnchor(
                sourceStart,
                fallbackEnd.coerceIn(sourceStart, sourceBody.length)
            )
            chapterPresenter.resolveDisplayedRange(
                body = sourceBody,
                layout = layout,
                images = images,
                sourceStart = sourceRange.start,
                sourceEnd = sourceRange.end,
                mode = mode
            )
        }
        if (mode != conversionMode) {
            return if (retryOnModeChange) {
                resolveSourceAnchor(
                    chapterIndex,
                    anchor,
                    fallbackStart,
                    fallbackEnd,
                    sourceBody,
                    false
                )
            } else {
                null
            }
        }
        return resolved
    }

    /** Converts one displayed reader boundary back to the raw source used by ListenEngine. */
    suspend fun sourceOffsetForDisplayed(chapterIndex: Int, displayOffset: Int): Int? {
        if (!mutableState.value.isContentReady) return null
        val displayed = contentController.chapterBody(chapterIndex) ?: return null
        val source = contentController.chapterSource(chapterIndex) ?: return null
        val mode = conversionMode
        val point = displayOffset.coerceIn(0, displayed.length)
        val displayedAnchor = ReaderTextAnchors.create(displayed, point, point, mode)
        return sourceOffsetForCapturedPresentation(
            point,
            displayedAnchor,
            source
        )
    }

    private suspend fun sourceOffsetForCapturedPresentation(
        displayOffset: Int,
        displayedAnchor: ReaderTextAnchor,
        source: ReaderChapterSource
    ): Int? {
        if (displayedAnchor.mode == ChineseConversionMode.OFF) {
            return displayOffset.coerceIn(0, source.body.length)
        }
        return withContext(Dispatchers.Default) {
            chapterPresenter.resolveSourcePoint(
                source = source,
                displayedAnchor = displayedAnchor
            )
        }
    }

    /** Captures original selection data and its display anchor together, before any mode switch. */
    suspend fun sourceSelectionForDisplayed(chapterIndex: Int, range: IntRange): ReaderSourceSelection? {
        if (range.isEmpty() || !mutableState.value.isContentReady) return null
        val displayed = contentController.chapterBody(chapterIndex) ?: return null
        val source = contentController.chapterSource(chapterIndex) ?: return null
        if (source.body.isEmpty()) return null
        val mode = conversionMode
        val anchor = ReaderTextAnchors.create(displayed, range.first, range.last + 1, mode)
        val startAnchor = ReaderTextAnchors.create(displayed, range.first, range.first, mode)
        val endAnchor = ReaderTextAnchors.create(displayed, range.last + 1, range.last + 1, mode)
        val start = sourceOffsetForCapturedPresentation(range.first, startAnchor, source)
            ?.coerceIn(0, source.body.lastIndex) ?: return null
        val end = sourceOffsetForCapturedPresentation(range.last + 1, endAnchor, source)
            ?.coerceIn(start + 1, source.body.length) ?: return null
        return ReaderSourceSelection(
            start = start,
            end = end,
            text = source.body.substring(start, end),
            textAnchorJson = ReaderTextAnchorCodec.encode(anchor)
        )
    }

    // ---- ReaderContentController.Listener ----

    override fun onContentChanged(relativePosition: Int) {
        if (relativePosition == 0 && contentController.isReady) {
            val pending = pendingAnchorJump
            if (pending != null && pending.chapterIndex == contentController.chapterIndex) {
                pendingAnchorJump = null
                val offset = resolveStoredRange(
                    pending.chapterIndex,
                    pending.fallbackOffset,
                    pending.fallbackOffset,
                    pending.anchor
                )?.start ?: 0
                if (offset != contentController.charOffset) {
                    contentController.jumpToChapter(pending.chapterIndex, offset)
                    return
                }
            }
        }
        contentHook?.invoke(relativePosition)
        if (relativePosition != 0) {
            mutableState.update { it.copy(contentRevision = it.contentRevision + 1) }
            return
        }
        if (contentController.isReady) {
            enqueueReadyChapter()
            mutableState.update {
                it.copy(
                    isContentReady = true,
                    contentRevision = it.contentRevision + 1
                )
            }
        }
    }

    override fun onSourceRevisionChanged() {
        reachedEnd = false
        previousPosition = null
        enqueuedReadyChapter = null
        visibleReadJob?.cancel()
        dismissAnnotationNotice()
        mutableState.update { it.copy(isContentReady = false, readingProgress = contentController.bookProgress()) }
    }

    override fun onContentError(chapterIndex: Int, error: Throwable) {
        mutableState.update {
            it.copy(
                isContentReady = false,
                errorMessage = "章节加载失败，请重试或重新导入本书"
            )
        }
    }

    override fun onPositionChanged(
        chapterIndex: Int,
        charOffset: Int,
        pageIndex: Int,
        pageCount: Int,
        bookProgress: Float
    ) {
        val previous = previousPosition
        previousPosition = ReaderPositionSnapshot(
            chapterIndex, pageIndex, pageCount,
            lastPageVisible = contentController.isLastPageReady()
        )
        if (
            previous != null &&
            chapterIndex == previous.chapterIndex + 1 &&
            contentController.positionChangeIsSequential &&
            previous.lastPageVisible
        ) {
            annotationScheduler.onChapterCompleted(bookId, previous.chapterIndex)
        }
        enqueueReadyChapter()
        mutableState.update {
            it.copy(
                currentChapterIndex = chapterIndex,
                currentCharOffset = charOffset,
                pageIndex = pageIndex,
                pageCount = pageCount,
                readingProgress = if (reachedEnd) 1f else bookProgress,
                chapterProgress = contentController.chapterProgress()
            )
        }
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistPosition(chapterIndex, charOffset)
        }
    }

    /** 两个阅读模式都在视口真实抵达末尾后回调；续读坐标仍保持页首。 */
    override fun onReachedBookEnd() {
        if (reachedEnd) return
        reachedEnd = true
        annotationScheduler.onChapterCompleted(bookId, contentController.chapterIndex)
        mutableState.update { it.copy(readingProgress = 1f) }
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            persistPosition(
                contentController.chapterIndex,
                contentController.charOffset
            )
        }
    }

    fun setReaderVisible(visible: Boolean) {
        if (readerVisible == visible) return
        readerVisible = visible
        readerVisibilityEpoch++
        if (visible) annotationScheduler.setReaderBook(bookId) else annotationScheduler.clearReaderBook(bookId)
        if (!visible) {
            enqueuedReadyChapter = null
            visibleReadJob?.cancel()
            dismissAnnotationNotice()
        } else enqueueReadyChapter()
    }

    /** Cold entry first publishes a placeholder; remember the first ready chapter separately. */
    private fun enqueueReadyChapter() {
        if (!readerVisible || !contentController.isReady) return
        val chapter = contentController.chapterIndex
        if (enqueuedReadyChapter == chapter) return
        enqueuedReadyChapter = chapter
        annotationScheduler.onChapterEntered(bookId, chapter)
    }

    fun dismissAnnotationNotice() {
        mutableState.update { it.copy(annotationNotice = null) }
    }

    fun setCompanionSidePaneEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setCompanionSidePaneEnabled(enabled) }
    }

    /** Only the page(s) actually displayed may expand spoiler scope, never prefetch or generation.
     * Resume position remains the focused source anchor, not the visible page end.
     */
    fun markVisiblePageRead(snapshot: com.mozhi.reader.feature.reader.engine.ReaderVisibleReadSnapshot) {
        if (!readerVisible || !contentController.isCurrentVisibleRead(snapshot) ||
            mutableState.value.settings.pageMode != com.mozhi.reader.core.datastore.PageMode.PAGINATED) return
        val chapterIndex = snapshot.chapterIndex
        val source = snapshot.source
        val body = source.displayedBody
        val mode = conversionMode
        visibleReadJob?.cancel()
        visibleReadJob = viewModelScope.launch {
            val point = snapshot.displayEnd.coerceIn(0, body.length)
            val anchor = ReaderTextAnchors.create(body, point, point, mode)
            val sourceEnd = sourceOffsetForCapturedPresentation(point, anchor, source) ?: return@launch
            if (readerVisible && mode == conversionMode && contentController.isCurrentVisibleRead(snapshot)) {
                libraryRepository.markVisibleReadEnd(bookId, chapterIndex, sourceEnd)
            }
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
                    when {
                        !contentController.isReady -> "正文尚未加载完成，请稍后重试"
                        direction == PageTurnDirection.NEXT && contentController.chapterIndex < contentController.chapterCount - 1 ->
                            "下一章尚未加载完成，请稍后重试"
                        direction == PageTurnDirection.PREVIOUS && contentController.chapterIndex > 0 ->
                            "上一章尚未加载完成，请稍后重试"
                        direction == PageTurnDirection.NEXT -> "已经是最后一页"
                        else -> "已经是第一页"
                    }
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
            persistPosition(chapterIndex, charOffset, forceCompletion = reachedEnd)
        }
    }

    private suspend fun persistPosition(
        chapterIndex: Int,
        charOffset: Int,
        forceCompletion: Boolean = false
    ) {
        // Never write from a session that failed to open (e.g. text still materializing) —
        // clearing locatorJson would permanently break the pending legacy migration.
        if (!hasOpenedPosition || !contentController.isReady) return
        if (!mutableState.value.isContentReady) return
        val displayed = contentController.chapterBody(chapterIndex) ?: return
        val source = contentController.chapterSource(chapterIndex) ?: return
        val point = charOffset.coerceIn(0, displayed.length)
        val anchor = ReaderTextAnchors.create(displayed, point, point, conversionMode)
        val sourceOffset = sourceOffsetForCapturedPresentation(
            point,
            anchor,
            source
        ) ?: return
        libraryRepository.saveProgress(
            bookId = bookId,
            locatorJson = ReaderTextAnchorCodec.encode(anchor),
            chapterIndex = chapterIndex,
            charOffset = sourceOffset,
            reachedEnd = forceCompletion || reachedEnd
        )
    }

    // ---- navigation ----

    fun supersedePendingNavigation() {
        pendingAnchorJump = null
        suspendedNavigationJob?.cancel()
        suspendedNavigationJob = null
    }

    fun goToChapter(chapterIndex: Int) {
        supersedePendingNavigation()
        contentController.jumpToChapter(chapterIndex)
    }

    /** 目录项可能指向章内 fragment，不能只跳到章节首页。 */
    fun goToTocEntry(chapterIndex: Int, href: String) {
        supersedePendingNavigation()
        val mode = conversionMode
        suspendedNavigationJob = viewModelScope.launch {
            val target = resolveEpubTarget(chapterIndex, href, chapterIndex)
            if (target == null) {
                if (mode == conversionMode) contentController.jumpToChapter(chapterIndex)
                return@launch
            }
            if (target.mode != conversionMode) return@launch
            contentController.jumpToChapter(target.chapterIndex, target.offset)
        }
    }

    /** 点击正文脚注/标注链接时先解析并读取目标内容，是否真正跳转由用户决定。 */
    suspend fun previewEpubLink(link: ReaderPageLink): EpubLinkPreview? {
        if (link.href.startsWith("http://", true) || link.href.startsWith("https://", true)) {
            return EpubLinkPreview(
                sourceChapterIndex = link.sourceChapterIndex,
                href = link.href,
                label = link.label,
                targetChapterIndex = null,
                targetCharOffset = 0,
                targetTitle = "外部链接",
                content = link.href,
                externalUrl = link.href
            )
        }
        val target = resolveEpubTarget(link.sourceChapterIndex, link.href) ?: return null
        val body = target.presented.body
        val start = target.offset.coerceIn(0, body.length)
        val end = target.endOffset.coerceIn(start, body.length)
        val previewEnd = if (end > start) end.coerceAtMost(start + LINK_PREVIEW_MAX_CHARS)
            else (start + LINK_PREVIEW_MAX_CHARS).coerceAtMost(body.length)
        val content = body.substring(start, previewEnd)
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { link.label.ifBlank { "该链接没有可预览的文字内容" } }
        return EpubLinkPreview(
            sourceChapterIndex = link.sourceChapterIndex,
            href = link.href,
            label = link.label,
            targetChapterIndex = target.chapterIndex,
            targetCharOffset = start,
            targetTitle = mutableState.value.chapters
                .getOrNull(target.chapterIndex)?.title.orEmpty(),
            content = content,
            presentedMode = target.mode
        )
    }

    fun goToEpubLink(preview: EpubLinkPreview) {
        val chapterIndex = preview.targetChapterIndex ?: return
        supersedePendingNavigation()
        if (preview.presentedMode == conversionMode) {
            contentController.jumpToChapter(chapterIndex, preview.targetCharOffset)
            return
        }
        suspendedNavigationJob = viewModelScope.launch {
            val target = resolveEpubTarget(preview.sourceChapterIndex, preview.href) ?: return@launch
            if (target.mode != conversionMode) return@launch
            contentController.jumpToChapter(target.chapterIndex, target.offset)
        }
    }

    private suspend fun resolveEpubTarget(
        sourceChapterIndex: Int,
        href: String,
        hintedChapterIndex: Int? = null,
        retryOnModeChange: Boolean = true
    ): EpubTarget? {
        val raw = href.trim()
        if (raw.isEmpty() || raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            return null
        }
        val source = chapterEntities.getOrNull(sourceChapterIndex) ?: return null
        val fragment = raw.substringAfter('#', "")
            .substringBefore('?')
            .takeIf(String::isNotBlank)
            ?.let { encoded ->
                runCatching {
                    URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8.name())
                }.getOrDefault(encoded)
            }
        val targetChapterIndex = hintedChapterIndex ?: run {
            val rawPath = raw.substringBefore('#').substringBefore('?')
            if (rawPath.isBlank()) {
                sourceChapterIndex
            } else {
                val resolved = EpubResourcePath.normalize(rawPath, source.href) ?: return null
                val matched = EpubResourcePath.matchKnown(resolved, chapterEntities.map(ChapterEntity::href))
                    ?: resolved
                chapterEntities.firstOrNull {
                    EpubResourcePath.normalize(it.href)?.equals(matched, true) == true
                }?.chapterIndex ?: return null
            }
        }
        val mode = conversionMode
        val presented = contentController.chapterBody(targetChapterIndex)?.let { body ->
            ReaderChapterContent(
                body = body,
                epubLayout = contentController.chapterLayout(targetChapterIndex)
            )
        } ?: loadPresentedChapter(targetChapterIndex) ?: return null
        if (mode != conversionMode) {
            return if (retryOnModeChange) {
                resolveEpubTarget(sourceChapterIndex, href, hintedChapterIndex, false)
            } else {
                null
            }
        }
        if (fragment == null) {
            return EpubTarget(targetChapterIndex, 0, 0, mode, presented)
        }
        val bundle = presented.epubLayout
            ?: return EpubTarget(targetChapterIndex, 0, 0, mode, presented)
        // 片段锚点优先在 DOM 里找：新导入的书不再落盘旧引擎的块列表，只有 DOM 知道 id 在哪。
        bundle.dom?.let { dom ->
            EpubDomFragmentLocator.locate(dom.bodyNode, fragment)?.let { range ->
                return EpubTarget(targetChapterIndex, range.first, range.last + 1, mode, presented)
            }
        }
        val matches = bundle.document.blocks.filter { block ->
            block.element.id == fragment || block.ancestors.any { it.id == fragment } ||
                block.spans.any { span -> span.elements.any { it.id == fragment } }
        }
        val start = matches.minOfOrNull { block ->
            block.spans.firstOrNull { span -> span.elements.any { it.id == fragment } }?.textStart
                ?: block.textStart
        } ?: 0
        val end = matches.maxOfOrNull { it.textEnd } ?: start
        return EpubTarget(targetChapterIndex, start, end, mode, presented)
    }

    fun goToPrevChapter() {
        val target = contentController.chapterIndex - 1
        if (target < 0) {
            onBoundaryHit(PageTurnDirection.PREVIOUS)
            return
        }
        supersedePendingNavigation()
        contentController.jumpToChapter(target)
    }

    fun goToNextChapter() {
        val target = contentController.chapterIndex + 1
        if (target >= chapterEntities.size) {
            onBoundaryHit(PageTurnDirection.NEXT)
            return
        }
        supersedePendingNavigation()
        contentController.jumpToChapter(target)
    }

    fun seekWithinChapter(fraction: Float) {
        supersedePendingNavigation()
        contentController.seekWithinChapter(fraction)
    }

    fun goToProgress(progress: Float) {
        supersedePendingNavigation()
        contentController.jumpToProgress(progress)
    }

    fun goToBookmark(bookmark: BookmarkEntity) {
        supersedePendingNavigation()
        pendingAnchorJump = PendingAnchorJump(
            bookmark.chapterIndex,
            ReaderTextAnchorCodec.decode(bookmark.locatorJson),
            bookmark.charOffset
        )
        contentController.jumpToChapter(bookmark.chapterIndex, bookmark.charOffset)
    }

    /** 书内搜索命中跳转：charOffset 为章内 UTF-16 偏移，与书签同轨。 */
    fun goToPosition(chapterIndex: Int, charOffset: Int) {
        supersedePendingNavigation()
        contentController.jumpToChapter(chapterIndex, charOffset)
    }

    /** 听书自动翻页：位置已在当前显示页时不跳，避免逐句抖动。 */
    fun isShowingPosition(chapterIndex: Int, charOffset: Int): Boolean =
        contentController.isDisplaying(chapterIndex, charOffset)

    fun canTurnToListeningPosition(chapterIndex: Int, charOffset: Int): Boolean =
        contentController.spreadMode && contentController.hasNextSpread() &&
            contentController.nextSpread().toList().filterIsInstance<RenderPage.Laid>().any {
                it.chapterIndex == chapterIndex && charOffset >= it.page.chapterPosition &&
                    charOffset < it.page.chapterPosition + it.page.charLength
            }

    fun focusReadingPosition(chapterIndex: Int, charOffset: Int) {
        if (chapterIndex == contentController.chapterIndex) contentController.focus(charOffset)
    }

    fun currentPageText(): String {
        val pages = if (contentController.spreadMode) contentController.curSpread().toList()
            else listOf(contentController.curPage())
        return pages.filterIsInstance<RenderPage.Laid>().flatMap { it.page.lines }
            .filter { it.charLength > 0 && it.inlineImage == null }
            .joinToString(separator = "\n") { it.text }.trim()
    }

    fun toggleBookmark() = updateBookmark(addOnly = false)

    /** Pull gestures only add: repeating one must never remove a bookmark. */
    fun addBookmarkFromPull() {
        if (mutableState.value.settings.pageMode == PageMode.SCROLL) return
        updateBookmark(addOnly = true)
    }

    private fun updateBookmark(addOnly: Boolean) {
        val chapterIndex = contentController.chapterIndex
        val charOffset = contentController.charOffset
        if (!mutableState.value.isContentReady) return
        val anchor = currentAnchor(chapterIndex, charOffset) ?: return
        val source = contentController.chapterSource(chapterIndex) ?: return
        // Capture the displayed anchor and original source before any page/conversion change.
        val knownId = mutableState.value.bookmarks.firstOrNull {
            it.chapterIndex == chapterIndex && bookmarkDisplayOffset(it) == charOffset
        }?.id
        val label = chapterEntities.getOrNull(chapterIndex)?.title ?: "阅读书签"
        viewModelScope.launch {
            val message = try {
                // Re-read under the lock: Room's observed UI list can lag behind a quick second pull.
                bookmarkMutex.withLock {
                    val sourceOffset = sourceOffsetForCapturedPresentation(charOffset, anchor, source)
                        ?: return@withLock R.string.reader_bookmark_failed
                    val excerpt = source.body.substring(sourceOffset).lineSequence().firstOrNull()
                        .orEmpty().trim().take(48)
                    val existing = libraryRepository.getBookmarks(bookId).firstOrNull {
                        it.chapterIndex == chapterIndex && (it.id == knownId || it.charOffset == sourceOffset)
                    }
                    if (existing != null) {
                        if (addOnly) R.string.reader_bookmark_exists else {
                            libraryRepository.deleteBookmark(existing.id)
                            R.string.reader_bookmark_removed
                        }
                    } else {
                        libraryRepository.addBookmark(
                            bookId = bookId,
                            chapterIndex = chapterIndex,
                            charOffset = sourceOffset,
                            locatorJson = ReaderTextAnchorCodec.encode(anchor),
                            excerpt = excerpt,
                            label = label
                        )
                        R.string.reader_bookmark_added
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                R.string.reader_bookmark_failed
            }
            eventChannel.send(ReaderEvent.ShowLocalizedMessage(message))
        }
    }

    private fun bookmarkDisplayOffset(bookmark: BookmarkEntity): Int? = resolveStoredRange(
        bookmark.chapterIndex,
        bookmark.charOffset,
        bookmark.charOffset,
        ReaderTextAnchorCodec.decode(bookmark.locatorJson)
    )?.start

    fun isCurrentPositionBookmarked(): Boolean {
        val chapterIndex = contentController.chapterIndex
        val charOffset = contentController.charOffset
        return mutableState.value.bookmarks.any {
            it.chapterIndex == chapterIndex && bookmarkDisplayOffset(it) == charOffset
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
        val source = sourceSelectionForDisplayed(chapterIndex, range) ?: return null
        return annotationRepository.add(
            bookId = bookId,
            personaId = null,
            chapterIndex = chapterIndex,
            startCharOffset = source.start,
            endCharOffset = source.end,
            selectedText = source.text,
            note = "",
            colorTag = state.lastAnnotationColor,
            style = state.lastAnnotationStyle,
            textAnchorJson = source.textAnchorJson
        )
    }

    fun resolveAnnotationRange(annotation: AnnotationEntity): ResolvedTextAnchor? {
        val anchor = ReaderTextAnchorCodec.decode(annotation.textAnchorJson)
            ?: ReaderTextAnchors.create(
                body = annotation.selectedText,
                start = 0,
                end = annotation.selectedText.length,
                mode = ChineseConversionMode.OFF
            ).copy(
                prefix = "",
                suffix = "",
                ratio = annotation.startCharOffset.toFloat() /
                    (chapterEntities.getOrNull(annotation.chapterIndex)?.charCount ?: 0).coerceAtLeast(1)
            )
        return resolveStoredRange(
            annotation.chapterIndex,
            annotation.startCharOffset,
            annotation.endCharOffset,
            anchor
        )
    }

    fun resolveIllustrationRange(illustration: IllustrationEntity): ResolvedTextAnchor? {
        val chapter = illustration.chapterIndex ?: return null
        val anchor = ReaderTextAnchorCodec.decode(illustration.textAnchorJson)
            ?: ReaderTextAnchors.create(
                body = illustration.sourceText,
                start = 0,
                end = illustration.sourceText.length,
                mode = ChineseConversionMode.OFF
            ).copy(
                prefix = "",
                suffix = "",
                ratio = (illustration.charOffset ?: 0).toFloat() /
                    (chapterEntities.getOrNull(chapter)?.charCount ?: 0).coerceAtLeast(1)
            )
        val start = illustration.charOffset ?: 0
        return resolveStoredRange(
            chapter,
            start,
            start + illustration.sourceText.length,
            anchor
        )
    }

    private fun resolveStoredRange(
        chapterIndex: Int,
        start: Int,
        end: Int,
        anchor: ReaderTextAnchor?
    ): ResolvedTextAnchor? {
        val body = contentController.chapterBody(chapterIndex) ?: return null
        val source = contentController.chapterSource(chapterIndex) ?: return null
        // Prefer surviving text matches in the captured mode, then original coordinates. A ratio
        // estimate must not replace valid source offsets when the displayed text no longer matches.
        if (anchor != null && anchor.mode == conversionMode && anchor.mode != ChineseConversionMode.OFF) {
            val resolved = ReaderTextAnchors.resolveTextMatch(body, anchor, conversionMode, chineseTextConverter)
            if (resolved != null && (start == end || resolved.end > resolved.start)) return resolved
        }
        val sourceStart = start.coerceIn(0, source.body.length)
        val fallback = ResolvedTextAnchor(sourceStart, end.coerceIn(sourceStart, source.body.length))
        val original = if (anchor?.mode == ChineseConversionMode.OFF) {
            ReaderTextAnchors.resolveTextMatch(source.body, anchor, ChineseConversionMode.OFF, chineseTextConverter)
                ?.takeIf { start == end || it.end > it.start } ?: fallback
        } else {
            fallback
        }
        return chapterPresenter.resolveDisplayedRange(
            source,
            original.start,
            original.end
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

    /** Saves an edited selection back into the local book text and reloads the visible window. */
    fun editSelectedText(chapterIndex: Int, range: IntRange, replacement: String) {
        if (!sourceEditAllowed()) return
        viewModelScope.launch {
            runCatching {
                val cursor = libraryRepository.replaceChapterText(
                    bookId = bookId,
                    chapterIndex = chapterIndex,
                    range = range,
                    replacement = replacement
                )
                refreshTextWindow(chapterIndex, cursor)
            }.onSuccess {
                eventChannel.send(ReaderEvent.ShowMessage("正文已修改"))
            }.onFailure { error ->
                eventChannel.send(
                    ReaderEvent.ShowMessage("修改正文失败：${error.message ?: "未知错误"}")
                )
            }
        }
    }

    /** Applies all currently enabled text-cleanup rules to this book only. */
    fun applyTextReplacementRules() {
        if (!sourceEditAllowed()) return
        viewModelScope.launch {
            runCatching {
                val rules = settingsRepository.settings.first().textReplacementRules
                val matches = libraryRepository.applyTextReplacementRules(bookId, rules)
                if (matches > 0) {
                    refreshTextWindow(contentController.chapterIndex, contentController.charOffset)
                }
                matches
            }.onSuccess { matches ->
                eventChannel.send(
                    ReaderEvent.ShowMessage(
                        if (matches > 0) "已应用规则，处理 $matches 处文本" else "没有匹配到需要处理的文本"
                    )
                )
            }.onFailure { error ->
                eventChannel.send(
                    ReaderEvent.ShowMessage("应用替换规则失败：${error.message ?: "请检查正则表达式"}")
                )
            }
        }
    }

    fun saveTextReplacementRule(rule: ReaderTextReplacementRule) {
        viewModelScope.launch {
            rule.validationError()?.let { error ->
                eventChannel.send(ReaderEvent.ShowMessage("规则无效：$error"))
                return@launch
            }
            runCatching { settingsRepository.saveTextReplacementRule(rule) }
                .onSuccess { eventChannel.send(ReaderEvent.ShowMessage("已保存清洗规则")) }
                .onFailure { error ->
                    eventChannel.send(
                        ReaderEvent.ShowMessage("保存规则失败：${error.message ?: "未知错误"}")
                    )
                }
        }
    }

    fun deleteTextReplacementRule(ruleId: Long) {
        viewModelScope.launch { settingsRepository.deleteTextReplacementRule(ruleId) }
    }

    /** Lets the model inspect excerpts from all chapters and return an editable regex draft. */
    fun generateTextReplacementRule(requirement: String) {
        viewModelScope.launch {
            runCatching { textReplacementRuleAgent.propose(bookId, requirement) }
                .onSuccess { rule -> eventChannel.send(ReaderEvent.TextReplacementRuleSuggested(rule)) }
                .onFailure { error ->
                    eventChannel.send(
                        ReaderEvent.ShowMessage("AI 生成规则失败：${error.message ?: "请检查模型配置"}")
                    )
                }
        }
    }

    /** Rebuilds a TXT book's chapter table from the current local text. */
    fun reidentifyChapters(customRegex: String) {
        if (!sourceEditAllowed()) return
        viewModelScope.launch {
            runCatching {
                val book = requireNotNull(libraryRepository.getBook(bookId)) { "书籍不存在" }
                require(book.sourceType == BookSourceType.TXT) { "当前仅支持重新识别 TXT 书籍的章节" }
                val existing = libraryRepository.getChapters(bookId)
                val source = buildString {
                    existing.forEachIndexed { index, chapter ->
                        if (index > 0) append("\n\n")
                        append(chapter.title).append('\n')
                        append(libraryRepository.readChapterText(bookId, chapter))
                    }
                }
                val split = customRegex.trim().takeIf(String::isNotBlank)
                    ?.let { regex ->
                        chapterSplitter.splitWithCustomRegex(source, regex)
                            ?: error("该表达式没有识别到足够的章节")
                    }
                    ?: chapterSplitter.chooseBest(source, tocRuleLoader.rules)
                require(split.chapters.isNotEmpty()) { "没有识别到可用章节" }
                libraryRepository.replaceBookChapters(
                    bookId = bookId,
                    chapters = split.chapters.mapIndexed { index, chapter ->
                        EditableChapterDraft(
                            index = index,
                            title = chapter.title.ifBlank { "第 ${index + 1} 章" },
                            href = "reader-reidentified/${index + 1}",
                            body = chapter.content
                        )
                    }
                )
                refreshTextWindow(0, 0)
                split
            }.onSuccess { split ->
                eventChannel.send(
                    ReaderEvent.ShowMessage(
                        "已重新识别 ${split.chapters.size} 章（${split.rule?.name ?: "自动分节"}）"
                    )
                )
            }.onFailure { error ->
                eventChannel.send(
                    ReaderEvent.ShowMessage("重新识别章节失败：${error.message ?: "未知错误"}")
                )
            }
        }
    }

    private fun sourceEditAllowed(): Boolean {
        if (conversionMode == ChineseConversionMode.OFF) return true
        viewModelScope.launch {
            eventChannel.send(ReaderEvent.ShowMessage("请先在排版中关闭繁简转换"))
        }
        return false
    }

    private suspend fun refreshTextWindow(chapterIndex: Int, charOffset: Int) {
        val chapters = libraryRepository.getChapters(bookId)
        val book = libraryRepository.getBook(bookId)
        val targetChapter = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        val targetOffset = charOffset.coerceIn(
            0,
            (chapters.firstOrNull { it.chapterIndex == targetChapter }?.charCount ?: 0)
        )
        chapterEntities = chapters
        supersedePendingNavigation()
        val shownChapters = displayChapters()
        contentController.setChapters(
            shownChapters.map { ChapterMeta(it.chapterIndex, it.title, it.charCount) }
        )
        contentController.reloadFromSource(targetChapter, targetOffset)
        mutableState.update {
            it.copy(
                book = book ?: it.book,
                chapters = shownChapters,
                currentChapterIndex = targetChapter,
                currentCharOffset = targetOffset
            )
        }
    }

    // ---- settings ----

    fun setChineseConversionMode(mode: ChineseConversionMode) {
        if (mode == conversionMode) return
        val previousPending = pendingAnchorJump
        supersedePendingNavigation()
        val chapter = contentController.chapterIndex
        val fallback = contentController.charOffset
        val anchor = currentAnchor(chapter, fallback).takeIf { mutableState.value.isContentReady }
        val source = contentController.chapterSource(chapter)
        val sourceOffset = if (source != null && anchor != null) {
            chapterPresenter.resolveSourcePoint(source, anchor)
        } else {
            null
        } ?: previousPending?.fallbackOffset ?: fallback
        val sourceAnchor = source?.let {
            ReaderTextAnchors.create(it.body, sourceOffset, sourceOffset, ChineseConversionMode.OFF)
        } ?: previousPending?.anchor
        conversionMode = mode
        val shownChapters = displayChapters(mode)
        val shownTocEntries = displayTocEntries(mode)
        pendingAnchorJump = PendingAnchorJump(chapter, sourceAnchor, sourceOffset)
        mutableState.update { state ->
            state.copy(
                settings = state.settings.copy(
                    bookChineseConversions = state.settings.bookChineseConversions.toMutableMap().apply {
                        if (mode == ChineseConversionMode.OFF) remove(bookId) else put(bookId, mode)
                    }
                ),
                chapters = shownChapters,
                tocEntries = shownTocEntries,
                isContentReady = false
            )
        }
        contentController.setChapters(
            shownChapters.map { chapterEntity ->
                ChapterMeta(
                    chapterEntity.chapterIndex,
                    chapterEntity.title,
                    chapterEntity.charCount
                )
            }
        )
        contentController.reloadFromSource(chapter, 0, resetBookEnd = false)
        viewModelScope.launch {
            settingsRepository.setBookChineseConversionMode(bookId, mode)
        }
    }

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

    fun setPublisherStyleMode(value: PublisherStyleMode) {
        viewModelScope.launch { settingsRepository.setPublisherStyleMode(value) }
    }

    fun setPageMargin(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMargin(value) }
    }

    fun setPageMarginLeft(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMarginLeft(value) }
    }

    fun setPageMarginRight(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMarginRight(value) }
    }

    fun setPageMarginTop(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMarginTop(value) }
    }

    fun setPageMarginBottom(value: Float) {
        viewModelScope.launch { settingsRepository.setPageMarginBottom(value) }
    }

    fun setHeaderMarginTop(value: Float) {
        viewModelScope.launch { settingsRepository.setHeaderMarginTop(value) }
    }

    fun setFooterMarginBottom(value: Float) {
        viewModelScope.launch { settingsRepository.setFooterMarginBottom(value) }
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

    fun setTitleTopSpacing(value: Float) {
        viewModelScope.launch { settingsRepository.setTitleTopSpacing(value) }
    }

    fun setTitleBottomSpacing(value: Float) {
        viewModelScope.launch { settingsRepository.setTitleBottomSpacing(value) }
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

    fun setTheme(value: ReaderTheme, slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch { settingsRepository.setTheme(value, slot) }
    }

    fun selectCustomTheme(id: Long, slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch { settingsRepository.selectCustomTheme(id, slot) }
    }

    fun saveCustomTheme(
        theme: com.mozhi.reader.core.datastore.CustomReaderTheme,
        slot: ReaderThemeSlot = ReaderThemeSlot.DAY
    ) {
        viewModelScope.launch { settingsRepository.saveCustomTheme(theme, slot) }
    }

    fun saveBookCustomTheme(
        theme: com.mozhi.reader.core.datastore.CustomReaderTheme,
        slot: ReaderThemeSlot
    ) {
        viewModelScope.launch { settingsRepository.saveBookCustomTheme(bookId, theme, slot) }
    }

    fun deleteCustomTheme(id: Long) {
        viewModelScope.launch { settingsRepository.deleteCustomTheme(id) }
    }

    fun setDayNightThemeAuto(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setDayNightThemeAuto(enabled) }
    }

    fun setBookThemeEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBookThemeEnabled(bookId, enabled) }
    }

    fun setBookTheme(value: ReaderTheme, slot: ReaderThemeSlot) {
        viewModelScope.launch { settingsRepository.setBookTheme(bookId, value, slot) }
    }

    fun selectBookCustomTheme(id: Long, slot: ReaderThemeSlot) {
        viewModelScope.launch { settingsRepository.selectBookCustomTheme(bookId, id, slot) }
    }

    fun setPageTurnAnimation(value: PageTurnAnimation) {
        viewModelScope.launch { settingsRepository.setPageTurnAnimation(value) }
    }

    fun setWidePageLayout(value: com.mozhi.reader.core.datastore.WidePageLayout) {
        viewModelScope.launch { settingsRepository.setWidePageLayout(value) }
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

    fun setScreenBrightness(value: Float) {
        viewModelScope.launch { settingsRepository.setScreenBrightness(value) }
    }

    fun importBackgroundImage(uri: Uri, slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch {
            // 先只入库，再按槽选中——importer 只认全局背景，日夜两套得由这里指定去处。
            runCatching { imageImporter.importImage(uri, selectAsBackground = false) }
                .onSuccess { asset ->
                    settingsRepository.selectBackgroundImage(asset.id, slot)
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

    fun selectBackgroundImage(imageId: String, slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch { settingsRepository.selectBackgroundImage(imageId, slot) }
    }

    fun clearBackgroundImage(slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch { settingsRepository.setBackgroundImagePath(null, slot) }
    }

    fun setBackgroundImageOpacity(value: Float, slot: ReaderThemeSlot = ReaderThemeSlot.DAY) {
        viewModelScope.launch { settingsRepository.setBackgroundImageOpacity(value, slot) }
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
        contentHook = null
        if (paneHolderDelegate.isInitialized()) paneHolder.release()
        if (scrollPaneHolderDelegate.isInitialized()) scrollPaneHolder.release()
        if (readerVisible) annotationScheduler.clearReaderBook(bookId)
        visibleReadJob?.cancel()
        progressSaveJob?.cancel()
    }

    private companion object {
        const val PROGRESS_SAVE_DEBOUNCE_MS = 750L
        const val TEXT_WAIT_ATTEMPTS = 20
        const val ASSET_WAIT_ATTEMPTS = 120
        const val TEXT_WAIT_INTERVAL_MS = 1500L
        const val LINK_PREVIEW_MAX_CHARS = 360
    }
}

private data class EpubTarget(
    val chapterIndex: Int,
    val offset: Int,
    val endOffset: Int,
    val mode: ChineseConversionMode,
    val presented: ReaderChapterContent
)

private data class ReaderPositionSnapshot(
    val chapterIndex: Int,
    val pageIndex: Int,
    val pageCount: Int,
    val lastPageVisible: Boolean
)

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
