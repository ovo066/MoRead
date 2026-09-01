package com.mozhi.reader.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.prompt.SelectionAiAction
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.engine.ChapterStrip
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.ReaderPageLink
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.SelectionRect
import com.mozhi.reader.feature.reader.engine.TextPage
import com.mozhi.reader.feature.reader.engine.annotationGeometry
import com.mozhi.reader.feature.reader.engine.dragSelectionHandle
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.inlineMarkerLayout
import com.mozhi.reader.feature.reader.engine.linkAt
import com.mozhi.reader.feature.reader.engine.selectionBodyRange
import com.mozhi.reader.feature.reader.engine.selectionRects
import com.mozhi.reader.feature.reader.engine.textPosAtBodyOffset
import com.mozhi.reader.feature.reader.engine.wordSelectionAt
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 连续滚动阅读面（PageMode.SCROLL）：以章节为单位把分页布局拼成 [ChapterStrip] 条带，
 * 手指驱动 + 惯性衰减自由滚动，跨章无缝续读。页眉/页脚固定，正文在中间的内容带内
 * 滚动；选词、批注墨迹、「评」标记、听书句底色全部复用分页引擎的几何与画笔。
 *
 * 位置真源仍是 controller 的 (chapterIndex, charOffset)：滚动落定/跨章时把视口顶部
 * 的字符写回；目录/书签/搜索等外部跳转通过 contentHook 通知本面重锚。
 */
