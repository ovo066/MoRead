package com.mozhi.reader.feature.reader

import android.content.Intent
import android.os.BatteryManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.mozhi.reader.feature.reader.engine.ChapterStrip
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.ReaderPageLink
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.TextPage
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.inlineMarkerLayout
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.feature.reader.engine.imageAt
import com.mozhi.reader.feature.reader.engine.linkAt
import com.mozhi.reader.feature.reader.engine.frameToPhysical
import com.mozhi.reader.feature.reader.engine.isVertical
import com.mozhi.reader.feature.reader.engine.lineExtent
import com.mozhi.reader.feature.reader.engine.physicalToFrame
import com.mozhi.reader.feature.reader.engine.ReaderVisibleReadSnapshot
import com.mozhi.reader.feature.reader.engine.visibleReadEnd
import com.mozhi.reader.feature.reader.engine.charOffsetOf
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job

/** 视口内一个章节块：条带原点在内容带坐标系（0 = 页眉下沿）里的位置。 */
private data class ChapterBlock(
    val chapterIndex: Int,
    val originY: Float,
    val strip: ChapterStrip?
)

/** 命中解析结果：页与页内局部坐标（内容局部系，可直接喂选区/批注几何）。 */
internal data class ResolvedScrollPoint(
    val chapterIndex: Int,
    val pageIndex: Int,
    val page: TextPage,
    val local: Offset
)

internal data class VisibleScrollPage(
    val pageIndex: Int,
    val page: TextPage,
    val origin: Offset
)

/**
 * 滚动面的全部非 Compose 状态：条带缓存、锚点、滚动推进与跨章滑窗、命中解析、绘制。
 * 锚点模型 = (controller.chapterIndex, anchorY)：anchorY 是视口内容带顶边在当前章条带
 * 里的 Y。跨章由 applyScroll 归一化，controller 滑窗跟着走。
 */
internal class ScrollPaneHolder(private val controller: ReaderContentController) {
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingDrawnSnapshot: ReaderVisibleReadSnapshot? = null
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set

    private var style: ReaderPageStyle? = null
    private var renderer: PageBitmapRenderer? = null
    private var appliedStyleKey: ReaderRenderStyleKey? = null
    private var appliedEnvironmentGeneration: Int? = null
    private var backgroundReadyCallback: (() -> Unit)? = null
    private val strips = HashMap<Int, ChapterStrip>()
    private var timeText: String = timeFormat.format(Date())
    private var batteryPercent: Int = 100

    var annotations: List<ReaderAnnotationMark> = emptyList()
    var illustrations: List<ReaderIllustrationMark> = emptyList()
    var transientHighlight: TransientHighlightSpan? = null

    /** 内容带顶边在当前章条带里的 Y；draw phase 逐帧读。 */
    var anchorY by mutableFloatStateOf(0f)

    var dragging = false
    var flinging = false
    var autoScrolling = false
    var scrollJob: Job? = null

    private var lastSyncedChapter = -1
    private var lastSyncedOffset = -1

    val viewportHeight: Float get() = style?.contentHeight ?: 1f
    private val contentTop: Float get() = style?.contentTop ?: 0f
    private val contentBottom: Float get() = style?.contentBottom ?: viewHeight.toFloat()
    private val placeholderHeight: Float get() = style?.contentHeight ?: 1f

    fun setViewport(width: Int, height: Int): Boolean {
        if (width == viewWidth && height == viewHeight) return false
        viewWidth = width
        viewHeight = height
        return true
    }

