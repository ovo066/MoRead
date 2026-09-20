package com.mozhi.reader.feature.reader

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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

// Release thresholds use logical pixels; animation duration stays bounded across screen sizes.
internal fun modernCurlShouldComplete(progress: Float, forwardVelocity: Float, travel: Float, density: Float): Boolean {
    val threshold = 700f * density.coerceAtLeast(.1f)
    return forwardVelocity > threshold ||
        (forwardVelocity >= -threshold && progress + forwardVelocity / travel.coerceAtLeast(1f) * .12f >= .25f)
}

internal fun modernCurlSettleDuration(distance: Float, velocity: Float, travel: Float): Int =
    (1000f * distance / max(abs(velocity), travel * 1.2f)).roundToInt().coerceIn(140, 420)
