package com.mozhi.reader.feature.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.magnifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.ai.prompt.SelectionAiAction
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.engine.TransientHighlightSpan
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.ReaderContentController
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.SelectionRect
import com.mozhi.reader.feature.reader.engine.TextPos
import com.mozhi.reader.feature.reader.engine.annotationGeometry
import com.mozhi.reader.feature.reader.engine.dragSelectionHandle
import com.mozhi.reader.feature.reader.engine.hitTextPos
import com.mozhi.reader.feature.reader.engine.inlineMarkerLayout
import com.mozhi.reader.feature.reader.engine.selectionBodyRange
import com.mozhi.reader.feature.reader.engine.selectionRects
import com.mozhi.reader.feature.reader.engine.textPosAtBodyOffset
import com.mozhi.reader.feature.reader.engine.wordSelectionAt
import com.mozhi.reader.feature.reader.render.PageBitmapRenderer
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The self-drawn reading surface: renders the three-page window into bitmaps, drives the
 * Legado-style page-turn state machine over them, and hosts long-press text selection.
 * Replaces the Readium WebView host.
 */
@Composable
fun ReaderPane(
    controller: ReaderContentController,
    settings: ReaderSettings,
    palette: ReaderPalette,
    enabled: Boolean,
    registerContentHook: (((Int) -> Unit)?) -> Unit,
    onCenterTap: () -> Unit,
    onBoundary: (PageTurnDirection) -> Unit,
    onNotice: (String) -> Unit,
    annotations: List<ReaderAnnotationMark>,
    illustrations: List<ReaderIllustrationMark> = emptyList(),
    transientHighlight: TransientHighlightSpan? = null,
    onAiAction: (action: SelectionAiAction, selection: String, context: String) -> Unit,
    onAnnotationAction: (selection: String, range: IntRange, anchorTopPx: Int) -> Unit,
    onAnnotationClick: (annotationIds: List<Long>) -> Unit,
    onIllustrationClick: (illustrationIds: List<Long>) -> Unit = {},
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

    var frameTick by remember { mutableIntStateOf(0) }
    var backgroundTick by remember { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val holder = remember(controller) { ReaderPaneHolder(controller) }
    val selection = remember(controller) {
        ReaderSelectionController(controller, holder) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

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
                    val moved = if (direction == PageTurnDirection.NEXT) {
                        controller.moveToNextPage()
                    } else {
                        controller.moveToPrevPage()
                    }
                    // A refused commit (e.g. the window shifted mid-animation) must still leave
                    // the bitmaps matching the unchanged position instead of a stale frame.
                    if (!moved) {
                        holder.cancelPreparedTurn()
                        holder.refresh(0)
                        frameTick++
                    }
                }

                override fun onBoundaryHit(direction: PageTurnDirection) = onBoundary(direction)

                override fun onTurnCommitted() {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }

                override fun onTurnStarted(direction: PageTurnDirection) {
                    holder.ensureFresh()
                }
            }
        )
    }
    driver.mode = when (settings.pageTurnAnimation) {
        PageTurnAnimation.SIMULATION -> PageTurnDriver.Mode.SIMULATION
        PageTurnAnimation.COVER, PageTurnAnimation.SLIDE -> PageTurnDriver.Mode.FLAT
        PageTurnAnimation.NONE -> PageTurnDriver.Mode.INSTANT
    }

    LaunchedEffect(pageTurnRequest?.sequence) {
        val request = pageTurnRequest ?: return@LaunchedEffect
        if (enabled) driver.turnByTap(request.direction)
    }

    // Environment: relayout when the typography inputs change; repaint when only colors change.
    val environment = ReaderEnvironmentKey(
        fontScale = settings.fontScale,
        font = settings.font,
        customFontPath = settings.customFontPath,
        lineHeight = settings.lineHeight,
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
            driver.cancelActiveTurn()
            selection.clear()
            val relayout = holder.applyStyle(
                style = style,
                environment = environment,
                includeBackgroundInPages = settings.pageTurnAnimation.usesEmbeddedPageBackground(),
                // 背景图在后台合成，落地后才重画页面位图——首帧不等它。
                onBackgroundReady = {
                    holder.refresh(0)
                    frameTick++
                    backgroundTick++
                }
            )
            if (relayout) {
                controller.updateEnvironment(style.spec, style.measure)
            } else {
                holder.refresh(0)
            }
            frameTick++
            backgroundTick++
        }
        environment
    }

    // 动画模式切换只改变页面快照是否嵌背景，不重建/重解码 BackgroundProvider。
    LaunchedEffect(settings.pageTurnAnimation) {
        driver.cancelActiveTurn()
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
        if (holder.setAnnotations(annotations)) {
            holder.refresh(0)
            frameTick++
        }
    }
    LaunchedEffect(illustrations) {
        if (holder.setIllustrations(illustrations)) {
            holder.refresh(0)
            frameTick++
        }
    }

    // 听书当前句变化时重绘页面位图，让底色跟随朗读进度。
    LaunchedEffect(transientHighlight) {
        if (holder.setTransientHighlight(transientHighlight)) {
            holder.refresh(0)
            frameTick++
        }
    }

    DisposableEffect(controller) {
        registerContentHook { relativePosition ->
            if (relativePosition == 0 && !selection.consumePreservedPageChange()) selection.clear()
            holder.refresh(relativePosition)
            frameTick++
        }
        onDispose {
            registerContentHook(null)
            driver.cancelActiveTurn()
            holder.release()
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
                if (changed) {
                    holder.refresh(0)
                    frameTick++
                }
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
                    if (holder.setViewport(size.width, size.height)) {
                        driver.setViewport(size.width.toFloat(), size.height.toFloat())
                        viewport = size
                    }
                }
                .magnifier(
                    sourceCenter = { selection.magnifierCenter ?: Offset.Unspecified },
                    magnifierCenter = {
                        selection.magnifierCenter?.let { center ->
                            Offset(center.x, center.y - 72.dp.toPx())
                        } ?: Offset.Unspecified
                    },
                    zoom = 1.8f
                )
                .readerPageTouch(
                    enabled = enabled,
                    driver = driver,
                    selection = selection
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
                    val fraction = position.x / holder.viewWidth.coerceAtLeast(1)
                    when {
                        fraction < PREV_TAP_ZONE -> driver.turnByTap(PageTurnDirection.PREVIOUS)
                        fraction > NEXT_TAP_ZONE -> driver.turnByTap(PageTurnDirection.NEXT)
                        // Legado suppresses only the center action after an aborted settle.
                        !fromAbort -> onCenterTap()
                        else -> Unit
                    }
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
                        holder.compositor.draw(
                            canvas = canvas,
                            animation = animation,
                            direction = direction,
                            front = front,
                            under = under,
                            touchX = driver.touchX,
                            touchY = driver.touchY,
                            startX = driver.startX,
                            cornerAtTop = driver.cornerAtTop,
                            width = size.width,
                            height = size.height,
                            backgroundColor = holder.backgroundColor,
                            darkTheme = palette.isDark
                        )
                    } else {
                        holder.curBitmap?.takeUnless(Bitmap::isRecycled)?.let {
                            canvas.drawBitmap(it, 0f, 0f, null)
                        }
                        selection.drawHighlight(this, palette)
                    }
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

@Composable
internal fun BoxScope.SelectionToolbar(
    palette: ReaderPalette,
    topPx: Int,
    onAi: (SelectionAiAction) -> Unit,
    onAnnotation: () -> Unit,
    onTts: () -> Unit,
    onImage: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth(0.96f)
            .offset { IntOffset(0, topPx) },
        shape = RoundedCornerShape(17.dp),
        color = palette.glassStrong,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 5.dp)
        ) {
            SelectionAiAction.entries.forEach { action ->
                SelectionToolItem(
                    icon = action.toolbarIcon(),
                    label = action.label,
                    iconTint = palette.accent,
                    palette = palette
                ) { onAi(action) }
            }
            SelectionToolItem(Icons.Outlined.BorderColor, "划线", palette.accent, palette, onAnnotation)
            SelectionToolItem(Icons.Outlined.Headphones, "朗读", palette.accent, palette, onTts)
            SelectionToolItem(Icons.Outlined.Image, "生图", palette.accent, palette, onImage)
            SelectionToolItem(Icons.Outlined.Edit, "编辑", palette.accent, palette, onEdit)
            SelectionToolItem(Icons.Outlined.ContentCopy, "复制", palette.onBackground, palette, onCopy)
            SelectionToolItem(Icons.Outlined.Close, "取消", palette.muted, palette, onDismiss)
        }
    }
}

