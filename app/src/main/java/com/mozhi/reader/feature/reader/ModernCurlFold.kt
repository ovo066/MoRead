package com.mozhi.reader.feature.reader

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The single-page fold for one frame, in height-normalized page units (y in 0..1, x in
 * 0..width/height). It is solved once per frame on the CPU; the shader only inverts the
 * cylinder per pixel. [normalX]/[normalY] point from the folded corner toward the grab edge,
 * [fold] is where the paper first leaves the page along that normal.
 */
internal class ModernCurlFold(
    val normalX: Float,
    val normalY: Float,
    val fold: Float,
    val radius: Float,
    val progress: Float,
    val reach: Float,
    val shadow: Float,
    val shadowSpread: Float
)

/**
 * The grab corner sits on the outer edge at the eased press height and is folded onto the
 * virtual fingertip ([curlX], [curlY], view pixels). The fold is the perpendicular bisector of
 * the two, shifted by half a cylinder so the corner lands under the finger. The tilt is limited
 * so the reflected corner cannot pass the binding, and it straightens as the sheet lands.
 */
internal fun modernCurlFold(
    curlX: Float,
    curlY: Float,
    downY: Float,
    width: Float,
    height: Float,
    radiusScale: Float
): ModernCurlFold {
    val h = height.coerceAtLeast(1f)
    val w = width.coerceAtLeast(1f) / h
    val fx = curlX / h
    val fy = curlY / h
    val progress = ((w - fx) / (2f * w)).coerceIn(0f, 1f)
    val anchor = modernCurlAnchorY(downY, h) / h
    val dx = max(w - fx, 1e-5f)
    val rawSlope = (fy - anchor) / max(dx, w * .18f)
    val lever = if (rawSlope >= 0f) anchor else 1f - anchor
    val reachable = max(fx - w * .055f, 0f)
    val sineLimit = (reachable / max(lever, 1e-5f)).coerceIn(0f, 1f)
    val maxSlope = sineLimit / (1f + sqrt(max(1f - sineLimit * sineLimit, 0f)))
    val slope = sign(rawSlope) * min(abs(rawSlope), maxSlope) * (1f - smoothstep(.65f, 1f, progress))
    val length = sqrt(1f + slope * slope)
    val normalX = 1f / length
    val normalY = -slope / length
    val tipY = anchor + slope * dx
    val reach = hypot(w - fx, anchor - tipY)
    val radius = mix(.065f, .04f, smoothstep(0f, .2f, reach)) * radiusScale.coerceIn(.6f, 1.8f)
    val halfTurn = (PI * radius).toFloat()
    val fold = fx * normalX + tipY * normalY + max((reach - halfTurn) * .5f, 0f) -
        halfTurn * .5f * smoothstep(.8f, 1f, progress)
    // Cast shadow on the revealed page: about 12% at the fold, decaying
    // over a tenth of the reach. It grows in with the first lift and is gone before the sheet lands.
    val shadow = MODERN_CURL_SHADOW * smoothstep(0f, .25f, reach) * (1f - smoothstep(.8f, .95f, progress))
    val spread = (reach * .1f).coerceIn(.008f, .07f)
    return ModernCurlFold(normalX, normalY, fold, radius, progress, reach, shadow, spread)
}

/** Darkening of the revealed page [distance] past the curl, in the same units as [ModernCurlFold]. */
internal fun ModernCurlFold.castShadowAt(distance: Float): Float =
    shadow * exp(-max(distance, 0f) / shadowSpread)

private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun mix(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal const val MODERN_CURL_SHADOW = .12f

/**
 * Whether a sheet carried off to the left has fully cleared the page. Past the binding the fold
 * is level, and the shader shows no paper once the crease lies half a turn beyond the page edge.
 * Solved with the smallest curl radius, so larger settings only animate a few unseen frames.
 */
internal fun modernCurlSheetHidden(curlX: Float, curlY: Float, downY: Float, width: Float, height: Float): Boolean {
    if (curlX > 0f) return false
    val fold = modernCurlFold(curlX, curlY, downY, width, height, MIN_RADIUS_SCALE)
    return fold.fold <= -PI.toFloat() * fold.radius / 2f
}

private const val MIN_RADIUS_SCALE = .6f
