package com.mozhi.reader.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.prompt.SelectionAiAction
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.ReaderPageLink
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.ReaderPageImage
import com.mozhi.reader.feature.reader.engine.imageAt
import com.mozhi.reader.feature.reader.engine.linkAt
import com.mozhi.reader.feature.reader.engine.ReaderVisibleReadSnapshot
import com.mozhi.reader.feature.reader.engine.isVertical
import com.mozhi.reader.feature.reader.engine.translationAt
import com.mozhi.reader.feature.reader.engine.ReaderParagraphTranslation
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.feature.reader.render.SpreadGeometry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The self-drawn reading surface: renders the three-page window into bitmaps, drives the
 * Legado-style page-turn state machine over them, and hosts long-press text selection.
 * Replaces the Readium WebView host.
 */
@Composable
internal fun ReaderPane(
    controller: ReaderContentController,
    holder: ReaderPaneHolder,
    settings: ReaderSettings,
    palette: ReaderPalette,
    enabled: Boolean,
    registerContentHook: (((Int) -> Unit)?) -> Unit,
    onCenterTap: () -> Unit,
    onTapAction: (com.mozhi.reader.core.datastore.ReaderTapAction) -> Unit = {},
    onDictionaryLookup: (com.mozhi.reader.core.dictionary.DictionaryLookupHit) -> Unit = {},
    onParagraphTranslation: (chapterIndex: Int, offset: Int) -> Unit = { _, _ -> },
    onTranslationLongPress: (ReaderParagraphTranslation) -> Unit = {},
    onAddBookmark: () -> Unit,
    onBoundary: (PageTurnDirection) -> Unit,
    onNotice: (String) -> Unit,
    annotations: List<ReaderAnnotationMark>,
    illustrations: List<ReaderIllustrationMark> = emptyList(),
    transientHighlight: TransientHighlightSpan? = null,
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
    onSpreadModeChanged: (Boolean) -> Unit = {},
    onVisiblePagesDrawn: (ReaderVisibleReadSnapshot) -> Unit = {},
    readTrackingEnabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val controlsPalette = readerControlsPalette(palette)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val bookmarkFeedback = remember { BookmarkPullFeedback() }
    val addBookmark by androidx.compose.runtime.rememberUpdatedState(onAddBookmark)
    val clipboard = LocalClipboardManager.current
    val safeInsets = readerSafeInsets()
    val statusBarPx = safeInsets.topPx
    val navBarPx = safeInsets.bottomPx
    val windowWidthDp = com.mozhi.reader.ui.LocalMoReadWindowWidthDp.current?.value
        ?: LocalConfiguration.current.screenWidthDp.toFloat()

    var frameTick by remember { mutableIntStateOf(0) }
    var backgroundTick by remember { mutableIntStateOf(0) }
    var viewport by remember(holder) { mutableStateOf(IntSize(holder.viewWidth, holder.viewHeight)) }
    val spreadMode = SpreadLayoutPolicy.resolve(
        settings.widePageLayout, viewport.width / density.density, windowWidthDp, settings.pageMode
    )
    val spreadGeometry = if (spreadMode) SpreadGeometry(
        viewport.width.toFloat(), viewport.height.toFloat(), with(density) { SpreadLayoutPolicy.GUTTER_DP.dp.toPx() }
    ) else null
    val visiblePagesCallback by androidx.compose.runtime.rememberUpdatedState(onVisiblePagesDrawn)
    // The first bitmap can be drawn before the entry transition/lifecycle permits read tracking.
    // Enabling the callback alone does not invalidate drawBehind, so the read watermark (and AI
    // notes filtered by it) used to remain stale until a page turn caused another draw.
    LaunchedEffect(readTrackingEnabled) {
        if (readTrackingEnabled) frameTick++
    }
    LaunchedEffect(spreadMode) { onSpreadModeChanged(spreadMode) }
    val selection = remember(controller) {
        ReaderSelectionController(controller, holder) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    val refreshQueue = remember(controller, holder) { PageWindowRefreshQueue() }
    // 竖排（vertical-rl）页从右往左翻：点左侧、向右滑是下一页，动画整体镜像。
    val rightToLeft = { holder.spread == null && (controller.curPage() as? RenderPage.Laid)?.page?.isVertical == true }

    val driver = remember(controller) {
        PageTurnDriver(
            scope = scope,
            callbacks = object : PageTurnDriver.Callbacks {
                override fun hasPage(direction: PageTurnDirection): Boolean =
                    if (direction == PageTurnDirection.NEXT) {
                        controller.hasNextPage()
                    } else {
                        controller.hasPrevPage()
                    }

                override fun fillPage(direction: PageTurnDirection) {
                    holder.prepareTurn(direction)
                    val moved = holder.commitTurn(direction)
                    // A refused commit (e.g. the window shifted mid-animation) must still leave
                    // the bitmaps matching the unchanged position instead of a stale frame.
                    if (!moved) {
                        holder.cancelPreparedTurn()
                        holder.refresh(0)
                        frameTick++
                    }
                    holder.finishFollowTurn(committed = moved)
                }

                override fun onBoundaryHit(direction: PageTurnDirection) = onBoundary(direction)

                override fun onTurnCommitted() {
                    if (holder.followRequest?.automatic != true) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }

                override fun onTurnStarted(direction: PageTurnDirection) {
                    holder.startTurn()
                }

                override fun onTurnFinished() {
                    refreshQueue.finishTurn()?.let { relativePosition ->
                        holder.refresh(relativePosition)
                        frameTick++
                    }
                }
            }
        )
    }
    driver.mirrorProvider = rightToLeft
    driver.mode = when (settings.pageTurnAnimation) {
        // A hinge uses relative travel, including cancellation back to startX; no corner curl.
        PageTurnAnimation.SIMULATION -> if (spreadMode) PageTurnDriver.Mode.FLAT else PageTurnDriver.Mode.SIMULATION
        PageTurnAnimation.COVER, PageTurnAnimation.SLIDE -> PageTurnDriver.Mode.FLAT
        PageTurnAnimation.MODERN_SIMULATION -> if (android.os.Build.VERSION.SDK_INT >= 33) PageTurnDriver.Mode.MODERN_CURL
            else if (spreadMode) PageTurnDriver.Mode.FLAT else PageTurnDriver.Mode.SIMULATION
        PageTurnAnimation.NONE -> PageTurnDriver.Mode.INSTANT
    }

    driver.hingeLeafWidth = spreadGeometry?.leafWidth
        ?.takeIf { settings.pageTurnAnimation == PageTurnAnimation.SIMULATION || settings.pageTurnAnimation == PageTurnAnimation.MODERN_SIMULATION }

    // 位图正被翻页合成器读取时，普通内容/批注/时钟更新等到手势落定后再发布。
    // 唯一例外是 fillPage 已登记的提交刷新：它只重画已经离窗的缓冲，而且必须在
    // fillPage 返回前同步轮换窗口。若也延迟到下一协程，连续第二次翻页会先拿到上一轮
    // 的 cur/next，仿真卷曲便会把新旧两页叠在一起，看起来像“重影”。
    val refreshSafely: (Int) -> Unit = { relativePosition ->
        val requestedPosition = refreshQueue.request(
            turnRunning = driver.isRunning,
            relativePosition = relativePosition,
            hasPreparedTurn = holder.hasPreparedTurn()
        )
        if (requestedPosition != null) {
            holder.refresh(requestedPosition)
            frameTick++
        }
    }

    LaunchedEffect(pageTurnRequest?.sequence) {
        val request = pageTurnRequest ?: return@LaunchedEffect
        if (enabled) {
            driver.finishActiveTurn()
            holder.followRequest = request.takeIf { it.focusTarget != null || it.onFinished != null }
            if (holder.followWasSuperseded()) {
                holder.finishFollowTurn()
            } else if (request.focusTarget?.let { controller.isDisplaying(it.first, it.second) } == true) {
                holder.finishFollowTurn(committed = true)
            } else {
                driver.turnByTap(request.direction)
                if (!driver.isRunning) holder.finishFollowTurn()
            }
        } else request.onFinished?.invoke(ReaderTurnResult.CANCELLED)
    }

    LaunchedEffect(autoRead?.running, autoRead?.generation, enabled) {
        val session = autoRead?.takeIf { it.running && enabled } ?: return@LaunchedEffect
        val token = session.generation
        val navigation = controller.navigationGeneration
        val source = controller.sourceGeneration
        val environment = controller.environmentGeneration
        fun stillCurrent(): Boolean {
            if (navigation != controller.navigationGeneration || source != controller.sourceGeneration ||
                environment != controller.environmentGeneration) session.pause(AutoReadPauseReason.NAVIGATION)
            return session.owns(token)
        }
        selection.clear()
        try {
            runAutoReadPaging(
                session,
                isReady = { stillCurrent() && controller.isReady && !driver.isRunning },
                hasNext = controller::hasNextPage,
                nextIsReady = { stillCurrent() && controller.turnPages(1).firstOrNull() is RenderPage.Laid },
                turn = {
                    val result = kotlinx.coroutines.CompletableDeferred<ReaderTurnResult>()
                    holder.followRequest = ReaderPageTurnRequest(
                        sequence = token,
                        direction = PageTurnDirection.NEXT,
                        navigationGeneration = controller.navigationGeneration,
                        sourceGeneration = controller.sourceGeneration,
                        onFinished = { result.complete(it) },
                        automatic = true,
                        isValid = { stillCurrent() }
                    )
                    driver.turnByTap(PageTurnDirection.NEXT)
                    if (!driver.isRunning) holder.finishFollowTurn()
                    result.await()
                }
            )
        } finally {
            if (holder.followRequest?.automatic == true) {
                driver.cancelActiveTurn()
                holder.finishFollowTurn()
                holder.refresh(0)
                frameTick++
            }
        }
    }

    // Environment: relayout when the typography inputs change; repaint when only colors change.
    val styleKey = readerRenderStyleKey(
        settings, palette, density, viewport, statusBarPx, navBarPx, spreadGeometry
    )
    val environment = styleKey.environment
    remember(styleKey) {
        if (viewport.width > 0 && viewport.height > 0) {
            driver.cancelActiveTurn()
            holder.finishFollowTurn()
            selection.clear()
            val relayout = holder.applyStyle(
                key = styleKey,
                createStyle = {
                    ReaderPageStyle.resolve(
                        settings = settings, palette = palette, density = density,
                        viewWidth = environment.leafWidth, viewHeight = viewport.height,
                        statusBarPx = statusBarPx, navigationBarPx = navBarPx,
                        columnWidthPx = environment.columnWidth
                    )
                },
                spread = spreadGeometry,
                includeBackgroundInPages = settings.pageTurnAnimation.usesEmbeddedPageBackground(),
                // 背景图在后台合成，落地后只刷新真正把纸面烘进位图的仿真模式。
                // 平移/覆盖模式的纸面是独立静态层；重画正在翻动的透明页会短暂露底，
                // 正是自定义背景下偶发“闪一下”的来源。
                onBackgroundReady = {
                    scope.launch {
                        // Never swap the static paper or its embedded snapshot under an active
                        // gesture. Wait for commit/cancel, then publish both layers in one frame.
                        while (driver.isRunning) delay(BACKGROUND_SWAP_POLL_MS)
                        if (holder.embedsBackgroundInPages()) {
                            holder.refresh(0)
                            frameTick++
                        }
                        backgroundTick++
                    }
                }
            )
            if (!relayout) holder.refresh(0)
            frameTick++
            backgroundTick++
        }
        environment
    }

    // 动画模式切换只改变页面快照是否嵌背景，不重建/重解码 BackgroundProvider。
    LaunchedEffect(settings.pageTurnAnimation) {
        driver.cancelActiveTurn()
        holder.finishFollowTurn()
        selection.clear()
        if (holder.setIncludeBackgroundInPages(
                settings.pageTurnAnimation.usesEmbeddedPageBackground()
            )
        ) {
            holder.refresh(0)
            frameTick++
        }
    }

    LaunchedEffect(annotations) {
        if (holder.setAnnotations(annotations)) refreshSafely(0)
    }
    LaunchedEffect(illustrations) {
        if (holder.setIllustrations(illustrations)) refreshSafely(0)
    }

    // 听书当前句变化时重绘页面位图，让底色跟随朗读进度。
    LaunchedEffect(transientHighlight) {
        if (holder.setTransientHighlight(transientHighlight)) refreshSafely(0)
    }

    DisposableEffect(controller) {
        registerContentHook { relativePosition ->
            if (relativePosition == 0 && !selection.consumePreservedPageChange()) selection.clear()
            refreshSafely(relativePosition)
        }
        onDispose {
            registerContentHook(null)
            driver.cancelActiveTurn()
            // The reader entry still owns this cache while chat is on top. Drop UI callbacks,
            // not full-screen bitmaps/typesetting; ViewModel.onCleared releases the resources.
            holder.detach()
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                val changed = when (intent?.action) {
                    Intent.ACTION_TIME_TICK -> holder.updateTime()
                    Intent.ACTION_BATTERY_CHANGED -> holder.updateBattery(intent)
                    else -> false
                }
                if (changed) refreshSafely(0)
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
        // 静态背景是独立兄弟层；翻页/滚动帧只让内容层失效，不再重录背景绘制。
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
                    // The driver is new after navigation even when the retained cache has this size.
                    driver.setViewport(size.width.toFloat(), size.height.toFloat())
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
                .readerPageTouch(
                    enabled = enabled,
                    driver = driver,
                    selection = selection,
                    onImageLongPress = { position ->
                        holder.hitAt(position)?.let { hit ->
                            hit.page.page.translationAt(hit.local.x, hit.local.y, hit.page.chapterIndex)
                        }?.let { translation ->
                            selection.clear()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onTranslationLongPress(translation)
                            true
                        } ?: holder.imageAt(position)?.let { image ->
                            selection.clear()
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEpubImageLongPress(image)
                            true
                        } ?: false
                    },
                    onBookmarkPull = { progress ->
                        if (progress >= 1f && bookmarkFeedback.progress < 1f) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        bookmarkFeedback.progress = progress
                    },
                    onAddBookmark = { addBookmark() }
                ) { position, fromAbort ->
                    val illustrationIds = holder.illustrationIdsAt(position)
                    if (illustrationIds.isNotEmpty()) {
                        selection.clear()
                        onIllustrationClick(illustrationIds)
                        return@readerPageTouch
                    }
                    val annotationIds = holder.annotationIdsAt(position)
                    if (annotationIds.isNotEmpty()) {
                        selection.clear()
                        onAnnotationClick(annotationIds)
                        return@readerPageTouch
                    }
                    holder.linkAt(position)?.let { link ->
                        selection.clear()
                        onLinkClick(link)
                        return@readerPageTouch
                    }
                    if (settings.englishLearningEnabled && settings.wordAnnotationMode == com.mozhi.reader.core.dictionary.WordAnnotationMode.POPUP && !fromAbort) {
                        holder.englishWordAt(position)?.takeIf { hit -> settings.vocabulary.any {
                            !it.learned && it.word == com.mozhi.reader.core.dictionary.EnglishWords.normalize(hit.word)
                        } }?.let { onDictionaryLookup(it); return@readerPageTouch }
                    }
                    val customAction = settings.tapZones?.actionAt(position.x, position.y,
                        holder.viewWidth.toFloat(), holder.viewHeight.toFloat())
                    if (customAction != null) {
                        when (customAction) {
                            com.mozhi.reader.core.datastore.ReaderTapAction.PREVIOUS_PAGE -> driver.turnByTap(PageTurnDirection.PREVIOUS)
                            com.mozhi.reader.core.datastore.ReaderTapAction.NEXT_PAGE -> driver.turnByTap(PageTurnDirection.NEXT)
                            com.mozhi.reader.core.datastore.ReaderTapAction.NONE -> Unit
                            else -> if (!fromAbort) onTapAction(customAction)
                        }
                        return@readerPageTouch
                    }
                    val tapDirection = readerTapDirection(
                        if (rightToLeft()) holder.viewWidth - position.x else position.x,
                        holder.viewWidth.toFloat(), holder.spread,
                        minimumChromeWidthPx = with(density) { SpreadLayoutPolicy.CHROME_TOUCH_DP.dp.toPx() }
                    )
                    if (tapDirection != null) driver.turnByTap(tapDirection)
                    // Legado suppresses only the chrome action after an aborted settle.
                    else if (!fromAbort) onCenterTap()
                }
                .drawBehind {
                    frameTick // draw-phase read: content/style changes invalidate this scope
                    val canvas = drawContext.canvas.nativeCanvas
                    val direction = driver.direction
                    val animation = settings.pageTurnAnimation
                    val front = holder.bitmapFor(direction, front = true)
                    val under = holder.bitmapFor(direction, front = false)
                    if (
                        driver.isRunning &&
                        direction != null &&
                        animation != PageTurnAnimation.NONE &&
                        front != null &&
                        under != null
                    ) {
                        val mirrored = driver.mirrored
                        val saved = canvas.save()
                        if (mirrored) canvas.scale(-1f, 1f, size.width / 2f, 0f)
                        holder.compositor.draw(
                            canvas = canvas,
                            animation = animation,
                            direction = direction,
                            // 镜像空间里合成：页图预先翻转一次，屏幕上的字就是正的。
                            front = if (mirrored) holder.mirrored(front, slot = 0) else front,
                            under = if (mirrored) holder.mirrored(under, slot = 1) else under,
                            touchX = driver.touchX,
                            touchY = driver.touchY,
                            startX = driver.startX,
                            cornerAtTop = driver.cornerAtTop,
                            width = size.width,
                            height = size.height,
                            backgroundColor = holder.backgroundColor,
                            spread = holder.spread,
                            startY = driver.startY,
                            modernBackTextOpacity = settings.modernBackTextOpacity,
                            modernRadiusScale = settings.modernCurlRadiusScale,
                            curlX = driver.curlX,
                            curlY = driver.curlY
                        )
                        canvas.restoreToCount(saved)
                    } else {
                        holder.curBitmap?.takeUnless(Bitmap::isRecycled)?.let {
                            canvas.drawBitmap(it, 0f, 0f, null)
                            if (readTrackingEnabled) {
                                holder.onCurrentBitmapDrawn(
                                    isSettled = { !driver.isRunning && readTrackingEnabled },
                                    onVisible = { visiblePagesCallback(it) }
                                )
                            }
                        }
                        selection.drawHighlight(this, controlsPalette)
                    }
                }
        )

        BookmarkPullIndicator(
            feedback = bookmarkFeedback,
            palette = controlsPalette,
            modifier = Modifier.align(Alignment.TopEnd).padding(
                top = with(density) { statusBarPx.toDp() }, end = 20.dp
            )
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

/** 点击分区：左右侧翻页/滚屏、中央呼出章节 chrome；翻页面与滚动面共用。 */
internal const val PREV_TAP_ZONE = 0.28f
internal const val NEXT_TAP_ZONE = 0.72f

/** 单调序号确保连续按同一枚音量键也会触发 Compose effect。 */
data class ReaderPageTurnRequest(
    val sequence: Int,
    val direction: PageTurnDirection,
    val focusTarget: Pair<Int, Int>? = null,
    val navigationGeneration: Int? = null,
    val sourceGeneration: Int? = null,
    val onFinished: ((ReaderTurnResult) -> Unit)? = null,
    val automatic: Boolean = false,
    val isValid: (() -> Boolean)? = null
)

/** Only inputs that affect typography/ink participate in the retained render cache. */
internal fun readerRenderStyleKey(
    settings: ReaderSettings,
    palette: ReaderPalette,
    density: Density,
    viewport: IntSize,
    statusBarPx: Float,
    navigationBarPx: Float,
    spread: SpreadGeometry? = null
): ReaderRenderStyleKey {
    val environment = ReaderEnvironmentKey(
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
            settings.titleStyle,
            settings.imageLibrary,
            settings.textJustification,
            settings.showHeader,
            settings.showFooter,
            settings.headerMarginTop,
            settings.footerMarginBottom
        ).hashCode(),
        syntaxHighlightEnabled = settings.syntaxHighlightEnabled,
        syntaxRulesHash = listOf(settings.syntaxHighlightRules, settings.englishBionicEnabled, settings.englishLearningEnabled,
            settings.wordAnnotationMode, settings.vocabulary.map { listOf(it.word, it.learned, it.gloss, it.phonetic) }).hashCode(),
        fontLibraryHash = settings.fontLibrary.hashCode(),
        width = viewport.width,
        height = viewport.height,
        spread = spread != null,
        leafWidth = spread?.leafWidth?.toInt() ?: viewport.width,
        columnWidth = spread?.leafWidth ?:
            with(density) { SpreadLayoutPolicy.singleColumnWidthDp(viewport.width / density.density).dp.toPx() },
        density = density.density,
        systemFontScale = density.fontScale,
        statusBarPx = statusBarPx,
        navigationBarPx = navigationBarPx
    )
    return ReaderRenderStyleKey(
        environment, palette, settings.backgroundImagePath, settings.backgroundImageOpacity,
        paperGrain = settings.activeCustomThemeId == null &&
            settings.theme == com.mozhi.reader.core.datastore.ReaderTheme.PAPER,
        preferReaderBackground = settings.backgroundImagePath != null || settings.activeCustomThemeId != null
    )
}

internal data class ReaderEnvironmentKey(
    val fontScale: Float,
    val font: ReaderFont,
    val customFontPath: String?,
    val lineHeight: Float,
    val publisherStyleMode: com.mozhi.reader.core.datastore.PublisherStyleMode,
    val pageMarginsHash: Int,
    val advancedTypographyHash: Int,
    val syntaxHighlightEnabled: Boolean,
    val syntaxRulesHash: Int,
    val fontLibraryHash: Int,
    val width: Int,
    val height: Int,
    val spread: Boolean,
    val leafWidth: Int,
    val columnWidth: Float,
    val density: Float,
    val systemFontScale: Float,
    val statusBarPx: Float,
    val navigationBarPx: Float
)

internal data class ReaderRenderStyleKey(
    val environment: ReaderEnvironmentKey,
    val palette: ReaderPalette,
    val backgroundImagePath: String?,
    val backgroundImageOpacity: Float,
    val paperGrain: Boolean,
    val preferReaderBackground: Boolean
)

/** Owns the page bitmaps and the render pipeline; all mutation happens on the main thread. */
private const val BACKGROUND_SWAP_POLL_MS = 16L