/** 图标在上、小字在下的选区操作项，替代早期的纯文字按钮。 */
@Composable
private fun SelectionToolItem(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = iconTint,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = palette.muted
        )
    }
}

private fun SelectionAiAction.toolbarIcon(): ImageVector = when (this) {
    SelectionAiAction.TRANSLATE -> Icons.Outlined.Translate
    SelectionAiAction.ANALYZE -> Icons.Outlined.Psychology
    SelectionAiAction.ASK -> Icons.AutoMirrored.Outlined.HelpOutline
}

/** 点击分区：左右侧翻页/滚屏、中央呼出章节 chrome；翻页面与滚动面共用。 */
internal const val PREV_TAP_ZONE = 0.28f
internal const val NEXT_TAP_ZONE = 0.72f

/** 单调序号确保连续按同一枚音量键也会触发 Compose effect。 */
data class ReaderPageTurnRequest(
    val sequence: Int,
    val direction: PageTurnDirection
)

private data class ReaderEnvironmentKey(
    val fontScale: Float,
    val font: ReaderFont,
    val customFontPath: String?,
    val lineHeight: Float,
    val pageMarginsHash: Int,
    val advancedTypographyHash: Int,
    val syntaxHighlightEnabled: Boolean,
    val syntaxRulesHash: Int,
    val width: Int,
    val height: Int
)

