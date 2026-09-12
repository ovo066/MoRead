package com.mozhi.reader.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Legado's `ReadView` + `HorizontalPageDelegate` touch state machine, verbatim in semantics:
 *
 * - The page-turn slop is a squared distance including the Y component.
 * - Once the direction is decided the start point is reset to the crossing point, so the offset
 *   ramps from zero with no jump.
 * - Cancel vs commit looks only at the direction of the very last move relative to the previous
 *   event — no thresholds, no velocity tracker. That single rule is what makes short fast flicks
 *   turn the page and a 1px pull-back cancel it.
 * - At a boundary (no prev/next page) the direction stays unset and the page never moves.
 * - The release settle runs at constant speed (Legado's `Scroller` + `LinearInterpolator`):
 *   duration = 300ms × |remaining Δx| / width.
 * - Data advances only when the animation finishes or is aborted mid-commit ([Callbacks.fillPage]),
 *   which is why the final frame and the freshly filled page are pixel-identical.
 */
@Stable
class PageTurnDriver(
    private val scope: CoroutineScope,
    private val callbacks: Callbacks
) {
    interface Callbacks {
        fun hasPage(direction: PageTurnDirection): Boolean
        /** Advance the window; bitmaps must be fresh when this returns. */
        fun fillPage(direction: PageTurnDirection)
        fun onBoundaryHit(direction: PageTurnDirection)
        fun onTurnCommitted()
        /** Called when the direction is decided, so bitmaps can be captured/refreshed. */
        fun onTurnStarted(direction: PageTurnDirection)
    }

    var viewWidth = 1f
        private set
    var viewHeight = 1f
        private set

    // Frame-driven values: written by the gesture/animation, read only in the draw phase.
    var touchX by mutableFloatStateOf(0.1f)
        private set
    var touchY by mutableFloatStateOf(0.1f)
        private set
    var startX by mutableFloatStateOf(0f)
        private set
    var startY by mutableFloatStateOf(0f)
        private set
    var direction by mutableStateOf<PageTurnDirection?>(null)
        private set
    var isRunning by mutableStateOf(false)
        private set

    /** Simulation corner: always the right edge; top or bottom decided per Legado's rules. */
    var cornerAtTop by mutableStateOf(false)
        private set

    private var isMoved = false
    private var noNext = false
    private var isCancel = false
    private var lastX = 0f
    private var downY = 0f
    private var tapSuppressedByAbort = false
    private var settleJob: Job? = null
    private val settle = Animatable(0f)

    /**
     * SIMULATION settles the absolute touch point to ±width (the curl geometry is absolute);
     * FLAT (cover/slide) settles the offset `touchX - startX` to ±width; INSTANT skips the settle
     * entirely (Legado's no-animation delegate).
     */
    enum class Mode { SIMULATION, FLAT, INSTANT }

    var mode: Mode = Mode.SIMULATION
    /** A spine hinge settles after one leaf width; cover/slide retain full-viewport travel. */
    var hingeLeafWidth: Float? = null
    private val settleWidth: Float get() = hingeLeafWidth?.coerceAtLeast(1f) ?: viewWidth

    fun setViewport(width: Float, height: Float) {
        viewWidth = width.coerceAtLeast(1f)
        viewHeight = height.coerceAtLeast(1f)
    }

    val isAnimating: Boolean get() = settleJob?.isActive == true

    // ---- gesture entry points ----

    fun onDown(x: Float, y: Float) {
        // Legado clears isAbortAnim on every DOWN that doesn't abort anything; abortAnimation()
        // re-arms the flag only when it actually interrupted a live settle.
        tapSuppressedByAbort = false
        abortAnimation()
        isMoved = false
        noNext = false
        isCancel = false
        direction = null
        isRunning = false
        startX = x
        startY = y
        lastX = x
        downY = y
    }

    fun onMove(x: Float, y: Float, touchSlop: Float) {
        if (noNext) return
        if (!isMoved) {
            val deltaX = x - startX
            val deltaY = y - startY
            if (deltaX * deltaX + deltaY * deltaY <= touchSlop * touchSlop) return
            isMoved = true
            val dir = if (deltaX > 0) PageTurnDirection.PREVIOUS else PageTurnDirection.NEXT
            if (!callbacks.hasPage(dir)) {
                noNext = true
                callbacks.onBoundaryHit(dir)
                return
            }
            direction = dir
            resolveCorner(dir)
            callbacks.onTurnStarted(dir)
            // Legado resets the start point to the slop-crossing point: zero-jump engagement.
            startX = x
            startY = y
            lastX = x
        }
        val dir = direction ?: return
        isCancel = if (dir == PageTurnDirection.NEXT) x > lastX else x < lastX
        lastX = x
        isRunning = true
        touchX = x
        touchY = antiJitterY(y, dir)
    }

    fun onUp(): UpResult {
        if (!isMoved) {
            val suppressed = tapSuppressedByAbort
            tapSuppressedByAbort = false
            return if (suppressed) UpResult.ABORT_TAP else UpResult.TAP
        }
        if (direction != null) startSettle(FULL_PAGE_SETTLE_MS) else clearTurn()
        return UpResult.DRAG_END
    }

    enum class UpResult { TAP, ABORT_TAP, DRAG_END }

    // ---- programmatic turns (tap zones / buttons) ----

    fun turnByTap(dir: PageTurnDirection) {
        abortAnimation()
        if (!callbacks.hasPage(dir)) {
            callbacks.onBoundaryHit(dir)
            return
        }
        direction = dir
        isCancel = false
        isMoved = true
        // Legado starts programmatic turns from (0.9w, 0.9h) — or the top for an upper-corner curl.
        val y = if (downY > 0f && downY <= viewHeight / 2) 1f else viewHeight * 0.9f
        startX = if (dir == PageTurnDirection.NEXT) viewWidth * 0.9f else 0f
        startY = if (dir == PageTurnDirection.NEXT) y else viewHeight
        touchX = startX
        touchY = startY
        cornerAtTop = dir == PageTurnDirection.NEXT && y <= 1f
        callbacks.onTurnStarted(dir)
        isRunning = true
        startSettle(FULL_PAGE_SETTLE_MS)
    }

    /** Settle the old request before a caller installs a newer request's completion owner. */
    fun finishActiveTurn() { abortAnimation() }

    fun cancelActiveTurn() {
        settleJob?.cancel()
        settleJob = null
        clearTurn()
    }

    // ---- internals ----

    /**
     * Legado's simulation anti-jitter, with its overwrite order preserved: a next-turn grabbed in
     * the upper-middle band pins to the top edge (matching the top corner); any previous-turn or a
     * middle-third grab pins to the bottom edge so the page slides flat instead of wobbling.
     */
    private fun antiJitterY(y: Float, dir: PageTurnDirection): Float {
        val third = viewHeight / 3
        if (dir == PageTurnDirection.NEXT && downY > third && downY < viewHeight / 2) {
            return 1f
        }
        if (dir == PageTurnDirection.PREVIOUS || (downY > third && downY < third * 2)) {
            return viewHeight
        }
        return y
    }

    private fun resolveCorner(dir: PageTurnDirection) {
        // Legado: previous-page curls never lift a top corner; next-page curls take the corner on
        // the down-point's half. The corner X is always the right edge in absolute space.
        cornerAtTop = dir == PageTurnDirection.NEXT && downY <= viewHeight / 2
        touchY = if (dir == PageTurnDirection.PREVIOUS) viewHeight else downY
    }

    private fun startSettle(speedMillis: Int) {
        val dir = direction ?: return
        val committing = !isCancel
        if (mode == Mode.INSTANT) {
            if (committing) {
                callbacks.onTurnCommitted()
                callbacks.fillPage(dir)
            }
            clearTurn()
            return
        }
        val targetX = settleTargetX(dir, committing)
        val targetY = if (cornerAtTop) 1f else viewHeight
        val fromX = if (hingeLeafWidth != null && mode == Mode.FLAT) {
            clampHingeTouchX(dir, touchX, startX, settleWidth)
        } else touchX
        val fromY = touchY
        val distance = kotlin.math.abs(targetX - fromX)
        val duration = (speedMillis * distance / settleWidth).roundToInt().coerceAtLeast(1)
        if (committing) callbacks.onTurnCommitted()
        settleJob = scope.launch {
            settle.snapTo(0f)
            settle.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = duration, easing = LinearEasing)
            ) {
                touchX = fromX + (targetX - fromX) * value
                touchY = fromY + (targetY - fromY) * value
            }
            if (committing) callbacks.fillPage(dir)
            clearTurn()
        }
    }

    /**
     * Legado `onAnimStart` targets. Simulation animates the absolute touch point: a committed
     * next-turn sends it to -width (sheet fully lifted over), a committed previous-turn to +width
     * (sheet laid flat), and cancelling reverses the endpoints. Flat animations settle the offset
     * `touchX - startX` instead, so their endpoints are relative to the reset start point.
     */
    private fun settleTargetX(dir: PageTurnDirection, committing: Boolean): Float =
        when (mode) {
            Mode.SIMULATION, Mode.INSTANT -> when {
                dir == PageTurnDirection.NEXT && committing -> -viewWidth
                dir == PageTurnDirection.NEXT -> viewWidth
                committing -> viewWidth
                else -> -viewWidth
            }
            Mode.FLAT -> flatPageTurnTargetX(dir, committing, startX, settleWidth)
        }

    /** Legado `abortAnim`: a DOWN during the settle snaps to the end state immediately. */
    private fun abortAnimation() {
        val job = settleJob ?: return
        if (!job.isActive) {
            settleJob = null
            return
        }
        job.cancel()
        settleJob = null
        val dir = direction
        if (!isCancel && dir != null) {
            callbacks.fillPage(dir)
        }
        tapSuppressedByAbort = true
        clearTurn()
    }

    private fun clearTurn() {
        settleJob = null
        isRunning = false
        isMoved = false
        direction = null
        isCancel = false
        noNext = false
    }

    private companion object {
        const val FULL_PAGE_SETTLE_MS = 300
    }
}