    /** Navigation detaches the UI, not the reader entry's fonts and chapter strips. */
    fun applyStyle(
        key: ReaderRenderStyleKey,
        createStyle: () -> ReaderPageStyle,
        onBackgroundReady: () -> Unit
    ): Boolean {
        backgroundReadyCallback = onBackgroundReady
        updateTime()
        val relayout = appliedStyleKey?.environment != key.environment ||
            appliedEnvironmentGeneration != controller.environmentGeneration
        if (appliedStyleKey != key || renderer == null) {
            val nextStyle = createStyle()
            appliedStyleKey = key
            style = nextStyle
            renderer?.release()
            renderer = PageBitmapRenderer(nextStyle).also { next ->
                next.prepareBackground { backgroundReadyCallback?.invoke() }
            }
        }
        if (relayout) {
            strips.clear()
            requirePositionReanchor()
            val currentStyle = checkNotNull(style)
            controller.updateEnvironment(currentStyle.spec, currentStyle.measure)
            appliedEnvironmentGeneration = controller.environmentGeneration
        }
        return relayout
    }

    fun drawBackground(canvas: android.graphics.Canvas, width: Float, height: Float) {
        renderer?.drawBackdrop(canvas, width, height)
            ?: canvas.drawColor(android.graphics.Color.TRANSPARENT)
    }

    fun interruptScroll() {
        scrollJob?.cancel()
        scrollJob = null
        flinging = false
    }

    fun detach() {
        pendingDrawnSnapshot = null
        if (dragging || flinging || autoScrolling) syncPosition()
        interruptScroll()
        dragging = false
        autoScrolling = false
        backgroundReadyCallback = null
    }

    fun release() {
        detach()
        renderer?.release()
        renderer = null
        style = null
        appliedStyleKey = null
        appliedEnvironmentGeneration = null
        strips.clear()
        requirePositionReanchor()
    }

    // ---- 条带缓存 ----

    private fun stripFor(chapterIndex: Int): ChapterStrip? {
        val style = style ?: return null
        val relative = chapterIndex - controller.chapterIndex
        val chapter = controller.laidChapter(relative) ?: return null
        strips[chapterIndex]?.takeIf { it.chapter === chapter }?.let { return it }
        val strip = ChapterStrip(chapter, style.spec)
        strips[chapterIndex] = strip
        val center = controller.chapterIndex
        strips.keys.retainAll { abs(it - center) <= 1 }
        return strip
    }

    private fun stripHeightOf(chapterIndex: Int): Float =
        stripFor(chapterIndex)?.totalHeight ?: placeholderHeight

    // ---- 滚动推进 ----

    /**
     * 推进锚点并做跨章归一化；撞到书首/尾（或未排版的上一章）返回边界方向，否则 null。
     * delta 为条带方向位移：正值 = 向后阅读。
     */
    fun applyScroll(delta: Float): PageTurnDirection? {
        val style = style ?: return null
        val viewportH = style.contentHeight
        if (controller.laidChapter(0) == null) return null
        var y = anchorY + delta
        var guard = 0
        while (guard++ < 8) {
            val chapter = controller.chapterIndex
            if (y < 0f) {
                if (chapter <= 0 || controller.laidChapter(-1) == null) {
                    anchorY = 0f
                    return PageTurnDirection.PREVIOUS
                }
                val prevHeight = stripHeightOf(chapter - 1)
                switchChapter(chapter - 1, forward = false)
                y += prevHeight
                continue
            }
            val height = stripHeightOf(chapter)
            if (chapter >= controller.chapterCount - 1) {
                val limit = max(0f, height - viewportH)
                if (y > limit) {
                    anchorY = limit
                    return PageTurnDirection.NEXT
                }
                anchorY = y
                return null
            }
            if (y >= height) {
                if (controller.laidChapter(1) == null) {
                    // Stop on the placeholder boundary without spending the unread chapter.
                    anchorY = height
                    controller.scrollWithinWindow(chapter + 1, 0)
                    return null
                }
                switchChapter(chapter + 1, forward = true)
                y -= height
                continue
            }
            anchorY = y
            return null
        }
        anchorY = y.coerceIn(0f, max(0f, stripHeightOf(controller.chapterIndex)))
        return null
    }