@Composable
fun ReaderScrollPane(
    controller: ReaderContentController,
    settings: ReaderSettings,
    palette: ReaderPalette,
    enabled: Boolean,
    registerContentHook: (((Int) -> Unit)?) -> Unit,
    onScrollSupersedesNavigation: () -> Unit,
    onCenterTap: () -> Unit,
    onBoundary: (PageTurnDirection) -> Unit,
    onNotice: (String) -> Unit,
    annotations: List<ReaderAnnotationMark>,
    illustrations: List<ReaderIllustrationMark> = emptyList(),
    transientHighlight: TransientHighlightSpan? = null,
    listenPlaying: Boolean = false,
    onAiAction: (action: SelectionAiAction, selection: String, context: String) -> Unit,
    onAnnotationAction: (selection: String, range: IntRange, anchorTopPx: Int) -> Unit,
    onAnnotationClick: (annotationIds: List<Long>) -> Unit,
    onIllustrationClick: (illustrationIds: List<Long>) -> Unit = {},
    onLinkClick: (ReaderPageLink) -> Unit = {},
    onTtsAction: (selection: String) -> Unit,
    onImageAction: (selection: String, context: String, range: IntRange) -> Unit,
    onEditText: (selection: String, range: IntRange) -> Unit,
    pageTurnRequest: ReaderPageTurnRequest? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val statusBarPx = WindowInsets.statusBars.getTop(density).toFloat()
    val navBarPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val flingDecay = rememberSplineBasedDecay<Float>()

    var frameTick by remember { mutableIntStateOf(0) }
    var backgroundTick by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val holder = remember(controller) { ScrollPaneHolder(controller) }
    val selection = remember(controller) {
        ScrollSelectionController(
            holder = holder,
            onSelectionStarted = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            },
            onSelectionChanged = { frameTick++ }
        )
    }

    fun invalidate() {
        frameTick++
    }

    /** 平滑滚动 [distance]（正向下阅读方向），逐帧走 applyScroll 保持跨章语义。 */
    fun animateScrollBy(distance: Float, supersedesNavigation: Boolean = true) {
        if (supersedesNavigation) onScrollSupersedesNavigation()
        holder.interruptScroll()
        holder.scrollJob = scope.launch {
            holder.flinging = true
            var played = 0f
            var boundary: PageTurnDirection? = null
            try {
                animate(
                    initialValue = 0f,
                    targetValue = distance,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                ) { value, _ ->
                    boundary = holder.applyScroll(value - played) ?: boundary
                    played = value
                    invalidate()
                }
            } finally {
                holder.flinging = false
                holder.syncPosition()
                invalidate()
            }
            boundary?.let(onBoundary)
        }
    }

    LaunchedEffect(pageTurnRequest?.sequence) {
        val request = pageTurnRequest ?: return@LaunchedEffect
        if (!enabled) return@LaunchedEffect
        val distance = holder.viewportHeight * PAGE_STEP_FRACTION
        animateScrollBy(
            if (request.direction == PageTurnDirection.PREVIOUS) -distance else distance
        )
    }

    fun startFling(velocity: Float) {
        holder.interruptScroll()
        holder.scrollJob = scope.launch {
            holder.flinging = true
            var last = 0f
            try {
                AnimationState(initialValue = 0f, initialVelocity = velocity)
                    .animateDecay(flingDecay) {
                        val delta = value - last
                        last = value
                        val boundary = holder.applyScroll(delta)
                        invalidate()
                        if (boundary != null) cancelAnimation()
                    }
            } finally {
                holder.flinging = false
                holder.syncPosition()
                invalidate()
            }
        }
    }

    // 排版环境：字号/字体/行距/边距/视口变化触发重排，仅调色变化只重绘。
    val environment = ReaderScrollEnvironmentKey(
        fontScale = settings.fontScale,
        font = settings.font,
        customFontPath = settings.customFontPath,
        lineHeight = settings.lineHeight,
        publisherStyleMode = settings.publisherStyleMode,
        pageMarginsHash = listOf(
            settings.pageMarginLeft,
            settings.pageMarginRight,
            settings.pageMarginTop,
            settings.pageMarginBottom
        ).hashCode(),
        advancedTypographyHash = listOf(
            settings.fontWeight,
            settings.letterSpacingEm,
            settings.paragraphSpacingEm,
            settings.firstLineIndentEm,
            settings.titleScale,
            settings.titleTopSpacing,
            settings.titleBottomSpacing,
            settings.textJustification,
            settings.showHeader,
            settings.showFooter,
            settings.headerMarginTop,
            settings.footerMarginBottom
        ).hashCode(),
        syntaxHighlightEnabled = settings.syntaxHighlightEnabled,
        syntaxRulesHash = settings.syntaxHighlightRules.hashCode(),
        width = viewport.width,
        height = viewport.height
    )
    remember(
        environment,
        palette,
        settings.backgroundImagePath,
        settings.backgroundImageOpacity
    ) {
        if (viewport.width > 0 && viewport.height > 0) {
            val style = ReaderPageStyle.resolve(
                settings = settings,
                palette = palette,
                density = density,
                viewWidth = viewport.width,
                viewHeight = viewport.height,
                statusBarPx = statusBarPx,
                navigationBarPx = navBarPx
            )
            holder.interruptScroll()
            selection.clear()
            // 背景图在后台合成，落地后再重绘——首帧不等它。
            val relayout = holder.applyStyle(style, environment) {
                invalidate()
                backgroundTick++
            }
            if (relayout) {
                controller.updateEnvironment(style.spec, style.measure)
            }
            invalidate()
            backgroundTick++
        }
        environment
    }

    LaunchedEffect(annotations) {
        holder.annotations = annotations
        invalidate()
    }
    LaunchedEffect(illustrations) {
        holder.illustrations = illustrations
        frameTick++
    }
    LaunchedEffect(transientHighlight) {
        holder.transientHighlight = transientHighlight
        invalidate()
    }

    // 听书温和跟读：句子快出视口才追，用户滚去别处浏览时不硬拽回来。
    LaunchedEffect(transientHighlight, listenPlaying) {
        if (!listenPlaying) return@LaunchedEffect
        val highlight = transientHighlight ?: return@LaunchedEffect
        if (holder.dragging || selection.isActive) return@LaunchedEffect
        holder.listenFollowDelta(highlight)?.let { delta ->
            animateScrollBy(delta, supersedesNavigation = false)
        }
    }

    DisposableEffect(controller) {
        registerContentHook { relativePosition ->
            if (relativePosition == 0) {
                selection.clear()
                holder.onContentRefreshed()
            }
            frameTick++
        }
        onDispose {
            registerContentHook(null)
            holder.interruptScroll()
            holder.release()
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (intent == null) return
                val changed = when (intent.action) {
                    Intent.ACTION_TIME_TICK -> holder.updateTime()
                    Intent.ACTION_BATTERY_CHANGED -> holder.updateBattery(intent)
                    else -> false
                }
                if (changed) frameTick++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val sticky = context.registerReceiver(receiver, filter)
        if (sticky != null) holder.updateBattery(sticky)
        onDispose { context.unregisterReceiver(receiver) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景独立成静态兄弟层；anchorY 的逐帧变化只会让下面的透明内容层失效。
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { }
                .drawBehind {
                    backgroundTick
                    holder.drawBackground(
                        drawContext.canvas.nativeCanvas,
                        size.width,
                        size.height
                    )
                }
        )
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    if (holder.setViewport(size.width, size.height)) viewport = size
                }
                .magnifier(
                    sourceCenter = { selection.magnifierCenter ?: Offset.Unspecified },
                    magnifierCenter = {
                        selection.magnifierCenter?.let { center ->
                            Offset(center.x, center.y - 94.dp.toPx())
                        } ?: Offset.Unspecified
                    },
                    size = DpSize(168.dp, 76.dp),
                    cornerRadius = 18.dp,
                    elevation = 8.dp,
                    zoom = 2f
                )
                .pointerInput(enabled, holder, selection) {
                    if (!enabled) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    val handleGrabRadius = 24.dp.toPx()
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val interruptedFling = holder.flinging
                        holder.interruptScroll()
                        val hadSelection = selection.isActive
                        var selecting = false
                        if (hadSelection) {
                            selecting = selection.grabHandle(down.position, handleGrabRadius)
                            if (!selecting) selection.clear()
                        }
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        val pointerId = down.id
                        var upPosition = down.position
                        var longPressFired = false
                        var slopCrossed = false
                        var lastY = down.position.y
                        var lastUptime = down.uptimeMillis
                        var boundaryHit: PageTurnDirection? = null
                        while (true) {
                            val event = if (
                                !longPressFired && !slopCrossed && !hadSelection && !selecting
                            ) {
                                val remaining =
                                    SCROLL_LONG_PRESS_TIMEOUT_MS - (lastUptime - down.uptimeMillis)
                                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                            } else {
                                awaitPointerEvent()
                            }
                            if (event == null) {
                                longPressFired = true
                                selecting = selection.begin(upPosition)
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            upPosition = change.position
                            lastUptime = change.uptimeMillis
                            if (!change.pressed) break
                            if (change.positionChanged()) {
                                if (selecting) {
                                    selection.drag(change.position)
                                    change.consume()
                                    continue
                                }
                                val deltaX = change.position.x - down.position.x
                                val deltaY = change.position.y - down.position.y
                                if (!slopCrossed &&
                                    deltaX * deltaX + deltaY * deltaY > slop * slop
                                ) {
                                    slopCrossed = true
                                    onScrollSupersedesNavigation()
                                    holder.dragging = true
                                    // 起步吃掉 slop：从跨过阈值那一刻起内容一比一跟手。
                                    lastY = change.position.y
                                }
                                if (slopCrossed) {
                                    val dy = change.position.y - lastY
                                    lastY = change.position.y
                                    tracker.addPosition(change.uptimeMillis, change.position)
                                    boundaryHit = holder.applyScroll(-dy) ?: boundaryHit
                                    frameTick++
                                }
                                change.consume()
                            }
                        }
                        if (selecting) {
                            selection.end()
                            return@awaitEachGesture
                        }
                        if (slopCrossed) {
                            holder.dragging = false
                            holder.syncPosition()
                            frameTick++
                            boundaryHit?.let(onBoundary)
                            val velocity = tracker.calculateVelocity().y
                            if (boundaryHit == null && abs(velocity) > MIN_FLING_VELOCITY) {
                                startFling(-velocity)
                            }
                            return@awaitEachGesture
                        }
                        holder.dragging = false
                        if (hadSelection || longPressFired) return@awaitEachGesture
                        // 纯点击：先批注热区，再左右滚屏分区，最后中央呼出 chrome。
                        val illustrationIds = holder.illustrationIdsAt(upPosition)
                        if (illustrationIds.isNotEmpty()) {
                            selection.clear()
                            onIllustrationClick(illustrationIds)
                            return@awaitEachGesture
                        }
                        val annotationIds = holder.annotationIdsAt(upPosition)
                        if (annotationIds.isNotEmpty()) {
                            selection.clear()
                            onAnnotationClick(annotationIds)
                            return@awaitEachGesture
                        }
                        holder.linkAt(upPosition)?.let { link ->
                            selection.clear()
                            onLinkClick(link)
                            return@awaitEachGesture
                        }
                        val fraction = upPosition.x / size.width.coerceAtLeast(1)
                        val pageStep = holder.viewportHeight * PAGE_STEP_FRACTION
                        when {
                            fraction < PREV_TAP_ZONE -> animateScrollBy(-pageStep)
                            fraction > NEXT_TAP_ZONE -> animateScrollBy(pageStep)
                            !interruptedFling -> onCenterTap()
                            else -> Unit
                        }
                    }
                }
                .drawBehind {
                    frameTick // draw-phase read：内容/样式变化都经它失效
                    holder.anchorY // 滚动逐帧值同样只在 draw phase 读
                    val canvas = drawContext.canvas.nativeCanvas
                    holder.drawFrame(canvas, size.width, size.height)
                    selection.drawHighlight(this, palette)
                }
        )

        selection.active?.takeIf { !it.dragging }?.let { active ->
            val toolbarTopPx = selection.toolbarTop(active, density)
            SelectionToolbar(
                palette = palette,
                topPx = toolbarTopPx,
                onAi = { action ->
                    val text = selection.selectedText()
                    val range = selection.bodyRange()
                    if (text.isNotBlank() && range != null) {
                        onAiAction(action, text, controller.contextAround(range))
                    }
                    selection.clear()
                },
                onAnnotation = {
                    val text = selection.selectedText()
                    val range = selection.bodyRange()
                    if (text.isNotBlank() && range != null) {
                        onAnnotationAction(text, range, toolbarTopPx)
                    }
                    selection.clear()
                },
                onTts = {
                    selection.selectedText().takeIf(String::isNotBlank)?.let(onTtsAction)
                    selection.clear()
                },
                onImage = {
                    val text = selection.selectedText()
                    val range = selection.bodyRange()
                    if (text.isNotBlank() && range != null) {
                        onImageAction(text, controller.contextAround(range), range)
                    }
                    selection.clear()
                },
                onEdit = {
                    val text = selection.selectedText()
                    val range = selection.bodyRange()
                    if (text.isNotBlank() && range != null) onEditText(text, range)
                    selection.clear()
                },
                onCopy = {
                    val text = selection.selectedText()
                    if (text.isNotEmpty()) {
                        clipboard.setText(AnnotatedString(text))
                        onNotice("已复制 ${text.length} 字")
                    }
                    selection.clear()
                },
                onDismiss = { selection.clear() }
            )
        }
    }
}