/** Selection hooks for [readerPageTouch]; a null hook set disables long-press selection. */
interface SelectionGestureHooks {
    val isActive: Boolean

    /** Returns true when the long press landed on text and selection took the gesture over. */
    fun begin(position: Offset): Boolean

    /**
     * Returns true when a DOWN within [radiusPx] of a selection handle grabbed it; the gesture
     * then feeds [drag]/[end] instead of the page-turn driver.
     */
    fun grabHandle(position: Offset, radiusPx: Float): Boolean
    fun drag(position: Offset)
    fun end()
    fun clear()
}

/**
 * Feeds raw pointer events to the driver, Legado-style: every gesture inside the reader is owned
 * here. Taps fall through to [onTap] with the down position; `fromAbort` mirrors Legado's
 * `isAbortAnim` — a tap that interrupted a settle suppresses only the center action, side-zone
 * turns still fire so rapid tapping never drops a page.
 *
 * Long-press semantics also follow Legado's `ReadView`: 600ms without crossing the page slop
 * enters selection (the drag then extends it); once a selection is showing, a DOWN on either
 * handle drags that edge, while a DOWN anywhere else clears it and that gesture's tap actions
 * are swallowed (`pressOnTextSelected`).
 */
fun Modifier.readerPageTouch(
    enabled: Boolean,
    driver: PageTurnDriver,
    selection: SelectionGestureHooks? = null,
    onBookmarkPull: ((Float) -> Unit)? = null,
    onAddBookmark: (() -> Unit)? = null,
    onTap: (position: Offset, fromAbort: Boolean) -> Unit
): Modifier = pointerInput(enabled, driver, selection, onAddBookmark != null) {
    if (!enabled) return@pointerInput
    val slop = viewConfiguration.touchSlop
    val handleGrabRadius = 24.dp.toPx()
    val bookmarkDistance = 144.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown()
        driver.setViewport(size.width.toFloat(), size.height.toFloat())
        val hadSelection = selection?.isActive == true
        var selecting = false
        if (hadSelection) {
            selecting = selection?.grabHandle(down.position, handleGrabRadius) == true
            if (!selecting) selection?.clear()
        }
        driver.onDown(down.position.x, down.position.y)
        var bookmarkGesture = if (!hadSelection && onAddBookmark != null) {
            PullBookmarkGesture(slop, bookmarkDistance, minimumDurationMs = BOOKMARK_PULL_MIN_DURATION_MS)
        } else null
        val pointerId = down.id
        var upPosition = down.position
        var longPressFired = false
        var slopCrossed = false
        var lastUptime = down.uptimeMillis
        var released = false
        try {
            while (true) {
                val holdRemaining = bookmarkGesture?.remainingHoldMs ?: 0L
                val event = if (holdRemaining > 0L) {
                    withTimeoutOrNull(holdRemaining) { awaitPointerEvent() }
                } else if (selection != null && !longPressFired && !slopCrossed && !hadSelection) {
                    val remaining = LONG_PRESS_TIMEOUT_MS - (lastUptime - down.uptimeMillis)
                    withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    if (holdRemaining > 0L) {
                        // A deliberate pull may pause at the end. Arm without lift-off jitter.
                        lastUptime += holdRemaining
                        bookmarkGesture?.move(upPosition.x - down.position.x, upPosition.y - down.position.y,
                            lastUptime - down.uptimeMillis)
                        onBookmarkPull?.invoke(bookmarkGesture?.progress ?: 0f)
                        continue
                    }
                    longPressFired = true
                    bookmarkGesture = null
                    selecting = selection?.begin(upPosition) == true
                    continue
                }
                val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                // Multi-touch and a parent/overlay taking ownership cancel; neither is a release.
                if (change.isConsumed || event.changes.any { it.id != pointerId && it.pressed }) break
                upPosition = change.position
                lastUptime = change.uptimeMillis
                // Do not feed lift-off jitter into the page-turn or bookmark commit decision.
                if (!change.pressed) {
                    released = true
                    if (bookmarkGesture?.ownsGesture == true) change.consume()
                    break
                }
                if (change.positionChanged()) {
                    if (selecting) {
                        selection?.drag(change.position)
                        change.consume()
                        continue
                    }
                    val deltaX = change.position.x - down.position.x
                    val deltaY = change.position.y - down.position.y
                    if (deltaX * deltaX + deltaY * deltaY > slop * slop) slopCrossed = true
                    bookmarkGesture?.move(deltaX, deltaY, lastUptime - down.uptimeMillis)
                    if (bookmarkGesture?.ownsGesture == true) {
                        onBookmarkPull?.invoke(bookmarkGesture.progress)
                    } else {
                        driver.onMove(change.position.x, change.position.y, slop)
                    }
                    change.consume()
                }
            }
            if (!released) return@awaitEachGesture
            if (selecting) {
                selection?.end()
                driver.onUp()
                return@awaitEachGesture
            }
            if (bookmarkGesture?.ownsGesture == true) {
                driver.cancelActiveTurn()
                if (bookmarkGesture.shouldAddOnRelease()) onAddBookmark?.invoke()
                return@awaitEachGesture
            }
            val suppressTap = hadSelection || longPressFired
            when (driver.onUp()) {
                PageTurnDriver.UpResult.TAP -> if (!suppressTap) onTap(upPosition, false)
                PageTurnDriver.UpResult.ABORT_TAP -> if (!suppressTap) onTap(upPosition, true)
                else -> Unit
            }
        } finally {
            onBookmarkPull?.invoke(0f)
            if (!released) {
                driver.cancelActiveTurn()
                if (selecting) selection?.end()
            }
        }
    }
}

private const val LONG_PRESS_TIMEOUT_MS = 600L
private const val BOOKMARK_PULL_MIN_DURATION_MS = 220L

/** Hinge callers supply leaf width; slide/cover callers supply full spread width. */
internal fun flatPageTurnTargetX(
    direction: PageTurnDirection,
    committing: Boolean,
    startX: Float,
    travelWidth: Float
): Float = when {
    !committing -> startX
    direction == PageTurnDirection.NEXT -> startX - travelWidth
    else -> startX + travelWidth
}

internal fun clampHingeTouchX(
    direction: PageTurnDirection,
    touchX: Float,
    startX: Float,
    leafWidth: Float
): Float = if (direction == PageTurnDirection.NEXT) touchX.coerceIn(startX - leafWidth, startX)
    else touchX.coerceIn(startX, startX + leafWidth)