    private fun switchChapter(target: Int, forward: Boolean) {
        val guessOffset = if (forward) {
            0
        } else {
            controller.laidChapter(-1)?.lastPage?.chapterPosition ?: 0
        }
        lastSyncedChapter = target
        lastSyncedOffset = guessOffset
        controller.scrollWithinWindow(target, guessOffset)
    }

    /** 把视口顶部字符写回 controller（进度持久化与伴读/听书上下文都吃它）。 */
    fun syncPosition() {
        val strip = stripFor(controller.chapterIndex) ?: return
        if (anchorY >= strip.totalHeight && controller.laidChapter(1) == null) return
        val offset = strip.charOffsetAt(anchorY)
        lastSyncedChapter = controller.chapterIndex
        lastSyncedOffset = offset
        controller.scrollWithinWindow(controller.chapterIndex, offset)
    }

    /**
     * contentHook(0)：当前章重排/加载完成，或外部跳转（目录/书签/搜索/换排版）。
     *
     * 连续滚动会先把视口顶部字符同步给 controller，随后相邻章节异步排版也会发同一个
     * 回调。此时 charOffset 只能定位到文字行，丢失行内像素偏移；若再次重锚，页面就会
     * 向上弹。仅当 controller 的位置不是本面最近一次同步的位置时，才视为外部跳转。
     */
    fun onContentRefreshed() {
        val positionWasSyncedHere = controller.chapterIndex == lastSyncedChapter &&
            controller.charOffset == lastSyncedOffset
        if (dragging || flinging || autoScrolling || positionWasSyncedHere) {
            clampAnchor()
        } else {
            reanchorToPosition()
        }
    }

    /** 排版环境变化会改变字符到像素的映射，下一次刷新必须重新按阅读位置落点。 */
    fun requirePositionReanchor() {
        lastSyncedChapter = -1
        lastSyncedOffset = -1
    }

    private fun reanchorToPosition() {
        interruptScroll()
        val strip = stripFor(controller.chapterIndex) ?: return
        anchorY = strip.stripYOf(controller.charOffset).coerceIn(0f, maxAnchorFor(strip))
        lastSyncedChapter = controller.chapterIndex
        lastSyncedOffset = controller.charOffset
    }

    private fun clampAnchor() {
        val strip = stripFor(controller.chapterIndex) ?: return
        val limit = if (controller.chapterIndex >= controller.chapterCount - 1) {
            strip.maxAnchor(viewportHeight)
        } else {
            max(0f, strip.totalHeight - 1f)
        }
        if (anchorY > limit) anchorY = limit
    }

    private fun maxAnchorFor(strip: ChapterStrip): Float =
        if (controller.chapterIndex >= controller.chapterCount - 1) {
            strip.maxAnchor(viewportHeight)
        } else {
            max(0f, strip.totalHeight - 1f)
        }

    // ---- 听书跟读 ----

    /** 需要追读时返回滚动量；句子已舒适可见或用户在远处浏览时返回 null。 */
    fun listenFollowDelta(highlight: TransientHighlightSpan): Float? {
        val relative = highlight.chapterIndex - controller.chapterIndex
        if (relative !in -1..1) return null
        val strip = stripFor(highlight.chapterIndex) ?: return null
        val blockOrigin = when (relative) {
            0 -> -anchorY
            1 -> stripHeightOf(controller.chapterIndex) - anchorY
            else -> -(strip.totalHeight + anchorY)
        }
        val viewY = blockOrigin + strip.stripYOf(highlight.startCharOffset)
        val viewportH = viewportHeight
        if (viewY in viewportH * 0.06f..viewportH * 0.82f) return null
        if (abs(viewY) > viewportH * 2.2f) return null
        return viewY - viewportH * FOLLOW_TARGET_FRACTION
    }

    // ---- 命中与几何 ----

