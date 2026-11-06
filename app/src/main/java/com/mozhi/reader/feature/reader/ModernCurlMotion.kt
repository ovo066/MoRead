package com.mozhi.reader.feature.reader

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Same edge easing as the shader; pointer deltas start at this eased anchor. */
internal fun modernCurlAnchorY(y: Float, height: Float): Float {
    val t = (y / height.coerceAtLeast(1f)).coerceIn(0f, 1f)
    fun smooth(x: Float) = x * x * (3f - 2f * x)
    return height * when {
        t < 1f / 3f -> t * smooth(3f * t)
        t > 2f / 3f -> 1f - (1f - t) * smooth(3f * (1f - t))
        else -> t
    }
}

/** Recent motion fit, so a pause or one noisy lift-off point cannot become a fling. */
internal class ModernCurlVelocity {
    private data class Sample(val time: Long, val x: Float)
    private val samples = ArrayDeque<Sample>()
    private var lastMotion = Long.MIN_VALUE
    fun reset(time: Long, x: Float) {
        samples.clear()
        lastMotion = Long.MIN_VALUE
        samples.addLast(Sample(time, x))
    }
    fun add(time: Long, x: Float) {
        val previous = samples.lastOrNull()
        if (previous != null && time < previous.time) return
        if (previous != null && x != previous.x) lastMotion = time
        if (previous?.time == time) samples.removeLast()
        samples.addLast(Sample(time, x))
        while (samples.size > 2 && time - samples.first().time > 100) samples.removeFirst()
    }
    fun velocity(now: Long): Float {
        if (lastMotion == Long.MIN_VALUE || now - lastMotion > 60) return 0f
        val points = samples.filter { now - it.time in 0..100 }
        if (points.size < 2) return 0f
        val meanT = points.sumOf { (it.time - now).toDouble() / 1000 } / points.size
        val meanX = points.sumOf { it.x.toDouble() } / points.size
        var numerator = 0.0
        var denominator = 0.0
        for (point in points) {
            val dt = (point.time - now).toDouble() / 1000 - meanT
            numerator += dt * (point.x - meanX)
            denominator += dt * dt
        }
        return if (denominator > 1e-9) (numerator / denominator).toFloat().coerceIn(-100000f, 100000f) else 0f
    }
}

/**
 * Release rule: any deliberate forward flick turns the page, a clear
 * backward flick cancels, and a release at rest needs a quarter of the travel from the press.
 * Velocities are px/s with forward positive; thresholds are in dp so every density feels alike.
 */
internal fun modernCurlShouldCommit(displacement: Float, forwardVelocity: Float, travel: Float, density: Float): Boolean {
    val dp = density.coerceAtLeast(.1f)
    if (forwardVelocity >= MODERN_CURL_COMMIT_FLING_DP * dp) return true
    if (forwardVelocity <= -MODERN_CURL_CANCEL_FLING_DP * dp) return false
    return displacement >= travel.coerceAtLeast(1f) * MODERN_CURL_COMMIT_FRACTION
}

/** Two-page hinge settle: constant-ish speed, bounded across screen sizes. */
internal fun modernCurlSettleDuration(distance: Float, velocity: Float, travel: Float): Int =
    (1000f * distance / max(abs(velocity), travel * 1.2f)).roundToInt().coerceIn(140, 420)

/**
 * One frame of the fingertip chase. A first-order lag keeps the folded edge under the finger
 * without a jump when a press lands mid-page, and filters touch-sampling jitter.
 */
internal fun modernCurlFollow(current: Float, target: Float, dtMillis: Float,
    tauMillis: Float = MODERN_CURL_FOLLOW_MS): Float =
    current + (target - current) * (1f - exp(-dtMillis.coerceAtLeast(0f) / tauMillis))

/**
 * Cubic path that leaves at [launch] times the average speed and arrives at rest. 1 gives the
 * soft start of a tap turn; up to 3 continues a fast flick without overshooting the end.
 */
internal fun modernCurlEase(fraction: Float, launch: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    val m = launch.coerceIn(0f, 3f)
    return ((m - 2f) * t + (3f - 2f * m)) * t * t + m * t
}

/** A release or tap sweep of the virtual fingertip, in view pixels. */
internal class ModernCurlSettle(
    private val fromX: Float,
    private val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMillis: Float,
    private val launch: Float
) {
    var elapsedMillis = 0f
        private set
    val finished: Boolean get() = elapsedMillis >= durationMillis

    fun advance(dtMillis: Float) { elapsedMillis = min(durationMillis, elapsedMillis + dtMillis.coerceAtLeast(0f)) }
    fun x(): Float = fromX + (toX - fromX) * modernCurlEase(elapsedMillis / durationMillis, launch)
    fun y(): Float = fromY + (toY - fromY) * modernCurlEase(elapsedMillis / durationMillis, launch)

    companion object {
        /**
         * A full sweep across both page widths takes about a third of a second; shorter paths are
         * quicker. A fast release shortens the sweep so the page leaves at the finger's speed.
         */
        fun create(fromX: Float, fromY: Float, toX: Float, toY: Float, pageWidth: Float,
            velocityTowardEnd: Float, launchFloor: Float = 1f): ModernCurlSettle {
            val distance = abs(toX - fromX)
            val sweep = (distance / (2f * pageWidth.coerceAtLeast(1f))).coerceIn(0f, 1f)
            var duration = MODERN_CURL_SWEEP_MS * sqrt(sweep)
            if (velocityTowardEnd > 1f && distance > 0f) {
                duration = min(duration, MODERN_CURL_MAX_LAUNCH * 1000f * distance / velocityTowardEnd)
            }
            duration = duration.coerceIn(MODERN_CURL_MIN_SETTLE_MS, MODERN_CURL_SWEEP_MS)
            val launch = if (distance > 0f) velocityTowardEnd * duration / 1000f / distance else 0f
            return ModernCurlSettle(fromX, fromY, toX, toY, duration,
                launch.coerceIn(launchFloor, MODERN_CURL_MAX_LAUNCH))
        }
    }
}

internal const val MODERN_CURL_FOLLOW_MS = 40f
internal const val MODERN_CURL_SWEEP_MS = 330f
internal const val MODERN_CURL_MIN_SETTLE_MS = 120f
internal const val MODERN_CURL_MAX_LAUNCH = 2.5f
internal const val MODERN_CURL_COMMIT_FRACTION = .25f
internal const val MODERN_CURL_COMMIT_FLING_DP = 140f
internal const val MODERN_CURL_CANCEL_FLING_DP = 400f
