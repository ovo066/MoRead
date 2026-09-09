package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.library.EpubLayoutChapterBundle
import com.mozhi.reader.core.datastore.ChineseConversionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Chapter metadata the controller needs, decoupled from Room entities. */
data class ChapterMeta(
    val index: Int,
    val title: String,
    val charCount: Int
)

data class ReaderChapterContent(
    val body: String,
    val epubLayout: EpubLayoutChapterBundle? = null,
    val inlineImages: List<InlineImageSource> = emptyList(),
    /** Original coordinates travel with the displayed chapter through reloads and window changes. */
    val source: ReaderChapterSource? = null
)

data class ReaderChapterSource(
    val body: String,
    val displayedBody: String = body,
    val mode: ChineseConversionMode = ChineseConversionMode.OFF,
    val boundaries: List<Int> = listOf(0, body.length).distinct(),
    val positions: Map<Int, Int> = mapOf(0 to 0, body.length to body.length)
)

/**
 * A page handed to the render layer. Real pages carry layout; placeholder pages appear when the
 * user outruns loading, and are drawn as a centered message exactly like Legado's `format()` page.
 */
sealed interface RenderPage {
    val chapterIndex: Int
    val chapterTitle: String
    val pageIndex: Int
    val pageCount: Int

    data class Laid(
        override val chapterIndex: Int,
        override val chapterTitle: String,
        override val pageIndex: Int,
        override val pageCount: Int,
        val page: TextPage
    ) : RenderPage

    /** Deliberate chapter-end paper, never a loading state or a progress position. */
    data class Blank(
        override val chapterIndex: Int,
        override val chapterTitle: String
    ) : RenderPage {
        override val pageIndex: Int get() = -1
        override val pageCount: Int get() = 0
    }

    data class Placeholder(
        override val chapterIndex: Int,
        override val chapterTitle: String,
        val message: String = "加载中…"
    ) : RenderPage {
        override val pageIndex: Int get() = 0
        override val pageCount: Int get() = 1
    }
}

/**
 * Port of Legado's `ReadBook` + `TextPageFactory`: a three-chapter window over the book with the
 * reading position expressed as (chapterIndex, charOffset) — the char offset is the single source
 * of truth, the page index is derived, so a re-typeset never loses the position.
 *
 * All public state transitions happen on the main thread; typesetting runs on [Dispatchers.Default]
 * and results are dropped if the window has moved on (Legado's stale-content gate).
 */