    private fun visibleBlocks(): List<ChapterBlock> {
        val blocks = ArrayList<ChapterBlock>(3)
        var chapter = controller.chapterIndex
        var origin = -anchorY
        val windowBottom = contentBottom - contentTop
        var guard = 0
        while (origin < windowBottom && chapter < controller.chapterCount && guard++ < 4) {
            val strip = stripFor(chapter)
            blocks.add(ChapterBlock(chapter, origin, strip))
            origin += strip?.totalHeight ?: placeholderHeight
            chapter++
        }
        return blocks
    }

    /** 视图坐标 → (章, 页, 页内内容局部坐标)；落在占位块/缝隙上时返回 null。 */
    fun resolve(position: Offset): ResolvedScrollPoint? {
        val style = style ?: return null
        val bandY = position.y - contentTop
        if (bandY < 0f || position.y > contentBottom) return null
        for (block in visibleBlocks()) {
            val strip = block.strip ?: continue
            val stripY = bandY - block.originY
            if (stripY < 0f || stripY >= strip.totalHeight) continue
            val pageIndex = strip.pageIndexAt(stripY)
            val page = strip.chapter.pages.getOrNull(pageIndex) ?: continue
            // 竖排页在条带里仍是一屏高的一块，页内坐标转进它的旋转坐标系再查询。
            val (localX, localY) = page.physicalToFrame(position.x - style.paddingLeft, stripY - strip.pageTops[pageIndex])
            return ResolvedScrollPoint(
                chapterIndex = block.chapterIndex,
                pageIndex = pageIndex,
                page = page,
                local = Offset(localX, localY)
            )
        }
        return null
    }

    fun chapterBody(chapterIndex: Int): String? = controller.chapterBody(chapterIndex)

    fun englishWordAt(position: Offset): com.mozhi.reader.core.dictionary.DictionaryLookupHit? {
        val hit = resolve(position) ?: return null
        val pos = hit.page.hitTextPos(hit.local.x, hit.local.y, exact = true) ?: return null
        val body = chapterBody(hit.chapterIndex) ?: return null
        return com.mozhi.reader.core.dictionary.EnglishWords.at(body, hit.page.charOffsetOf(pos), hit.chapterIndex)
    }

    fun visiblePages(chapterIndex: Int): List<VisibleScrollPage> {
        val currentStyle = style ?: return emptyList()
        val block = visibleBlocks().firstOrNull { it.chapterIndex == chapterIndex } ?: return emptyList()
        val strip = block.strip ?: return emptyList()
        return strip.chapter.pages.mapIndexed { pageIndex, page ->
            VisibleScrollPage(
                pageIndex = pageIndex,
                page = page,
                origin = Offset(
                    currentStyle.paddingLeft,
                    contentTop + block.originY + strip.pageTops[pageIndex]
                )
            )
        }
    }

    fun visibleSourceRange(chapterIndex: Int): IntRange? {
        val lines = visiblePages(chapterIndex).flatMap { page -> page.page.lines.filter { line ->
            // 竖排页的一行是一列：它在屏幕上的纵向范围是列里首尾字的帧 x。
            val top = if (page.page.isVertical) line.columns.firstOrNull()?.start ?: line.startX else line.lineTop
            val bottom = if (page.page.isVertical) line.columns.lastOrNull()?.end ?: top else line.lineBottom
            line.charLength > 0 && page.origin.y + bottom > contentTop && page.origin.y + top < contentBottom
        } }
        val first = lines.minOfOrNull { it.chapterPosition } ?: return null
        return first until lines.maxOf { it.chapterPosition + it.charLength }
    }