/**
 * Long-press selection over the current page, Legado's `ContentTextView` selection reduced to the
 * single-page case: the long press seeds a word, dragging extends either side of it.
 */
private class ReaderSelectionController(
    private val controller: ReaderContentController,
    private val holder: ReaderPaneHolder,
    private val onSelectionStarted: () -> Unit
) : SelectionGestureHooks {

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
    private var preserveNextPageChange = false
    private var lastAutoTurnAt = 0L

    override val isActive: Boolean get() = active != null

    override fun begin(position: Offset): Boolean {
        val laid = controller.curPage() as? RenderPage.Laid ?: return false
        val local = holder.toContentLocal(position) ?: return false
        val hit = laid.page.hitTextPos(local.x, local.y, exact = true) ?: return false
        val word = laid.page.wordSelectionAt(hit)
        val range = laid.page.selectionBodyRange(word.first, word.second)
        draggedHandle = null
        active = ActiveSelection(
            chapterIndex = laid.chapterIndex,
            anchorStartOffset = range.first,
            anchorEndOffset = range.last,
            startOffset = range.first,
            endOffset = range.last,
            dragging = true
        )
        magnifierCenter = position
        onSelectionStarted()
        return true
    }

    override fun grabHandle(position: Offset, radiusPx: Float): Boolean {
        val current = active ?: return false
        val laid = controller.curPage() as? RenderPage.Laid ?: return false
        if (laid.chapterIndex != current.chapterIndex) return false
        val origin = holder.contentOrigin() ?: return false
        val startCenter = endpointCenter(laid.page, current.startOffset, origin, startSide = true)
        val endCenter = endpointCenter(laid.page, current.endOffset, origin, startSide = false)
        val startDistance = startCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        val endDistance = endCenter?.let { (position - it).getDistance() } ?: Float.MAX_VALUE
        draggedHandle = when {
            startDistance > radiusPx && endDistance > radiusPx -> return false
            startDistance <= endDistance -> Handle.START
            else -> Handle.END
        }
        magnifierCenter = position
        active = current.copy(dragging = true)
        return true
    }

    override fun drag(position: Offset) {
        magnifierCenter = position
        val current = active ?: return
        if (autoTurnAtEdge(position, current)) return
        val laid = controller.curPage() as? RenderPage.Laid ?: return
        if (laid.chapterIndex != current.chapterIndex) return
        val local = holder.toContentLocal(position) ?: return
        val hit = laid.page.hitTextPos(local.x, local.y, exact = false) ?: return
        val hitRange = laid.page.selectionBodyRange(hit, hit)
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
        }
    }

    private fun autoTurnAtEdge(position: Offset, current: ActiveSelection): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastAutoTurnAt < AUTO_TURN_COOLDOWN_MS) return false
        val edge = holder.viewHeight * AUTO_TURN_EDGE_FRACTION
        val direction = when {
            position.y <= edge -> PageTurnDirection.PREVIOUS
            position.y >= holder.viewHeight - edge -> PageTurnDirection.NEXT
            else -> return false
        }
        val candidate = when (direction) {
            PageTurnDirection.PREVIOUS -> controller.prevPage()
            PageTurnDirection.NEXT -> controller.nextPage()
        } as? RenderPage.Laid ?: return false
        if (candidate.chapterIndex != current.chapterIndex) return false
        preserveNextPageChange = true
        val moved = when (direction) {
            PageTurnDirection.PREVIOUS -> controller.moveToPrevPage()
            PageTurnDirection.NEXT -> controller.moveToNextPage()
        }
        if (!moved) preserveNextPageChange = false
        lastAutoTurnAt = now
        return moved
    }

    fun consumePreservedPageChange(): Boolean {
        val preserve = preserveNextPageChange
        preserveNextPageChange = false
        return preserve
    }

    override fun end() {
        draggedHandle = null
        magnifierCenter = null
        active = active?.copy(dragging = false)
    }

    override fun clear() {
        draggedHandle = null
        magnifierCenter = null
        active = null
    }

    fun selectedText(): String {
        val current = active ?: return ""
        val body = controller.chapterBody(current.chapterIndex) ?: return ""
        val start = current.startOffset.coerceIn(0, body.length)
        val endExclusive = (current.endOffset + 1).coerceIn(start, body.length)
        return body.substring(start, endExclusive)
            .replace('\uFFFC'.toString(), "［图片］")
    }

    fun bodyRange(): IntRange? = active?.bodyRange

    fun drawHighlight(drawScope: DrawScope, palette: ReaderPalette) {
        val current = active ?: return
        val laid = controller.curPage() as? RenderPage.Laid ?: return
        if (laid.chapterIndex != current.chapterIndex) return
        val origin = holder.contentOrigin() ?: return
        val rects = laid.page.selectionRects(current.bodyRange)
        val color = palette.accent.copy(alpha = if (palette.isDark) 0.18f else 0.30f)
        for (rect in rects) {
            drawScope.drawRect(
                color = color,
                topLeft = Offset(origin.x + rect.left, origin.y + rect.top),
                size = Size(rect.right - rect.left, rect.bottom - rect.top)
            )
        }
        endpointCenter(laid.page, current.startOffset, origin, startSide = true)?.let { center ->
            val pos = laid.page.textPosAtBodyOffset(current.startOffset) ?: return@let
            val line = laid.page.lines[pos.lineIndex]
            drawHandle(drawScope, palette, center.x, origin.y + line.lineTop, origin.y + line.lineBottom)
        }
        endpointCenter(laid.page, current.endOffset, origin, startSide = false)?.let { center ->
            val pos = laid.page.textPosAtBodyOffset(current.endOffset) ?: return@let
            val line = laid.page.lines[pos.lineIndex]
            drawHandle(drawScope, palette, center.x, origin.y + line.lineTop, origin.y + line.lineBottom)
        }
    }

    private fun endpointCenter(
        page: com.mozhi.reader.feature.reader.engine.TextPage,
        offset: Int,
        origin: Offset,
        startSide: Boolean
    ): Offset? {
        val pageEnd = page.chapterPosition + page.charLength
        if (offset !in page.chapterPosition until pageEnd) return null
        val pos = page.textPosAtBodyOffset(offset) ?: return null
        val line = page.lines[pos.lineIndex]
        val column = line.columns[pos.columnIndex]
        return Offset(origin.x + if (startSide) column.start else column.end, origin.y + line.lineBottom)
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
        val laid = controller.curPage() as? RenderPage.Laid ?: return 0
        val origin = holder.contentOrigin() ?: return 0
        val rects = laid.page.selectionRects(current.bodyRange)
        val gap = with(density) { 12.dp.toPx() }
        val barHeight = with(density) { 48.dp.toPx() }
        val first = rects.firstOrNull() ?: return 0
        val above = origin.y + first.top - gap - barHeight
        if (above > origin.y * 0.4f) return above.toInt()
        return (origin.y + rects.last().bottom + gap).toInt()
    }

    private companion object {
        const val AUTO_TURN_EDGE_FRACTION = 0.08f
        const val AUTO_TURN_COOLDOWN_MS = 420L
    }
}