private data class ReaderScrollEnvironmentKey(
    val fontScale: Float,
    val font: com.mozhi.reader.core.datastore.ReaderFont,
    val customFontPath: String?,
    val lineHeight: Float,
    val publisherStyleMode: com.mozhi.reader.core.datastore.PublisherStyleMode,
    val pageMarginsHash: Int,
    val advancedTypographyHash: Int,
    val syntaxHighlightEnabled: Boolean,
    val syntaxRulesHash: Int,
    val width: Int,
    val height: Int
)

/** 视口内一个章节块：条带原点在内容带坐标系（0 = 页眉下沿）里的位置。 */
private data class ChapterBlock(
    val chapterIndex: Int,
    val originY: Float,
    val strip: ChapterStrip?
)

/** 命中解析结果：页与页内局部坐标（内容局部系，可直接喂选区/批注几何）。 */
private data class ResolvedScrollPoint(
    val chapterIndex: Int,
    val pageIndex: Int,
    val page: TextPage,
    val local: Offset
)

private data class VisibleScrollPage(
    val pageIndex: Int,
    val page: TextPage,
    val origin: Offset
)

/**
 * 滚动面的全部非 Compose 状态：条带缓存、锚点、滚动推进与跨章滑窗、命中解析、绘制。
 * 锚点模型 = (controller.chapterIndex, anchorY)：anchorY 是视口内容带顶边在当前章条带
 * 里的 Y。跨章由 applyScroll 归一化，controller 滑窗跟着走。
 */