    /** Capture only the source prefix actually painted inside the clipped content band. */
    fun captureVisibleRead(): ReaderVisibleReadSnapshot? {
        if (!controller.isReady || renderer == null || viewWidth <= 0 || viewHeight <= 0) return null
        var result: ReaderVisibleReadSnapshot? = null
        for (block in visibleBlocks()) {
            val strip = block.strip ?: break
            val source = controller.chapterSource(block.chapterIndex) ?: break
            val pages = mutableListOf<RenderPage.Laid>()
            var end: Int? = null
            for ((index, page) in strip.chapter.pages.withIndex()) {
                val origin = contentTop + block.originY + strip.pageTops[index]
                if (origin >= contentBottom) break
                val extent = if (index + 1 < strip.pageTops.size) strip.pageTops[index + 1] - strip.pageTops[index]
                    else strip.totalHeight - strip.pageTops[index]
                if (origin + extent <= contentTop) continue
                val visibleEnd = page.visibleReadEnd(contentTop - origin, contentBottom - origin) ?: continue
                end = maxOf(end ?: 0, visibleEnd)
                pages += RenderPage.Laid(block.chapterIndex, strip.chapter.title, index, strip.chapter.pageCount, page)
            }
            if (end != null) result = ReaderVisibleReadSnapshot(controller.layoutGeneration,
                block.chapterIndex, pages, end, source)
        }
        return result
    }

    fun isCurrentVisibleRead(snapshot: ReaderVisibleReadSnapshot): Boolean = snapshot == captureVisibleRead()

    /** Called after drawFrame, never from loading, pagination, hit testing or prefetch. */
    fun onFrameDrawn(isVisible: () -> Boolean, onVisible: (ReaderVisibleReadSnapshot) -> Unit) {
        if (!isVisible()) return
        val drawn = captureVisibleRead() ?: return
        if (pendingDrawnSnapshot == drawn) return
        pendingDrawnSnapshot = drawn
        mainHandler.post {
            if (pendingDrawnSnapshot != drawn) return@post
            pendingDrawnSnapshot = null
            if (isVisible() && isCurrentVisibleRead(drawn)) onVisible(drawn)
        }
    }

    /** 选区/绘制用：页的内容原点（视图坐标）；该页当前不可见时返回 null。 */
    fun pageOriginInView(chapterIndex: Int, pageIndex: Int): Offset? {
        val style = style ?: return null
        val block = visibleBlocks().firstOrNull { it.chapterIndex == chapterIndex } ?: return null
        val strip = block.strip ?: return null
        val top = strip.pageTops.getOrNull(pageIndex) ?: return null
        return Offset(style.paddingLeft, contentTop + block.originY + top)
    }

    fun imageAt(position: Offset): ReaderPageImage? {
        val currentStyle = style ?: return null
        val hit = resolve(position) ?: return null
        val (physicalX, _) = hit.page.frameToPhysical(hit.local.x, hit.local.y)
        if (physicalX < 0f || physicalX >= currentStyle.contentWidth) return null
        return hit.page.imageAt(hit.local.x, hit.local.y, hit.chapterIndex)
    }

    fun linkAt(position: Offset): ReaderPageLink? {
        val hit = resolve(position) ?: return null
        return hit.page.linkAt(hit.local.x, hit.local.y, hit.chapterIndex)
    }