/** Owns the page bitmaps and the render pipeline; all mutation happens on the main thread. */
private class ReaderPaneHolder(private val controller: ReaderContentController) {
    var viewWidth = 0
        private set
    var viewHeight = 0
        private set
    var backgroundColor: Int = android.graphics.Color.TRANSPARENT
        private set

    val compositor = PageTurnCompositor()

    private var style: ReaderPageStyle? = null
    private var renderer: PageBitmapRenderer? = null
    private var includeBackgroundInPages = true
    private var appliedEnvironment: ReaderEnvironmentKey? = null
    private var timeText: String = timeFormat.format(Date())
    private var batteryPercent: Int = 100
    private var annotations: List<ReaderAnnotationMark> = emptyList()
    private var illustrations: List<ReaderIllustrationMark> = emptyList()
    private var transientHighlight: TransientHighlightSpan? = null
    private var dirty = true
    private var preparedTurn: PageTurnDirection? = null
    /** 邻页渲染已排到下一帧、尚未执行。 */
    private var pendingNeighbors = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    var curBitmap: Bitmap? = null
        private set
    private var nextBitmap: Bitmap? = null
    private var prevBitmap: Bitmap? = null

    fun setViewport(width: Int, height: Int): Boolean {
        if (width == viewWidth && height == viewHeight) return false
        viewWidth = width
        viewHeight = height
        return true
    }