class ReaderContentController(
    private val scope: CoroutineScope,
    private val chapterLoader: suspend (chapterIndex: Int) -> ReaderChapterContent?,
    private val listener: Listener
) {
    interface Listener {
        /** -1: only the previous page changed; 1: only the next; 0: everything. */
        fun onContentChanged(relativePosition: Int)
        fun onPositionChanged(
            chapterIndex: Int,
            charOffset: Int,
            pageIndex: Int,
            pageCount: Int,
            bookProgress: Float
        )

        /** Fired only after the actual last chapter/last page is visible; never changes position. */
        fun onReachedBookEnd() = Unit

        /** A failed current chapter must surface as an error instead of an endless spinner. */
        fun onContentError(chapterIndex: Int, error: Throwable) = Unit

        /** Genuine source edits invalidate the old completion latch, unlike presentation changes. */
        fun onSourceRevisionChanged() = Unit
    }

    private class Slot(
        val index: Int,
        val content: ReaderChapterContent,
        var chapter: TextChapter?
    )

    private data class PendingProgressJump(
        val chapterIndex: Int,
        val rawFraction: Double,
        val sourceVersion: Int
    )

    private var chapters: List<ChapterMeta> = emptyList()
    private var inlineMarkersByChapter: Map<Int, List<InlineMarkerReservation>> = emptyMap()
    private var cumulativeChars: LongArray = LongArray(0)
    private var totalChars: Long = 0

    private var typesetter: ChapterTypesetter? = null
    private var environmentVersion = 0
    /** A retained surface must reapply its typography if another surface took ownership. */
    internal val environmentGeneration: Int get() = environmentVersion
    private var sourceVersion = 0
    val sourceGeneration: Int get() = sourceVersion
    var navigationGeneration: Int = 0
        private set

    var chapterIndex: Int = 0
        private set
    var charOffset: Int = 0
        private set

    private var prevSlot: Slot? = null
    private var curSlot: Slot? = null
    private var nextSlot: Slot? = null
    private var pendingProgressJump: PendingProgressJump? = null
    private val loadingIndices = LinkedHashSet<Int>()
    private val relayoutJobs = mutableMapOf<Int, Job>()
    private val markerVersions = mutableMapOf<Int, Int>()
    var layoutGeneration: Int = 0
        private set
    var spreadMode: Boolean = false
        private set
    private var bookEndNotified = false
    var positionChangeIsSequential: Boolean = false
        private set
    private var pendingScroll: Pair<Int, Int>? = null

    fun turnPages(relative: Int): List<RenderPage> = if (spreadMode) {
        when (relative) { -1 -> prevSpread(); 1 -> nextSpread(); else -> curSpread() }.toList()
    } else {
        listOf(when (relative) { -1 -> prevPage(); 1 -> nextPage(); else -> curPage() })
    }

    fun captureTurn(
        forward: Boolean,
        sourcePages: List<RenderPage> = turnPages(0),
        targetPages: List<RenderPage> = turnPages(if (forward) 1 else -1)
    ): ReaderTurnSnapshot = ReaderTurnSnapshot(
        forward, sourcePages.toList(), targetPages.toList(), navigationGeneration,
        sourceGeneration, environmentVersion, spreadMode
    )

    /** A neighbor-only publication is harmless only when BOTH frozen faces still match exactly. */
    fun canCommitTurn(turn: ReaderTurnSnapshot): Boolean = isReady &&
        turn.navigationGeneration == navigationGeneration && turn.sourceGeneration == sourceGeneration &&
        turn.environmentGeneration == environmentVersion && turn.spread == spreadMode &&
        turn.sourcePages == turnPages(0) && turn.targetPages == turnPages(if (turn.forward) 1 else -1)

    fun commitTurn(turn: ReaderTurnSnapshot): Boolean {
        if (!canCommitTurn(turn)) return false
        return if (turn.forward) moveToNextPage() else moveToPrevPage()
    }

    private val layoutMutex = Mutex()

    val isReady: Boolean get() = curSlot?.chapter != null

    fun setInlineMarkers(markers: Map<Int, List<InlineMarkerReservation>>) {
        if (markers == inlineMarkersByChapter) return
        val changed = (markers.keys + inlineMarkersByChapter.keys).filter {
            markers[it].orEmpty() != inlineMarkersByChapter[it].orEmpty()
        }.toSet()
        inlineMarkersByChapter = markers.mapValues { it.value.toList() }
        changed.forEach { markerVersions[it] = (markerVersions[it] ?: 0) + 1 }
        relayoutVisibleSlots(slots = listOfNotNull(curSlot, nextSlot, prevSlot).filter { it.index in changed })
    }

    fun setChapters(list: List<ChapterMeta>) {
        chapters = list
        cumulativeChars = LongArray(list.size + 1)
        for (i in list.indices) {
            cumulativeChars[i + 1] = cumulativeChars[i] + list[i].charCount
        }
        totalChars = cumulativeChars.lastOrNull() ?: 0
    }

    /**
     * (Re)creates the typesetter for a new viewport/style. Chapter bodies already in the window are
     * re-laid out off the main thread; the reading position is preserved via [charOffset].
     */
    fun updateEnvironment(spec: TypesetSpec, measure: TextMeasure, spread: Boolean = false) {
        spreadMode = spread
        typesetter = ChapterTypesetter(spec, measure)
        environmentVersion++
        layoutGeneration++
        relayoutVisibleSlots(clearLayouts = true, openPositionWhenEmpty = true)
    }

    private fun relayoutVisibleSlots(
        slots: List<Slot> = listOfNotNull(curSlot, nextSlot, prevSlot),
        clearLayouts: Boolean = false,
        openPositionWhenEmpty: Boolean = false
    ) {
        if (typesetter == null) return
        if (slots.isEmpty()) {
            if (openPositionWhenEmpty) rebindWindow()
            return
        }
        val version = environmentVersion
        val relayoutSourceVersion = sourceVersion
        for (slot in slots) {
            relayoutJobs.remove(slot.index)?.cancel()
            if (clearLayouts) slot.chapter = null
            val markerVersion = markerVersions[slot.index]
            // Keep the old immutable chapter visible until this chapter's replacement is ready.
            relayoutJobs[slot.index] = scope.launch {
                try {
                    val laid = typesetChapter(slot.index, slot.content) ?: return@launch
                    if (version != environmentVersion || relayoutSourceVersion != sourceVersion ||
                        markerVersion != markerVersions[slot.index] || slotFor(slot.index) !== slot
                    ) return@launch
                    slot.chapter = laid
                    layoutGeneration++
                    notifySlotChanged(slot.index)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    if (relayoutSourceVersion == sourceVersion) clearPendingScrollOnFailure(slot.index)
                    if (relayoutSourceVersion == sourceVersion && slot.index == chapterIndex) {
                        listener.onContentError(slot.index, error)
                    }
                }
            }
        }
    }

    fun openPosition(chapterIndex: Int, charOffset: Int) {
        bookEndNotified = false
        pendingProgressJump = null
        pendingScroll = null
        this.chapterIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        this.charOffset = charOffset.coerceAtLeast(0)
        rebindWindow()
    }

    fun jumpToChapter(index: Int, offset: Int = 0) {
        if (chapters.isEmpty()) return
        pendingProgressJump = null
        pendingScroll = null
        jumpToChapterInternal(index, offset)
    }

    private fun jumpToChapterInternal(index: Int, offset: Int) {
        pendingScroll = null
        chapterIndex = index.coerceIn(0, chapters.size - 1)
        charOffset = offset.coerceAtLeast(0)
        rebindWindow()
    }

    /** Drops the three-chapter cache after local text was edited, then reads the window again. */
    fun reloadFromSource(
        chapterIndex: Int = this.chapterIndex,
        charOffset: Int = this.charOffset,
        resetBookEnd: Boolean = true
    ) {
        sourceVersion++
        if (resetBookEnd) {
            bookEndNotified = false
            listener.onSourceRevisionChanged()
        }
        pendingProgressJump = null
        pendingScroll = null
        relayoutJobs.values.forEach { it.cancel() }
        relayoutJobs.clear()
        layoutGeneration++
        environmentVersion++
        this.chapterIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        this.charOffset = charOffset.coerceAtLeast(0)
        prevSlot = null
        curSlot = null
        nextSlot = null
        loadingIndices.clear()
        rebindWindow()
    }

    /** Jump by whole-book fraction, resolved through cumulative char counts. */
    fun jumpToProgress(progress: Float) {
        if (chapters.isEmpty() || totalChars <= 0) return
        val target = (progress.coerceIn(0f, 1f) * totalChars).toLong()
        val index = cumulativeChars.indexOfLast { it <= target }
            .coerceIn(0, chapters.size - 1)
        val rawLength = chapters[index].charCount.coerceAtLeast(1)
        val rawWithin = (target - cumulativeChars[index]).coerceIn(0, rawLength.toLong())
        val rawFraction = rawWithin.toDouble() / rawLength
        val shownLength = slotFor(index)?.content?.body?.length
        pendingProgressJump = if (shownLength == null) {
            PendingProgressJump(index, rawFraction, sourceVersion)
        } else {
            null
        }
        jumpToChapterInternal(
            index,
            shownLength?.let { displayedOffset(rawFraction, it) } ?: rawWithin.toInt()
        )
    }

    /** Fraction of the current chapter that lies before the reading position. */
    fun chapterProgress(): Float {
        val length = displayedCharCount(chapterIndex)
        return if (length <= 0) 0f else (charOffset.toFloat() / length).coerceIn(0f, 1f)
    }

    /** Jump within the current chapter by fraction of its characters (章内跳页). */
    fun seekWithinChapter(fraction: Float) {
        val length = displayedCharCount(chapterIndex)
        val within = (fraction.coerceIn(0f, 1f) * length).toInt()
            .coerceIn(0, (length - 1).coerceAtLeast(0))
        jumpToChapter(chapterIndex, within)
    }

    // ---- page factory ----

    private val curChapter: TextChapter? get() = curSlot?.chapter
    private val nextChapter: TextChapter? get() = nextSlot?.chapter
    private val prevChapter: TextChapter? get() = prevSlot?.chapter

    /**
     * Explicit, not derived from [charOffset]: a synthetic title page and the first body page can
     * share the same start offset, so sequential turning must not round-trip through the offset →
     * page lookup (Legado keeps `durPageIndex` for the same reason). Re-derived only when the
     * position jumps or the current chapter is (re)laid out.
     */
    var pageIndex: Int = 0
        private set
    val pageCount: Int get() = curChapter?.pageCount ?: 1

    private fun rederivePageIndex() {
        pageIndex = curChapter?.pageIndexAt(charOffset) ?: 0
    }

    fun curPage(): RenderPage = curChapter?.let { chapter ->
        chapter.page(pageIndex)?.let { laidPage(it, chapter) }
    } ?: placeholder(chapterIndex)

    fun nextPage(): RenderPage {
        val chapter = curChapter
        if (chapter != null && pageIndex < chapter.pageCount - 1) {
            return chapter.page(pageIndex + 1)?.let { laidPage(it, chapter) }
                ?: placeholder(chapterIndex)
        }
        val next = nextChapter
        if (next != null) return laidPage(next.pages.first(), next)
        return placeholder(chapterIndex + 1)
    }

    fun prevPage(): RenderPage {
        val chapter = curChapter
        if (chapter != null && pageIndex > 0) {
            return chapter.page(pageIndex - 1)?.let { laidPage(it, chapter) }
                ?: placeholder(chapterIndex)
        }
        val prev = prevChapter
        if (prev != null && prev.pages.isNotEmpty()) {
            return laidPage(prev.pages.last(), prev)
        }
        return placeholder(chapterIndex - 1)
    }

    fun hasNextPage(): Boolean {
        if (spreadMode) return hasNextSpread()
        val chapter = curChapter ?: run { ensureLoaded(chapterIndex); return false }
        if (!chapter.isLastPage(pageIndex)) return true
        return chapterIndex < chapters.size - 1
    }

    /**
     * Legado's rule: moving back across a chapter boundary requires the previous chapter to be
     * fully laid out, because the landing page is its last one. Gating here (not only in
     * [moveToPrevPage]) keeps the gesture from playing a turn that would fail to commit.
     */
    fun hasPrevPage(): Boolean {
        if (spreadMode) return hasPrevSpread()
        val chapter = curChapter ?: run { ensureLoaded(chapterIndex); return false }
        if (pageIndex > 0) return true
        return chapterIndex > 0 && prevChapter != null
    }

    /** Advances one page; called when a turn animation commits (Legado's `fillPage`). */
    fun moveToNextPage(): Boolean {
        if (spreadMode) return moveToNextSpread()
        val chapter = curChapter ?: return false
        if (!chapter.isLastPage(pageIndex)) {
            pageIndex++
            charOffset = chapter.pageStartOffset(pageIndex)
            listener.onContentChanged(0)
            publishPosition(sequential = true)
            return true
        }
        if (chapterIndex >= chapters.size - 1) {
            if (chapter.isLastPage(pageIndex)) notifyBookEnd()
            return false
        }
        // It is safe to enter a loading next chapter only after the current last page was read.
        // Once entered, the current-chapter gate above prevents skipping that loading chapter.
        shiftWindowForward()
        return true
    }

    fun moveToPrevPage(): Boolean {
        if (spreadMode) return moveToPrevSpread()
        if (curChapter == null) return false
        if (pageIndex > 0) {
            val chapter = curChapter ?: return false
            pageIndex--
            charOffset = chapter.pageStartOffset(pageIndex)
            listener.onContentChanged(0)
            publishPosition(sequential = true)
            return true
        }
        if (chapterIndex <= 0) return false
        if (prevChapter == null) return false
        shiftWindowBackward()
        return true
    }

    fun captureVisibleRead(pages: List<RenderPage>): ReaderVisibleReadSnapshot? {
        if (pages.isEmpty() || pages.any { it is RenderPage.Placeholder }) return null
        val laid = pages.filterIsInstance<RenderPage.Laid>()
        val last = laid.lastOrNull() ?: return null
        if (laid.any { it.chapterIndex != last.chapterIndex }) return null
        val source = chapterSource(last.chapterIndex) ?: return null
        return ReaderVisibleReadSnapshot(layoutGeneration, last.chapterIndex, laid,
            last.page.chapterPosition + last.page.charLength, source)
    }

    /** Unchanged current ink may be rebased after a neighbor-only load, but never after reflow. */
    fun rebaseDrawnSnapshot(snapshot: ReaderVisibleReadSnapshot): ReaderVisibleReadSnapshot? {
        if (!isReady || snapshot.chapterIndex != chapterIndex) return null
        val visible = if (spreadMode) curSpread().toList() else listOf(curPage())
        val last = snapshot.pages.lastOrNull()?.page ?: return null
        if (snapshot.displayEnd != last.chapterPosition + last.charLength ||
            snapshot.pages != visible.filterIsInstance<RenderPage.Laid>() ||
            snapshot.source != chapterSource(chapterIndex)
        ) return null
        return snapshot.copy(layoutGeneration = layoutGeneration)
    }

    fun isCurrentVisibleRead(snapshot: ReaderVisibleReadSnapshot): Boolean =
        snapshot.layoutGeneration == layoutGeneration && rebaseDrawnSnapshot(snapshot) == snapshot

    fun curSpread(): Pair<RenderPage, RenderPage> = spread(curChapter, chapterIndex, spreadFor(pageIndex))

    fun nextSpread(): Pair<RenderPage, RenderPage> {
        val chapter = curChapter
        val left = spreadFor(pageIndex) + 2
        return if (chapter != null && left < chapter.pageCount) spread(chapter, chapterIndex, left)
        else spread(nextChapter, chapterIndex + 1, 0)
    }

    fun prevSpread(): Pair<RenderPage, RenderPage> {
        val left = spreadFor(pageIndex) - 2
        return if (curChapter != null && left >= 0) spread(curChapter, chapterIndex, left)
        else spread(prevChapter, chapterIndex - 1, spreadFor(prevChapter?.pages?.lastIndex ?: 0))
    }

    private fun spread(chapter: TextChapter?, index: Int, left: Int): Pair<RenderPage, RenderPage> {
        if (chapter == null) return placeholder(index) to placeholder(index)
        val first = chapter.page(left)?.let { laidPage(it, chapter) } ?: return placeholder(index) to placeholder(index)
        return first to rightPageOrBlank(chapter, left)
    }

    fun hasNextSpread(): Boolean {
        val chapter = curChapter ?: run { ensureLoaded(chapterIndex); return false }
        if (spreadFor(pageIndex) + 2 < chapter.pageCount) return true
        if (chapterIndex >= chapters.lastIndex) return false
        if (nextChapter == null) ensureLoaded(chapterIndex + 1)
        return nextChapter != null
    }

    fun hasPrevSpread(): Boolean {
        if (curChapter == null) { ensureLoaded(chapterIndex); return false }
        if (spreadFor(pageIndex) > 0) return true
        if (chapterIndex <= 0) return false
        if (prevChapter == null) ensureLoaded(chapterIndex - 1)
        return prevChapter != null
    }

    /** One target calculation and one position publication, never two single-page turns. */
    fun moveToNextSpread(): Boolean {
        val chapter = curChapter ?: return false
        val target = spreadFor(pageIndex) + 2
        if (target < chapter.pageCount) return moveWithinChapter(chapter, target)
        if (chapterIndex == chapters.lastIndex) {
            notifyBookEnd()
            return false
        }
        if (nextChapter == null) return false
        shiftWindowForward()
        return true
    }

    fun moveToPrevSpread(): Boolean {
        val chapter = curChapter ?: return false
        val target = spreadFor(pageIndex) - 2
        if (target >= 0) return moveWithinChapter(chapter, target)
        if (chapterIndex <= 0 || prevChapter == null) return false
        shiftWindowBackward(spread = true)
        return true
    }

    private fun moveWithinChapter(chapter: TextChapter, target: Int): Boolean {
        pageIndex = target
        charOffset = chapter.pageStartOffset(target)
        listener.onContentChanged(0)
        publishPosition(sequential = true)
        return true
    }

    // ---- window management ----

    private fun shiftWindowForward() {
        pendingProgressJump = null
        chapterIndex++
        charOffset = 0
        pageIndex = 0
        prevSlot = curSlot
        curSlot = nextSlot
        nextSlot = null
        trimRelayoutJobs()
        if (curSlot == null) ensureLoaded(chapterIndex)
        ensureLoaded(chapterIndex + 1)
        listener.onContentChanged(0)
        publishPosition(sequential = true)
    }

    private fun shiftWindowBackward(spread: Boolean = spreadMode) {
        pendingProgressJump = null
        val landing = prevChapter
        chapterIndex--
        pageIndex = landing?.pages?.lastIndex?.coerceAtLeast(0) ?: 0
        if (spread) pageIndex = spreadFor(pageIndex)
        charOffset = landing?.pageStartOffset(pageIndex) ?: 0
        nextSlot = curSlot
        curSlot = prevSlot
        prevSlot = null
        trimRelayoutJobs()
        ensureLoaded(chapterIndex - 1)
        listener.onContentChanged(0)
        publishPosition(sequential = true)
    }

    private fun rebindWindow() {
        navigationGeneration++
        layoutGeneration++ // A jump can reuse layouts but must invalidate an in-flight turn.
        val existing = listOfNotNull(prevSlot, curSlot, nextSlot)
        prevSlot = existing.firstOrNull { it.index == chapterIndex - 1 }
        curSlot = existing.firstOrNull { it.index == chapterIndex }
        nextSlot = existing.firstOrNull { it.index == chapterIndex + 1 }
        trimRelayoutJobs()
        rederivePageIndex()
        ensureLoaded(chapterIndex)
        ensureLoaded(chapterIndex + 1)
        ensureLoaded(chapterIndex - 1)
        listener.onContentChanged(0)
        publishPosition()
    }

    private fun trimRelayoutJobs() {
        relayoutJobs.keys.filter { it !in (chapterIndex - 1)..(chapterIndex + 1) }.forEach {
            relayoutJobs.remove(it)?.cancel()
        }
    }

    private fun ensureLoaded(index: Int) {
        if (index < 0 || index >= chapters.size) return
        val existing = slotFor(index)
        if (existing?.chapter != null || (existing != null && relayoutJobs[index]?.isActive == true) ||
            !loadingIndices.add(index)
        ) return
        val requestedSourceVersion = sourceVersion
        scope.launch {
            try {
                val content = existing?.content
                    ?: chapterLoader(index)
                    ?: throw IllegalStateException("第 ${index + 1} 章内容暂不可用，请重试")
                if (requestedSourceVersion != sourceVersion) return@launch
                // Re-typeset if the environment moved underneath us (viewport/typography change
                // while the chapter was loading), so a stale spec never reaches a slot.
                var laid: TextChapter?
                do {
                    currentCoroutineContext().ensureActive()
                    val version = environmentVersion
                    val markerVersion = markerVersions[index]
                    laid = typesetChapter(index, content)
                } while ((version != environmentVersion || markerVersion != markerVersions[index]) &&
                    requestedSourceVersion == sourceVersion)
                if (requestedSourceVersion != sourceVersion) return@launch
                if (index !in (chapterIndex - 1)..(chapterIndex + 1)) return@launch
                val slot = Slot(index, content, laid)
                when (index) {
                    chapterIndex - 1 -> prevSlot = slot
                    chapterIndex -> curSlot = slot
                    chapterIndex + 1 -> nextSlot = slot
                }
                if (index == chapterIndex) applyPendingProgress(slot)
                layoutGeneration++
                notifySlotChanged(index)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (requestedSourceVersion == sourceVersion) clearPendingScrollOnFailure(index)
                if (requestedSourceVersion == sourceVersion && index == chapterIndex) {
                    listener.onContentError(index, error)
                }
            } finally {
                if (requestedSourceVersion == sourceVersion) loadingIndices.remove(index)
            }
        }
    }

    private suspend fun typesetChapter(
        index: Int,
        content: ReaderChapterContent
    ): TextChapter? {
        val typesetter = typesetter ?: return null
        val title = chapters.getOrNull(index)?.title.orEmpty()
        return layoutMutex.withLock {
            withContext(Dispatchers.Default) {
                val context = currentCoroutineContext()
                val cancellationCheck = { context.ensureActive() }
                try {
                    typesetter.typeset(
                        chapterIndex = index,
                        title = title,
                        body = content.body,
                        inlineImages = content.inlineImages,
                        inlineMarkers = inlineMarkersByChapter[index].orEmpty(),
                        epubLayout = content.epubLayout,
                        cancellationCheck = cancellationCheck
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (nativeError: Throwable) {
                    if (content.epubLayout == null) throw nativeError
                    typesetter.typeset(
                        chapterIndex = index,
                        title = title,
                        body = content.body,
                        inlineImages = content.inlineImages,
                        inlineMarkers = inlineMarkersByChapter[index].orEmpty(),
                        epubLayout = null,
                        cancellationCheck = cancellationCheck
                    )
                }
            }
        }
    }

    private fun clearPendingScrollOnFailure(index: Int) {
        if (index == chapterIndex || pendingScroll?.first == index) pendingScroll = null
    }

    private fun notifySlotChanged(index: Int) {
        pendingScroll?.takeIf { isReady && slotFor(it.first)?.chapter != null }?.let {
            pendingScroll = null
            scrollTo(it.first, it.second)
            listener.onContentChanged(0)
            return
        }
        when (index) {
            chapterIndex -> {
                rederivePageIndex()
                listener.onContentChanged(0)
                publishPosition()
            }
            chapterIndex - 1 -> listener.onContentChanged(-1)
            chapterIndex + 1 -> listener.onContentChanged(1)
        }
    }

    private fun slotFor(index: Int): Slot? = when (index) {
        prevSlot?.index -> prevSlot
        curSlot?.index -> curSlot
        nextSlot?.index -> nextSlot
        else -> null
    }

    private fun publishPosition(sequential: Boolean = false) {
        positionChangeIsSequential = sequential
        if (chapterIndex == chapters.lastIndex && isLastPageReady()) {
            notifyBookEnd()
        }
        listener.onPositionChanged(
            chapterIndex = chapterIndex,
            charOffset = charOffset,
            pageIndex = pageIndex,
            pageCount = pageCount,
            bookProgress = bookProgress()
        )
    }

    // ---- 滚动模式（PageMode.SCROLL）----

    val chapterCount: Int get() = chapters.size

    /** 滑窗内已排版的章：-1/0/+1 相对当前章，滚动条带按此拼接。 */
    fun laidChapter(relative: Int): TextChapter? = when (relative) {
        -1 -> prevChapter
        0 -> curChapter
        1 -> nextChapter
        else -> null
    }

    /** Gesture/viewport synchronization cannot intentionally skip outside the three-chapter window. */
    fun scrollWithinWindow(chapter: Int, offset: Int) {
        if (chapter !in (chapterIndex - 1)..(chapterIndex + 1)) return
        scrollTo(chapter, offset)
    }

    /**
     * 滚动模式的位置推进：只更新偏移并平移滑窗，不做页语义，也不回调 onContentChanged ——
     * 滚动面自己持帧，回调只会造成多余重绘。跨到相邻章时窗口跟着滑（预排下一邻章），
     * 更远的调用是显式导航，整窗重开（如跳章），并非消耗占位符的连续滚动。
     * 手势/视口同步必须使用 scrollWithinWindow，不能利用此入口跨过未加载章节。
     */
    fun scrollTo(chapter: Int, offset: Int) {
        if (chapters.isEmpty()) return
        pendingProgressJump = null
        val target = chapter.coerceIn(0, chapters.size - 1)
        val clamped = offset.coerceAtLeast(0)
        if (target !in (chapterIndex - 1)..(chapterIndex + 1)) {
            // Explicit far navigation owns its requested anchor, even while its content loads.
            // It supersedes pending adjacent scrolls and is never a sequential completion signal.
            jumpToChapterInternal(target, clamped)
            return
        }
        if (curChapter == null || slotFor(target)?.chapter == null) {
            pendingScroll = target to clamped
            ensureLoaded(target)
            return
        }
        pendingScroll = null
        when (target) {
            chapterIndex -> {
                if (charOffset == clamped) return
                charOffset = clamped
                rederivePageIndex()
                publishPosition()
            }
            chapterIndex + 1 -> {
                chapterIndex = target
                charOffset = clamped
                prevSlot = curSlot
                curSlot = nextSlot
                nextSlot = null
                rederivePageIndex()
                if (curSlot == null) ensureLoaded(chapterIndex)
                ensureLoaded(chapterIndex + 1)
                publishPosition(sequential = true)
            }
            chapterIndex - 1 -> {
                chapterIndex = target
                charOffset = clamped
                nextSlot = curSlot
                curSlot = prevSlot
                prevSlot = null
                rederivePageIndex()
                if (curSlot == null) ensureLoaded(chapterIndex)
                ensureLoaded(chapterIndex - 1)
                publishPosition(sequential = true)
            }
            else -> {
                chapterIndex = target
                charOffset = clamped
                rebindWindow()
            }
        }
    }

    /**
     * A body slice around the given range of the current chapter, for AI context assembly.
     * Cuts at the nearest paragraph break within reach so the slice starts and ends cleanly.
     */
    fun contextAround(range: IntRange, radius: Int = 240): String {
        val body = curSlot?.content?.body ?: return ""
        if (body.isEmpty()) return ""
        var from = (range.first - radius).coerceIn(0, body.length)
        var to = (range.last + 1 + radius).coerceIn(from, body.length)
        body.lastIndexOf('\n', startIndex = range.first.coerceIn(0, body.length - 1))
            .takeIf { it in from until range.first }
            ?.let { from = it + 1 }
        body.indexOf('\n', startIndex = (range.last + 1).coerceIn(0, body.length))
            .takeIf { it in (range.last + 1) until to }
            ?.let { to = it }
        return body.substring(from, to)
            .replace(INLINE_IMAGE_CHAR.toString(), "［图片］")
            .trim()
    }

    /** Body text for a chapter currently held by the three-chapter window. */
    fun chapterBody(index: Int): String? = slotFor(index)?.content?.body

    fun chapterSource(index: Int): ReaderChapterSource? = slotFor(index)?.content?.let { content ->
        content.source ?: ReaderChapterSource(content.body)
    }

    fun chapterLayout(index: Int): EpubLayoutChapterBundle? = slotFor(index)?.content?.epubLayout

    fun bookProgress(): Float = progressAt(chapterIndex, charOffset)

    fun isLastPageReady(): Boolean = curChapter?.let {
        if (spreadMode) spreadFor(pageIndex) + 1 >= it.pages.lastIndex else it.isLastPage(pageIndex)
    } == true

    private fun notifyBookEnd() {
        if (bookEndNotified) return
        bookEndNotified = true
        listener.onReachedBookEnd()
    }

    /** Move only the durable reading anchor (e.g. a TTS sentence on the right leaf). */
    fun focus(offset: Int) {
        if (!isReady || !isDisplaying(chapterIndex, offset)) return
        val next = offset.coerceIn(0, (displayedCharCount(chapterIndex) - 1).coerceAtLeast(0))
        if (charOffset == next) return
        charOffset = next
        publishPosition()
    }

    /** 听书自动翻页用：该章内偏移是否正落在当前显示页上；本章尚未排版时按“已显示”处理。 */
    fun isDisplaying(chapter: Int, offset: Int): Boolean {
        if (chapter != chapterIndex) return false
        val laid = curChapter ?: return true
        val target = laid.pageIndexAt(offset)
        return if (spreadMode) target in spreadFor(pageIndex)..(spreadFor(pageIndex) + 1)
        else target == pageIndex
    }

    fun progressAt(chapterIndex: Int, charOffset: Int): Float {
        if (totalChars <= 0 || chapterIndex !in chapters.indices) return 0f
        val shownLength = displayedCharCount(chapterIndex).coerceAtLeast(1)
        val rawWithin = charOffset.toDouble() / shownLength * chapters[chapterIndex].charCount
        return ((cumulativeChars[chapterIndex] + rawWithin) / totalChars)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    private fun displayedCharCount(index: Int): Int =
        slotFor(index)?.content?.body?.length ?: chapters.getOrNull(index)?.charCount ?: 0

    private fun applyPendingProgress(slot: Slot) {
        val pending = pendingProgressJump ?: return
        if (pending.chapterIndex != slot.index || pending.sourceVersion != sourceVersion) return
        charOffset = displayedOffset(pending.rawFraction, slot.content.body.length)
        pendingProgressJump = null
    }

    private fun displayedOffset(fraction: Double, length: Int): Int =
        (fraction * length).toInt().coerceIn(0, (length - 1).coerceAtLeast(0))

    private fun laidPage(page: TextPage, chapter: TextChapter): RenderPage.Laid = RenderPage.Laid(
        chapterIndex = chapter.chapterIndex,
        chapterTitle = chapter.title,
        pageIndex = page.index,
        pageCount = chapter.pageCount,
        page = page
    )

    private fun placeholder(index: Int): RenderPage.Placeholder = RenderPage.Placeholder(
        chapterIndex = index.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)),
        chapterTitle = chapters.getOrNull(index)?.title.orEmpty()
    )

    private companion object {
        const val INLINE_IMAGE_CHAR = '\uFFFC'
    }
}
