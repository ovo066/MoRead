package com.mozhi.reader.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.feature.reader.engine.imageAt
import com.mozhi.reader.feature.reader.engine.linkAt
import com.mozhi.reader.feature.reader.engine.translationAt
import com.mozhi.reader.feature.reader.engine.ReaderParagraphTranslation
import com.mozhi.reader.feature.reader.engine.ReaderVisibleReadSnapshot
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * 连续滚动阅读面（PageMode.SCROLL）：以章节为单位把分页布局拼成 [ChapterStrip] 条带，
 * 手指驱动 + 惯性衰减自由滚动，跨章无缝续读。页眉/页脚固定，正文在中间的内容带内
 * 滚动；选词、批注墨迹、评论小点、听书句底色全部复用分页引擎的几何与画笔。
 *
 * 位置真源仍是 controller 的 (chapterIndex, charOffset)：滚动落定/跨章时把视口顶部
 * 的字符写回；目录/书签/搜索等外部跳转通过 contentHook 通知本面重锚。
 */
@Composable
internal fun ReaderScrollPane(
    controller: ReaderContentController,
    holder: ScrollPaneHolder,
    settings: ReaderSettings,
    palette: ReaderPalette,
    enabled: Boolean,
    registerContentHook: (((Int) -> Unit)?) -> Unit,
    onScrollSupersedesNavigation: () -> Unit,
    onCenterTap: () -> Unit,
    onTapAction: (com.mozhi.reader.core.datastore.ReaderTapAction) -> Unit = {},
    onDictionaryLookup: (com.mozhi.reader.core.dictionary.DictionaryLookupHit) -> Unit = {},
    onParagraphTranslation: (chapterIndex: Int, offset: Int) -> Unit = { _, _ -> },
    onTranslationLongPress: (ReaderParagraphTranslation) -> Unit = {},
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
    onEpubImageLongPress: (ReaderPageImage) -> Unit = {},
    onTtsAction: (selection: String) -> Unit,
    onImageAction: (selection: String, context: String, range: IntRange) -> Unit,
    onEditText: ((selection: String, range: IntRange) -> Unit)?,
    pageTurnRequest: ReaderPageTurnRequest? = null,
    autoRead: AutoReadSession? = null,
    onVisiblePagesDrawn: (ReaderVisibleReadSnapshot) -> Unit = {},
    readTrackingEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    /** 页眉页脚可选显示书名。 */
    bookTitle: String = ""
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val controlsPalette = readerControlsPalette(palette)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current
    val safeInsets = readerSafeInsets()
    val statusBarPx = safeInsets.topPx
    val navBarPx = safeInsets.bottomPx
    val flingDecay = rememberSplineBasedDecay<Float>()
    val visiblePagesCallback by androidx.compose.runtime.rememberUpdatedState(onVisiblePagesDrawn)
    val trackVisibleRead by androidx.compose.runtime.rememberUpdatedState(readTrackingEnabled)
    var frameTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(readTrackingEnabled) {
        // A stationary frame must be drawn again when returning from a sheet/background.
        if (readTrackingEnabled) frameTick++
    }

    var backgroundTick by remember { mutableIntStateOf(0) }
    var viewport by remember(holder) { mutableStateOf(IntSize(holder.viewWidth, holder.viewHeight)) }
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

    LaunchedEffect(autoRead?.running, autoRead?.generation, enabled) {
        val session = autoRead?.takeIf { it.running && enabled } ?: return@LaunchedEffect
        val token = session.generation
        val navigation = controller.navigationGeneration
        val source = controller.sourceGeneration
        val environment = controller.environmentGeneration
        val clock = AutoReadFrameClock()
        var lastSync = 0L
        var stalledSince: Long? = null
        holder.interruptScroll()
        selection.clear()
        holder.autoScrolling = true
        try {
            while (session.owns(token)) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val delta = clock.distanceDp(now, session.settings.scrollDpPerSecond) * density.density
                    if (!session.owns(token)) return@withFrameNanos
                    if (navigation != controller.navigationGeneration || source != controller.sourceGeneration ||
                        environment != controller.environmentGeneration) {
                        session.pause(AutoReadPauseReason.NAVIGATION)
                        return@withFrameNanos
                    }
                    val beforeChapter = controller.chapterIndex
                    val beforeY = holder.anchorY
                    if (!controller.isReady) {
                        val stalled = stalledSince ?: now.also { stalledSince = it }
                        if (now - stalled >= 30_000_000_000L) session.pause(AutoReadPauseReason.ERROR)
                        return@withFrameNanos
                    }
                    val boundary = holder.applyScroll(delta)
                    if (delta > 0f && beforeChapter == controller.chapterIndex && beforeY == holder.anchorY) {
                        val stalled = stalledSince ?: now.also { stalledSince = it }
                        if (now - stalled >= 30_000_000_000L) session.pause(AutoReadPauseReason.ERROR)
                    } else stalledSince = null
                    if (now - lastSync >= 500_000_000L) {
                        holder.syncPosition()
                        lastSync = now
                    }
                    invalidate()
                    if (boundary == PageTurnDirection.NEXT) session.pause(AutoReadPauseReason.END)
                }
            }
        } finally {
            holder.autoScrolling = false
            if (navigation == controller.navigationGeneration && source == controller.sourceGeneration &&
                environment == controller.environmentGeneration) holder.syncPosition()
            invalidate()
        }
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
    val styleKey = readerRenderStyleKey(settings, palette, density, viewport, statusBarPx, navBarPx, bookTitle = bookTitle)
    remember(styleKey) {
        if (viewport.width > 0 && viewport.height > 0) {
            holder.interruptScroll()
            selection.clear()
            holder.applyStyle(styleKey, createStyle = {
                ReaderPageStyle.resolve(
                    settings, palette, density, viewport.width, viewport.height,
                    statusBarPx, navBarPx, styleKey.environment.columnWidth, bookTitle
                )
            }, onBackgroundReady = {
                invalidate()
                backgroundTick++
            })
            invalidate()
            backgroundTick++
        }
        styleKey
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
            holder.detach()
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
                .pointerInput(enabled, holder, selection, settings.tapZones, settings.englishLearningEnabled) {
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
                                val translation = holder.resolve(upPosition)?.let { hit ->
                                    hit.page.translationAt(hit.local.x, hit.local.y, hit.chapterIndex)
                                }
                                val image = holder.imageAt(upPosition)
                                if (translation != null || image != null) {
                                    selection.clear()
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    if (translation != null) onTranslationLongPress(translation)
                                    else if (image != null) onEpubImageLongPress(image)
                                    while (true) {
                                        val tail = awaitPointerEvent()
                                        tail.changes.forEach { it.consume() }
                                        if (tail.changes.none { it.pressed }) break
                                    }
                                    return@awaitEachGesture
                                }
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
                        if (settings.englishLearningEnabled && settings.wordAnnotationMode == com.mozhi.reader.core.dictionary.WordAnnotationMode.POPUP && !interruptedFling) {
                            holder.englishWordAt(upPosition)?.takeIf { hit -> settings.vocabulary.any {
                                !it.learned && it.word == com.mozhi.reader.core.dictionary.EnglishWords.normalize(hit.word)
                            } }?.let { onDictionaryLookup(it); return@awaitEachGesture }
                        }
                        val pageStep = holder.viewportHeight * PAGE_STEP_FRACTION
                        val customAction = settings.tapZones?.actionAt(upPosition.x, upPosition.y,
                            size.width.toFloat(), size.height.toFloat())
                        if (customAction != null) {
                            when (customAction) {
                                com.mozhi.reader.core.datastore.ReaderTapAction.PREVIOUS_PAGE -> animateScrollBy(-pageStep)
                                com.mozhi.reader.core.datastore.ReaderTapAction.NEXT_PAGE -> animateScrollBy(pageStep)
                                com.mozhi.reader.core.datastore.ReaderTapAction.NONE -> Unit
                                else -> if (!interruptedFling) onTapAction(customAction)
                            }
                            return@awaitEachGesture
                        }
                        val fraction = upPosition.x / size.width.coerceAtLeast(1)
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
                    if (trackVisibleRead) holder.onFrameDrawn({ trackVisibleRead }) { visiblePagesCallback(it) }
                    selection.drawHighlight(this, controlsPalette)
                }
        )

        selection.active?.takeIf { !it.dragging }?.let { active ->
            val toolbarTopPx = selection.toolbarTop(active, density)
            SelectionToolbar(
                palette = controlsPalette,
                topPx = toolbarTopPx,
                extras = settings.selectionToolbarExtras,
                onDictionary = {
                    val text = selection.selectedText()
                    selection.bodyRange()?.let { range ->
                        onDictionaryLookup(com.mozhi.reader.core.dictionary.DictionaryLookupHit(
                            text, controller.contextAround(range), active.chapterIndex, range.first))
                    }
                    selection.clear()
                },
                onParagraphTranslation = {
                    selection.bodyRange()?.let { onParagraphTranslation(active.chapterIndex, it.first) }
                    selection.clear()
                },
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
                onEdit = onEditText?.let { editText ->
                    {
                        val text = selection.selectedText()
                        val range = selection.bodyRange()
                        if (text.isNotBlank() && range != null) editText(text, range)
                        selection.clear()
                    }
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

private const val SCROLL_LONG_PRESS_TIMEOUT_MS = 600L
private const val MIN_FLING_VELOCITY = 320f
/** 点按左右分区时滚动一「屏」的比例，留一点重叠帮助接续。 */
private const val PAGE_STEP_FRACTION = 0.92f