    /** Returns true when the typography environment changed and a relayout is required. */
    fun applyStyle(
        style: ReaderPageStyle,
        environment: ReaderEnvironmentKey,
        includeBackgroundInPages: Boolean,
        onBackgroundReady: () -> Unit
    ): Boolean {
        val relayout = appliedEnvironment != environment
        appliedEnvironment = environment
        this.style = style
        this.includeBackgroundInPages = includeBackgroundInPages
        backgroundColor = style.backgroundColor
        renderer?.release()
        renderer = PageBitmapRenderer(style).also { it.prepareBackground(onBackgroundReady) }
        dirty = true
        return relayout
    }

    fun drawBackground(canvas: android.graphics.Canvas, width: Float, height: Float) {
        renderer?.drawBackdrop(canvas, width, height) ?: canvas.drawColor(backgroundColor)
    }

    fun setIncludeBackgroundInPages(value: Boolean): Boolean {
        if (includeBackgroundInPages == value) return false
        includeBackgroundInPages = value
        dirty = true
        return true
    }

    fun setAnnotations(value: List<ReaderAnnotationMark>): Boolean {
        if (annotations == value) return false
        annotations = value
        dirty = true
        return true
    }

    fun setIllustrations(value: List<ReaderIllustrationMark>): Boolean {
        if (illustrations == value) return false
        illustrations = value
        dirty = true
        return true
    }

    fun setTransientHighlight(value: TransientHighlightSpan?): Boolean {
        if (transientHighlight == value) return false
        transientHighlight = value
        dirty = true
        return true
    }

    /** Top-left of the typeset content area in view coordinates, or null before the first style. */
    fun contentOrigin(): Offset? = style?.let { Offset(it.paddingLeft, it.contentTop) }

