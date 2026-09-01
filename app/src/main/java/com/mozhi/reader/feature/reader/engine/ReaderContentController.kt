package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.library.EpubLayoutChapterBundle
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
    val inlineImages: List<InlineImageSource> = emptyList()
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

        /** A failed current chapter must surface as an error instead of an endless spinner. */
        fun onContentError(chapterIndex: Int, error: Throwable) = Unit
    }

    private class Slot(
        val index: Int,
        val content: ReaderChapterContent,
        var chapter: TextChapter?
    )

    private var chapters: List<ChapterMeta> = emptyList()
    private var inlineMarkersByChapter: Map<Int, List<InlineMarkerReservation>> = emptyMap()
    private var cumulativeChars: LongArray = LongArray(0)
    private var totalChars: Long = 0

    private var typesetter: ChapterTypesetter? = null
    private var environmentVersion = 0
    private var sourceVersion = 0

    var chapterIndex: Int = 0
        private set
    var charOffset: Int = 0
        private set

    private var prevSlot: Slot? = null
    private var curSlot: Slot? = null
    private var nextSlot: Slot? = null
    private val loadingIndices = LinkedHashSet<Int>()
    private var relayoutJob: Job? = null
    private val layoutMutex = Mutex()

    val isReady: Boolean get() = curSlot?.chapter != null

    fun setInlineMarkers(markers: Map<Int, List<InlineMarkerReservation>>) {
        if (markers == inlineMarkersByChapter) return
        inlineMarkersByChapter = markers
        relayoutVisibleSlots()
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
    fun updateEnvironment(spec: TypesetSpec, measure: TextMeasure) {
        typesetter = ChapterTypesetter(spec, measure)
        relayoutVisibleSlots(openPositionWhenEmpty = true)
    }

    private fun relayoutVisibleSlots(openPositionWhenEmpty: Boolean = false) {
        if (typesetter == null) return
        environmentVersion++
        val version = environmentVersion
        val relayoutSourceVersion = sourceVersion
        val slots = listOfNotNull(curSlot, nextSlot, prevSlot)
        slots.forEach { it.chapter = null }
        if (slots.isEmpty()) {
            if (openPositionWhenEmpty) openPosition(chapterIndex, charOffset)
            return
        }
        relayoutJob?.cancel()
        // Registering in loadingIndices keeps a concurrent jump's ensureLoaded from re-reading
        // and re-typesetting the same chapter into a competing slot.
        val registered = slots.filter { loadingIndices.add(it.index) }
        relayoutJob = scope.launch {
            try {
                for (slot in slots) {
                    currentCoroutineContext().ensureActive()
                    val laid = try {
                        typesetChapter(slot.index, slot.content)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        if (relayoutSourceVersion == sourceVersion && slot.index == chapterIndex) {
                            listener.onContentError(slot.index, error)
                        }
                        continue
                    } ?: continue
                    if (version != environmentVersion || relayoutSourceVersion != sourceVersion) {
                        return@launch
                    }
                    slot.chapter = laid
                    notifySlotChanged(slot.index)
                }
            } finally {
                if (relayoutSourceVersion == sourceVersion) {
                    registered.forEach { loadingIndices.remove(it.index) }
                }
            }
        }
    }

    fun openPosition(chapterIndex: Int, charOffset: Int) {
        this.chapterIndex = chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))
        this.charOffset = charOffset.coerceAtLeast(0)
        rebindWindow()
    }

    fun jumpToChapter(index: Int, offset: Int = 0) {
        if (chapters.isEmpty()) return
        chapterIndex = index.coerceIn(0, chapters.size - 1)
        charOffset = offset.coerceAtLeast(0)
        rebindWindow()
    }

    /** Drops the three-chapter cache after local text was edited, then reads the window again. */
    fun reloadFromSource(
        chapterIndex: Int = this.chapterIndex,
        charOffset: Int = this.charOffset
    ) {
        sourceVersion++
        relayoutJob?.cancel()
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
        val shownLength = displayedCharCount(index)
        val shownWithin = (rawWithin.toDouble() / rawLength * shownLength).toInt()
            .coerceIn(0, (shownLength - 1).coerceAtLeast(0))
        jumpToChapter(index, shownWithin)
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
        val chapter = curChapter
        if (chapter != null && !chapter.isLastPage(pageIndex)) return true
        return chapterIndex < chapters.size - 1
    }

    /**
     * Legado's rule: moving back across a chapter boundary requires the previous chapter to be
     * fully laid out, because the landing page is its last one. Gating here (not only in
     * [moveToPrevPage]) keeps the gesture from playing a turn that would fail to commit.
     */
    fun hasPrevPage(): Boolean {
        val chapter = curChapter
        if (chapter != null && pageIndex > 0) return true
        return chapterIndex > 0 && prevChapter != null
    }

    /** Advances one page; called when a turn animation commits (Legado's `fillPage`). */
    fun moveToNextPage(): Boolean {
        val chapter = curChapter
        if (chapter != null && !chapter.isLastPage(pageIndex)) {
            pageIndex++
            charOffset = chapter.pageStartOffset(pageIndex)
            listener.onContentChanged(0)
            publishPosition()
            return true
        }
        if (chapterIndex >= chapters.size - 1) return false
        // Legado's gate: while nothing around the position is laid out, refuse to advance —
        // otherwise taps on the placeholder would silently skip whole chapters and overwrite the
        // stored offset.
        if (chapter == null && nextChapter == null) return false
        shiftWindowForward()
        return true
    }

    fun moveToPrevPage(): Boolean {
        if (pageIndex > 0) {
            val chapter = curChapter ?: return false
            pageIndex--
            charOffset = chapter.pageStartOffset(pageIndex)
            listener.onContentChanged(0)
            publishPosition()
            return true
        }
        if (chapterIndex <= 0) return false
        if (prevChapter == null) return false
        shiftWindowBackward()
        return true
    }

    // ---- window management ----

    private fun shiftWindowForward() {
        chapterIndex++
        charOffset = 0
        pageIndex = 0
        prevSlot = curSlot
        curSlot = nextSlot
        nextSlot = null
        if (curSlot == null) ensureLoaded(chapterIndex)
        ensureLoaded(chapterIndex + 1)
        listener.onContentChanged(0)
        publishPosition()
    }

    private fun shiftWindowBackward() {
        val landing = prevChapter
        chapterIndex--
        charOffset = landing?.lastPage?.chapterPosition ?: 0
        pageIndex = landing?.pages?.lastIndex?.coerceAtLeast(0) ?: 0
        nextSlot = curSlot
        curSlot = prevSlot
        prevSlot = null
        ensureLoaded(chapterIndex - 1)
        listener.onContentChanged(0)
        publishPosition()
    }

    private fun rebindWindow() {
        val existing = listOfNotNull(prevSlot, curSlot, nextSlot)
        prevSlot = existing.firstOrNull { it.index == chapterIndex - 1 }
        curSlot = existing.firstOrNull { it.index == chapterIndex }
        nextSlot = existing.firstOrNull { it.index == chapterIndex + 1 }
        rederivePageIndex()
        ensureLoaded(chapterIndex)
        ensureLoaded(chapterIndex + 1)
        ensureLoaded(chapterIndex - 1)
        listener.onContentChanged(0)
        publishPosition()
    }

    private fun ensureLoaded(index: Int) {
        if (index < 0 || index >= chapters.size) return
        val existing = slotFor(index)
        if (existing?.chapter != null || !loadingIndices.add(index)) return
        val requestedSourceVersion = sourceVersion
        scope.launch {
            try {
                val content = existing?.content
                    ?: chapterLoader(index)
                    ?: ReaderChapterContent("")
                if (requestedSourceVersion != sourceVersion) return@launch
                // Re-typeset if the environment moved underneath us (viewport/typography change
                // while the chapter was loading), so a stale spec never reaches a slot.
                var laid: TextChapter?
                do {
                    currentCoroutineContext().ensureActive()
                    val version = environmentVersion
                    laid = typesetChapter(index, content)
                } while (version != environmentVersion && requestedSourceVersion == sourceVersion)
                if (requestedSourceVersion != sourceVersion) return@launch
                if (index !in (chapterIndex - 1)..(chapterIndex + 1)) return@launch
                val slot = Slot(index, content, laid)
                when (index) {
                    chapterIndex - 1 -> prevSlot = slot
                    chapterIndex -> curSlot = slot
                    chapterIndex + 1 -> nextSlot = slot
                }
                notifySlotChanged(index)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
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

    private fun notifySlotChanged(index: Int) {
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

    private fun publishPosition() {
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

    /**
     * 滚动模式的位置推进：只更新偏移并平移滑窗，不做页语义，也不回调 onContentChanged ——
     * 滚动面自己持帧，回调只会造成多余重绘。跨到相邻章时窗口跟着滑（预排下一邻章），
     * 更远的跳转整窗重开。
     */
    fun scrollTo(chapter: Int, offset: Int) {
        if (chapters.isEmpty()) return
        val target = chapter.coerceIn(0, chapters.size - 1)
        val clamped = offset.coerceAtLeast(0)
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
                publishPosition()
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
                publishPosition()
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

    fun chapterLayout(index: Int): EpubLayoutChapterBundle? = slotFor(index)?.content?.epubLayout

    fun bookProgress(): Float = progressAt(chapterIndex, charOffset)

    /** 听书自动翻页用：该章内偏移是否正落在当前显示页上；本章尚未排版时按“已显示”处理。 */
    fun isDisplaying(chapter: Int, offset: Int): Boolean {
        if (chapter != chapterIndex) return false
        val laid = curChapter ?: return true
        return laid.pageIndexAt(offset) == pageIndex
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
