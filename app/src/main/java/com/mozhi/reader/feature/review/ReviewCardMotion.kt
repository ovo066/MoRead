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

/** Full-screen review transitions. [PAPER] is the original handoff; the others are 3D stages. */
internal enum class ReviewFocusMotion {
    PAPER, CUBE, FLOW;

    companion object {
        fun fromWire(value: String?): ReviewFocusMotion = entries.firstOrNull { it.name == value } ?: PAPER
    }
}

/**
 * One frame of a 3D review card. Rotations are degrees, [translationX] is a fraction of the page width,
 * [pivotX] a fraction of the page width, [shade] the darkening of a face turned away from the light and
 * [glare] the horizontal centre (0..1) of the moving sheen.
 */
internal data class ReviewCard3d(
    val rotationX: Float, val rotationY: Float, val scale: Float, val alpha: Float,
    val translationX: Float, val pivotX: Float, val shade: Float, val glare: Float
)

internal const val REVIEW_TILT_LIMIT = 8f
private const val LIFT_DEGREES = 12f

/**
 * [pageOffset] uses the pager convention of the paper motion: `currentPage - index + fraction`, so a page
 * to the right of the centre is negative. [tiltX] / [tiltY] are the device tilt in degrees and only move
 * the centred card; [lift] (-1..1) leans the card while it is dragged vertically toward open or dismiss.
 */
internal fun reviewCard3d(motion: ReviewFocusMotion, pageOffset: Float, tiltX: Float = 0f, tiltY: Float = 0f, lift: Float = 0f): ReviewCard3d {
    val position = if (pageOffset.isFinite()) -pageOffset else 0f
    val clamped = position.coerceIn(-1f, 1f)
    val distance = abs(clamped)
    val focus = 1f - distance
    val safeTiltX = if (tiltX.isFinite()) tiltX.coerceIn(-REVIEW_TILT_LIMIT, REVIEW_TILT_LIMIT) else 0f
    val safeTiltY = if (tiltY.isFinite()) tiltY.coerceIn(-REVIEW_TILT_LIMIT, REVIEW_TILT_LIMIT) else 0f
    val safeLift = if (lift.isFinite()) lift.coerceIn(-1f, 1f) else 0f
    val leanX = (safeTiltX + safeLift * LIFT_DEGREES) * focus
    val glare = (.5f + safeTiltY / (REVIEW_TILT_LIMIT * 2f) - clamped * .6f).coerceIn(0f, 1f)
    return if (motion == ReviewFocusMotion.FLOW) {
        // A corridor: side cards turn their faces toward the centre and peek in from the edges.
        ReviewCard3d(
            rotationX = leanX, rotationY = -52f * clamped + safeTiltY * focus, scale = 1f - distance * .2f,
            alpha = (1f - distance * .28f) * (2f - abs(position)).coerceIn(0f, 1f),
            translationX = -position * .4f, pivotX = .5f, shade = distance * .22f, glare = glare
        )
    } else {
        // Neighbouring faces hinge on the shared page edge, so the pair always reads as one solid block.
        ReviewCard3d(
            rotationX = leanX, rotationY = 90f * clamped + safeTiltY * focus, scale = 1f,
            alpha = if (abs(position) >= .999f) 0f else 1f, translationX = 0f,
            pivotX = if (clamped < 0f) 1f else 0f, shade = distance * .34f, glare = glare
        )
    }
}

/** Maps a gravity reading (m/s², device axes) to a gentle card tilt, relative to how the phone was first held. */
internal fun reviewTiltDegrees(gravityX: Float, gravityY: Float, restingY: Float): Pair<Float, Float> {
    if (!gravityX.isFinite() || !gravityY.isFinite() || !restingY.isFinite()) return 0f to 0f
    val scale = REVIEW_TILT_LIMIT * 1.6f / 9.81f
    val tiltX = ((gravityY - restingY) * scale).coerceIn(-REVIEW_TILT_LIMIT, REVIEW_TILT_LIMIT)
    val tiltY = (-gravityX * scale).coerceIn(-REVIEW_TILT_LIMIT, REVIEW_TILT_LIMIT)
    return tiltX to tiltY
}
