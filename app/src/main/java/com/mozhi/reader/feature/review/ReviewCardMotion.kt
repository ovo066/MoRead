package com.mozhi.reader.feature.review

import kotlin.math.abs

internal data class ReviewCardMotion(val scale: Float, val alpha: Float, val rotation: Float, val dropDp: Float)

/** A small paper-card handoff driven by the finger, so interruption and reverse swipes stay continuous. */
internal fun reviewCardMotion(pageOffset: Float): ReviewCardMotion {
    val offset = if (pageOffset.isFinite()) pageOffset.coerceIn(-1f, 1f) else 0f
    val distance = abs(offset)
    return ReviewCardMotion(scale = 1f - distance * .055f, alpha = 1f - distance * .38f,
        rotation = if (offset == 0f) 0f else offset * -2.5f, dropDp = distance * 12f)
}
