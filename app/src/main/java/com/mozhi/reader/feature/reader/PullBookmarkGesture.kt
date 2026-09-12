package com.mozhi.reader.feature.reader

import kotlin.math.abs

/** Direction locks at touch slop. A pull never turns a page or turns back into a tap. */
internal class PullBookmarkGesture(
    private val touchSlop: Float,
    private val triggerDistance: Float,
    private val minimumDurationMs: Long = 0L
) {
    private enum class Phase { PENDING, PAGE_TURN, PULL, CANCELLED }
    private var phase = Phase.PENDING
    var progress: Float = 0f
        private set
    val ownsGesture: Boolean get() = phase == Phase.PULL || phase == Phase.CANCELLED
    private var distanceProgress = 0f
    private var elapsedMs = 0L
    val remainingHoldMs: Long get() = if (phase == Phase.PULL && distanceProgress >= 1f)
        (minimumDurationMs - elapsedMs).coerceAtLeast(0L) else 0L

    fun move(deltaX: Float, deltaY: Float, elapsedMillis: Long = 0L) {
        elapsedMs = maxOf(elapsedMs, elapsedMillis)
        if (phase == Phase.PENDING && deltaX * deltaX + deltaY * deltaY > touchSlop * touchSlop) {
            phase = if (deltaY > 0f && deltaY >= abs(deltaX) * DIRECTION_BIAS) Phase.PULL else Phase.PAGE_TURN
        }
        if (phase == Phase.PULL) {
            if (abs(deltaX) > touchSlop && abs(deltaX) > deltaY.coerceAtLeast(0f) * CANCEL_DIRECTION_BIAS) {
                cancel()
            } else {
                distanceProgress = (deltaY / triggerDistance.coerceAtLeast(1f)).coerceIn(0f, 1f)
                progress = if (elapsedMs < minimumDurationMs) distanceProgress.coerceAtMost(0.98f) else distanceProgress
            }
        }
    }

    fun cancel() {
        phase = Phase.CANCELLED
        progress = 0f
    }

    fun shouldAddOnRelease(): Boolean = phase == Phase.PULL && progress >= 1f

    private companion object {
        const val DIRECTION_BIAS = 2f
        const val CANCEL_DIRECTION_BIAS = 1.5f
    }
}