    fun annotationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val hit = resolve(position) ?: return emptyList()
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val geometry = hit.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == hit.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == hit.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.INLINE_MARKER_GAP_RATIO,
            maxRight = hit.page.lineExtent(currentStyle.contentWidth, currentStyle.spec.visibleHeight)
        )
        return geometry.annotationIdsAt(hit.local.x, hit.local.y)
    }

    fun illustrationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val hit = resolve(position) ?: return emptyList()
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val markers = hit.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == hit.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == hit.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.INLINE_MARKER_GAP_RATIO,
            maxRight = hit.page.lineExtent(currentStyle.contentWidth, currentStyle.spec.visibleHeight)
        )
        val hitRadius = (currentStyle.tipSizePx * 1.35f).coerceAtLeast(18f)
        return markers.markers.firstOrNull { marker ->
            if (marker.illustrationIds.isEmpty()) return@firstOrNull false
            val dx = hit.local.x - marker.centerX
            val dy = hit.local.y - marker.centerY
            dx * dx + dy * dy <= hitRadius * hitRadius
        }?.illustrationIds.orEmpty()
    }

    // ---- 绘制 ----

    fun drawFrame(canvas: android.graphics.Canvas, width: Float, height: Float) {
        val style = style
        val renderer = renderer
        if (style == null || renderer == null) {
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            return
        }
        canvas.save()
        canvas.clipRect(0f, contentTop, width, contentBottom)
        for (block in visibleBlocks()) {
            val blockTop = contentTop + block.originY
            val strip = block.strip
            if (strip == null) {
                renderer.drawScrollPlaceholder(
                    canvas = canvas,
                    title = chapterTitle(block.chapterIndex),
                    message = "加载中…",
                    top = blockTop,
                    height = placeholderHeight
                )
                continue
            }
            val chapter = strip.chapter
            val chapterAnnotations = annotations.filter { it.chapterIndex == chapter.chapterIndex }
            val chapterIllustrations = illustrations.filter { it.chapterIndex == chapter.chapterIndex }
            val highlight = transientHighlight?.takeIf { it.chapterIndex == chapter.chapterIndex }
            for (pageIndex in chapter.pages.indices) {
                val pageTop = blockTop + strip.pageTops[pageIndex]
                val pageExtent = if (pageIndex + 1 < strip.pageTops.size) {
                    strip.pageTops[pageIndex + 1] - strip.pageTops[pageIndex]
                } else {
                    strip.totalHeight - strip.pageTops[pageIndex]
                }
                if (pageTop + pageExtent < contentTop || pageTop > contentBottom) continue
                val page = chapter.pages[pageIndex]
                canvas.save()
                canvas.translate(style.paddingLeft, pageTop)
                renderer.drawContent(
                    canvas = canvas,
                    page = RenderPage.Laid(
                        chapterIndex = chapter.chapterIndex,
                        chapterTitle = chapter.title,
                        pageIndex = page.index,
                        pageCount = chapter.pageCount,
                        page = page
                    ),
                    annotations = chapterAnnotations,
                    illustrations = chapterIllustrations,
                    transientHighlight = highlight,
                    clipTop = contentTop - pageTop,
                    clipBottom = contentBottom - pageTop
                )
                canvas.restore()
            }
        }
        canvas.restore()

        // 固定页眉/页脚：章名 + 进度/时间/电量，按视口顶部实时位置计算。
        val curStrip = stripFor(controller.chapterIndex)
        val headerPage: RenderPage
        val progress: Float
        if (curStrip != null) {
            val pageIndex = curStrip.pageIndexAt(anchorY)
            val chapter = curStrip.chapter
            headerPage = RenderPage.Laid(
                chapterIndex = chapter.chapterIndex,
                chapterTitle = chapter.title,
                pageIndex = pageIndex,
                pageCount = chapter.pageCount,
                page = chapter.pages[pageIndex.coerceIn(chapter.pages.indices)]
            )
            progress = controller.progressAt(
                controller.chapterIndex,
                curStrip.charOffsetAt(anchorY)
            )
        } else {
            headerPage = controller.curPage()
            progress = controller.bookProgress()
        }
        renderer.drawHeader(canvas, headerPage, progress, timeText, batteryPercent)
        renderer.drawFooter(canvas, headerPage, progress, timeText, batteryPercent)
    }

    /** 占位块标题：走页工厂拿章节元数据标题（未排版章也有标题可显示）。 */
    private fun chapterTitle(chapterIndex: Int): String = when (chapterIndex - controller.chapterIndex) {
        0 -> controller.curPage().chapterTitle
        1 -> controller.nextPage().chapterTitle
        else -> ""
    }

    // ---- 页眉页脚数据 ----

    fun updateTime(): Boolean {
        val next = timeFormat.format(Date())
        if (next == timeText) return false
        timeText = next
        return true
    }

    fun updateBattery(intent: Intent): Boolean {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        val next = (level * 100) / scale
        if (next == batteryPercent) return false
        batteryPercent = next
        return true
    }

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ROOT)
    }
}

/** 听书跟读把句首安放的视口高度比例。 */
private const val FOLLOW_TARGET_FRACTION = 0.28f