    fun annotationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val page = controller.curPage() as? RenderPage.Laid ?: return emptyList()
        val local = toContentLocal(position) ?: return emptyList()
        // 与 PageBitmapRenderer.drawBody 的 marker 参数保持一致，否则点击热区和画出的圆点对不上
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val geometry = page.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
            maxRight = currentStyle.contentWidth
        )
        val hitRadius = (currentStyle.tipSizePx * 1.35f).coerceAtLeast(18f)
        geometry.markers.firstOrNull { marker ->
            if (marker.annotationIds.isEmpty()) return@firstOrNull false
            val dx = local.x - marker.centerX
            val dy = local.y - marker.centerY
            dx * dx + dy * dy <= hitRadius * hitRadius
        }?.let { return it.annotationIds }
        // 纯高亮没有「评」圆点，划线区域本身也可点开讨论串
        return geometry.highlights
            .filter { rect ->
                local.x in rect.left..rect.right && local.y in rect.top..rect.bottom
            }
            .map { it.annotationId }
            .distinct()
    }

    fun illustrationIdsAt(position: Offset): List<Long> {
        val currentStyle = style ?: return emptyList()
        val page = controller.curPage() as? RenderPage.Laid ?: return emptyList()
        val local = toContentLocal(position) ?: return emptyList()
        val markerRadius = (currentStyle.tipSizePx * 0.72f).coerceAtLeast(8f)
        val markers = page.page.inlineMarkerLayout(
            annotations = annotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            markerRadius = markerRadius,
            markerGap = markerRadius * com.mozhi.reader.feature.reader.engine.ANNOTATION_MARKER_GAP_RATIO,
            maxRight = currentStyle.contentWidth
        )
        val hitRadius = (currentStyle.tipSizePx * 1.35f).coerceAtLeast(18f)
        return markers.markers.firstOrNull { marker ->
            if (marker.illustrationIds.isEmpty()) return@firstOrNull false
            val dx = local.x - marker.centerX
            val dy = local.y - marker.centerY
            dx * dx + dy * dy <= hitRadius * hitRadius
        }?.illustrationIds.orEmpty()
    }

    fun toContentLocal(position: Offset): Offset? {
        val origin = contentOrigin() ?: return null
        return Offset(position.x - origin.x, position.y - origin.y)
    }

    fun bitmapFor(direction: PageTurnDirection?, front: Boolean): Bitmap? = when (direction) {
        PageTurnDirection.NEXT -> if (front) curBitmap else nextBitmap
        PageTurnDirection.PREVIOUS -> if (front) prevBitmap else curBitmap
        null -> curBitmap
    }?.takeUnless(Bitmap::isRecycled)

    fun ensureFresh() {
        if (dirty) refresh(0)
        // 翻页真要用到邻页了，把推迟的那两张立刻补上。
        if (pendingNeighbors) renderNeighborsNow()
    }

    fun prepareTurn(direction: PageTurnDirection) {
        preparedTurn = direction
    }

    fun cancelPreparedTurn() {
        preparedTurn = null
    }

    fun refresh(relativePosition: Int) {
        val renderer = renderer ?: return
        val turn = preparedTurn
        if (relativePosition == 0 && turn != null) {
            preparedTurn = null
            rotateAndRenderNeighbor(renderer, turn)
            dirty = false
            return
        }
        when (relativePosition) {
            -1 -> prevBitmap = renderPage(renderer, prevBitmap, RelativePage.PREV)
            1 -> nextBitmap = renderPage(renderer, nextBitmap, RelativePage.NEXT)
            else -> {
                curBitmap = renderPage(renderer, curBitmap, RelativePage.CUR)
                // 邻页推到下一帧：一张全屏 ARGB_8888 就是十来兆，进书时三张连着画
                // 必然顶掉首帧。邻页在翻页前不上屏，晚一帧毫无代价。
                scheduleNeighbors()
            }
        }
        dirty = false
    }

    private fun scheduleNeighbors() {
        if (pendingNeighbors) return
        pendingNeighbors = true
        mainHandler.post { if (pendingNeighbors) renderNeighborsNow() }
    }

    private fun renderNeighborsNow() {
        pendingNeighbors = false
        val renderer = renderer ?: return
        nextBitmap = renderPage(renderer, nextBitmap, RelativePage.NEXT)
        prevBitmap = renderPage(renderer, prevBitmap, RelativePage.PREV)
    }

    private fun rotateAndRenderNeighbor(
        renderer: PageBitmapRenderer,
        direction: PageTurnDirection
    ) {
        val rotated = rotatePageWindow(prevBitmap, curBitmap, nextBitmap, direction)
        prevBitmap = rotated.previous
        curBitmap = rotated.current
        nextBitmap = rotated.next
        when (direction) {
            PageTurnDirection.NEXT -> {
                nextBitmap = renderPage(renderer, rotated.reusable, RelativePage.NEXT)
            }
            PageTurnDirection.PREVIOUS -> {
                prevBitmap = renderPage(renderer, rotated.reusable, RelativePage.PREV)
            }
        }
    }

    private enum class RelativePage { PREV, CUR, NEXT }

    private fun renderPage(
        renderer: PageBitmapRenderer,
        into: Bitmap?,
        which: RelativePage
    ): Bitmap {
        val page = when (which) {
            RelativePage.PREV -> controller.prevPage()
            RelativePage.CUR -> controller.curPage()
            RelativePage.NEXT -> controller.nextPage()
        }
        val progress = when (page) {
            is RenderPage.Laid -> controller.progressAt(page.chapterIndex, page.page.chapterPosition)
            else -> controller.bookProgress()
        }
        return renderer.render(
            page = page,
            into = into,
            bookProgress = progress,
            timeText = timeText,
            batteryPercent = batteryPercent,
            annotations = annotations.filter { it.chapterIndex == page.chapterIndex },
            illustrations = illustrations.filter { it.chapterIndex == page.chapterIndex },
            transientHighlight = transientHighlight?.takeIf { it.chapterIndex == page.chapterIndex },
            includeBackground = includeBackgroundInPages
        )
    }

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

    fun release() {
        pendingNeighbors = false
        mainHandler.removeCallbacksAndMessages(null)
        renderer?.release()
        renderer = null
        curBitmap?.recycle()
        nextBitmap?.recycle()
        prevBitmap?.recycle()
        curBitmap = null
        nextBitmap = null
        prevBitmap = null
    }

    private companion object {
        val timeFormat = SimpleDateFormat("HH:mm", Locale.ROOT)
    }
}