private class ScrollPaneHolder(private val controller: ReaderContentController) {
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set

    private var style: ReaderPageStyle? = null
    private var renderer: PageBitmapRenderer? = null
    private var appliedEnvironment: ReaderScrollEnvironmentKey? = null
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

    fun applyStyle(
        style: ReaderPageStyle,
        environment: ReaderScrollEnvironmentKey,
        onBackgroundReady: () -> Unit
    ): Boolean {
        val relayout = appliedEnvironment != environment
        appliedEnvironment = environment
        this.style = style
        renderer?.release()
        renderer = PageBitmapRenderer(style).also { it.prepareBackground(onBackgroundReady) }
        strips.clear()
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

    fun release() {
        renderer?.release()
        renderer = null
        strips.clear()
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
        controller.scrollTo(target, guessOffset)
    }

    /** 把视口顶部字符写回 controller（进度持久化与伴读/听书上下文都吃它）。 */
    fun syncPosition() {
        val strip = stripFor(controller.chapterIndex) ?: return
        val offset = strip.charOffsetAt(anchorY)
        lastSyncedChapter = controller.chapterIndex
        lastSyncedOffset = offset
        controller.scrollTo(controller.chapterIndex, offset)
    }

    /**
     * contentHook(0)：当前章重排/加载完成，或外部跳转（目录/书签/搜索/换排版）。
     * 空闲时按 charOffset 重锚；滚动进行中只钳制锚点，别打断手感。
     */
    fun onContentRefreshed() {
        if (dragging || flinging) {
            clampAnchor()
        } else {
            reanchorToPosition()
        }
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
            return ResolvedScrollPoint(
                chapterIndex = block.chapterIndex,
                pageIndex = pageIndex,
                page = page,
                local = Offset(
                    position.x - style.paddingLeft,
                    stripY - strip.pageTops[pageIndex]
                )
            )
        }
        return null
    }

    fun chapterBody(chapterIndex: Int): String? = controller.chapterBody(chapterIndex)

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

    /** 选区/绘制用：页的内容原点（视图坐标）；该页当前不可见时返回 null。 */
    fun pageOriginInView(chapterIndex: Int, pageIndex: Int): Offset? {
        val style = style ?: return null
        val block = visibleBlocks().firstOrNull { it.chapterIndex == chapterIndex } ?: return null
        val strip = block.strip ?: return null
        val top = strip.pageTops.getOrNull(pageIndex) ?: return null
        return Offset(style.paddingLeft, contentTop + block.originY + top)
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
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
            maxRight = currentStyle.contentWidth
        )
        val hitRadius = (currentStyle.tipSizePx * 1.35f).coerceAtLeast(18f)
        geometry.markers.firstOrNull { marker ->
            if (marker.annotationIds.isEmpty()) return@firstOrNull false
            val dx = hit.local.x - marker.centerX
            val dy = hit.local.y - marker.centerY
            dx * dx + dy * dy <= hitRadius * hitRadius
        }?.let { return it.annotationIds }
        return geometry.highlights
            .filter { rect ->
                hit.local.x in rect.left..rect.right && hit.local.y in rect.top..rect.bottom
            }
            .map { it.annotationId }
            .distinct()
    }

    fun illustrationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val hit = resolve(position) ?: return emptyList()
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val markers = hit.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == hit.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == hit.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
            maxRight = currentStyle.contentWidth
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
        renderer.drawHeader(canvas, headerPage)
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

/** 滚动模式长按选词：用章节正文偏移保存范围，因此可跨多个排版页。 */
private class ScrollSelectionController(
    private val holder: ScrollPaneHolder,
    private val onSelectionStarted: () -> Unit,
    private val onSelectionChanged: () -> Unit
) {
    data class ActiveSelection(
        val chapterIndex: Int,
        val anchorStartOffset: Int,
        val anchorEndOffset: Int,
        val startOffset: Int,
        val endOffset: Int,
        val dragging: Boolean
    ) {
        val bodyRange: IntRange get() = startOffset..endOffset
    }

    private enum class Handle { START, END }

    var active by mutableStateOf<ActiveSelection?>(null)
        private set
    var magnifierCenter by mutableStateOf<Offset?>(null)
        private set

    private var draggedHandle: Handle? = null

    val isActive: Boolean get() = active != null

    fun begin(position: Offset): Boolean {
        val hit = holder.resolve(position) ?: return false
        val pos = hit.page.hitTextPos(hit.local.x, hit.local.y, exact = true) ?: return false
        val word = hit.page.wordSelectionAt(pos)
        val range = hit.page.selectionBodyRange(word.first, word.second)
        draggedHandle = null
        active = ActiveSelection(
            chapterIndex = hit.chapterIndex,
            anchorStartOffset = range.first,
            anchorEndOffset = range.last,
            startOffset = range.first,
            endOffset = range.last,
            dragging = true
        )
        magnifierCenter = position
        onSelectionStarted()
        onSelectionChanged()
        return true
    }

    fun grabHandle(position: Offset, radiusPx: Float): Boolean {
        val current = active ?: return false
        val startCenter = endpointCenter(current.chapterIndex, current.startOffset, startSide = true)
        val endCenter = endpointCenter(current.chapterIndex, current.endOffset, startSide = false)
        val startDistance = startCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        val endDistance = endCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        draggedHandle = when {
            startDistance > radiusPx && endDistance > radiusPx -> return false
            startDistance <= endDistance -> Handle.START
            else -> Handle.END
        }
        magnifierCenter = position
        active = current.copy(dragging = true)
        onSelectionChanged()
        return true
    }

    fun drag(position: Offset) {
        magnifierCenter = position
        val current = active ?: return
        val hit = holder.resolve(position) ?: return
        if (hit.chapterIndex != current.chapterIndex) return
        val pos = hit.page.hitTextPos(hit.local.x, hit.local.y, exact = false) ?: return
        val hitRange = hit.page.selectionBodyRange(pos, pos)
        val handle = draggedHandle
        val next = if (handle != null) {
            val moved = dragSelectionHandle(
                start = current.startOffset,
                end = current.endOffset,
                hit = if (handle == Handle.START) hitRange.first else hitRange.last,
                draggingStart = handle == Handle.START
            )
            draggedHandle = if (moved.draggingStart) Handle.START else Handle.END
            current.copy(startOffset = moved.start, endOffset = moved.end)
        } else {
            when {
                hitRange.first < current.anchorStartOffset -> current.copy(
                    startOffset = hitRange.first,
                    endOffset = current.anchorEndOffset
                )
                hitRange.last > current.anchorEndOffset -> current.copy(
                    startOffset = current.anchorStartOffset,
                    endOffset = hitRange.last
                )
                else -> current.copy(
                    startOffset = current.anchorStartOffset,
                    endOffset = current.anchorEndOffset
                )
            }
        }
        if (next.startOffset != current.startOffset || next.endOffset != current.endOffset) {
            active = next
            onSelectionChanged()
        }
    }

    fun end() {
        draggedHandle = null
        magnifierCenter = null
        active = active?.copy(dragging = false)
        onSelectionChanged()
    }

    fun clear() {
        draggedHandle = null
        magnifierCenter = null
        active = null
        onSelectionChanged()
    }

    fun selectedText(): String {
        val current = active ?: return ""
        val body = holder.chapterBody(current.chapterIndex) ?: return ""
        val start = current.startOffset.coerceIn(0, body.length)
        val endExclusive = (current.endOffset + 1).coerceIn(start, body.length)
        return body.substring(start, endExclusive).replace('\uFFFC'.toString(), "［图片］")
    }

    fun bodyRange(): IntRange? = active?.bodyRange

    fun drawHighlight(drawScope: DrawScope, palette: ReaderPalette) {
        val current = active ?: return
        val pages = holder.visiblePages(current.chapterIndex)
        val color = palette.accent.copy(alpha = if (palette.isDark) 0.18f else 0.30f)
        pages.forEach { visible ->
            visible.page.selectionRects(current.bodyRange).forEach { rect ->
                drawScope.drawRect(
                    color = color,
                    topLeft = Offset(visible.origin.x + rect.left, visible.origin.y + rect.top),
                    size = Size(rect.right - rect.left, rect.bottom - rect.top)
                )
            }
        }
        endpointGeometry(current.chapterIndex, current.startOffset, startSide = true)?.let { endpoint ->
            drawHandle(drawScope, palette, endpoint.center.x, endpoint.top, endpoint.bottom)
        }
        endpointGeometry(current.chapterIndex, current.endOffset, startSide = false)?.let { endpoint ->
            drawHandle(drawScope, palette, endpoint.center.x, endpoint.top, endpoint.bottom)
        }
    }

    private data class Endpoint(val center: Offset, val top: Float, val bottom: Float)

    private fun endpointCenter(chapterIndex: Int, offset: Int, startSide: Boolean): Offset? =
        endpointGeometry(chapterIndex, offset, startSide)?.center

    private fun endpointGeometry(chapterIndex: Int, offset: Int, startSide: Boolean): Endpoint? {
        holder.visiblePages(chapterIndex).forEach { visible ->
            val pageEnd = visible.page.chapterPosition + visible.page.charLength
            if (offset !in visible.page.chapterPosition until pageEnd) return@forEach
            val pos = visible.page.textPosAtBodyOffset(offset) ?: return@forEach
            val line = visible.page.lines[pos.lineIndex]
            val column = line.columns[pos.columnIndex]
            val x = visible.origin.x + if (startSide) column.start else column.end
            return Endpoint(
                center = Offset(x, visible.origin.y + line.lineBottom),
                top = visible.origin.y + line.lineTop,
                bottom = visible.origin.y + line.lineBottom
            )
        }
        return null
    }

    private fun drawHandle(
        drawScope: DrawScope,
        palette: ReaderPalette,
        x: Float,
        top: Float,
        bottom: Float
    ) = with(drawScope) {
        val barWidth = 2.dp.toPx()
        val radius = 6.dp.toPx()
        drawRect(
            color = palette.accent,
            topLeft = Offset(x - barWidth / 2f, top),
            size = Size(barWidth, bottom - top)
        )
        drawCircle(
            color = palette.accent,
            radius = radius,
            center = Offset(x, bottom + radius * 0.8f)
        )
    }

    fun toolbarTop(current: ActiveSelection, density: Density): Int {
        val rects = holder.visiblePages(current.chapterIndex)
            .flatMap { visible ->
                visible.page.selectionRects(current.bodyRange).map { rect -> rect to visible.origin }
            }
        val gap = with(density) { 12.dp.toPx() }
        val barHeight = with(density) { 48.dp.toPx() }
        val first = rects.minByOrNull { (rect, origin) -> origin.y + rect.top } ?: return 0
        val above = first.second.y + first.first.top - gap - barHeight
        if (above > with(density) { 72.dp.toPx() }) return above.toInt()
        val last = rects.maxByOrNull { (rect, origin) -> origin.y + rect.bottom } ?: return 0
        return (last.second.y + last.first.bottom + gap).toInt()
    }
}
private const val SCROLL_LONG_PRESS_TIMEOUT_MS = 600L
private const val MIN_FLING_VELOCITY = 320f
/** 点按左右分区时滚动一「屏」的比例，留一点重叠帮助接续。 */
private const val PAGE_STEP_FRACTION = 0.92f
/** 听书跟读把句首安放的视口高度比例。 */
private const val FOLLOW_TARGET_FRACTION = 0.28f
