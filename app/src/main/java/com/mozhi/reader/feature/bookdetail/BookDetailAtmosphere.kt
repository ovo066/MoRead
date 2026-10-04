package com.mozhi.reader.feature.bookdetail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A tiny copy of the cover (blurred by upscaling) plus the colours sampled from it. */
internal class CoverArt(val thumbnail: ImageBitmap, val palette: CoverPalette?)

private val coverArtCache = LruCache<String, CoverArt>(32)
private const val THUMBNAIL_WIDTH = 36

/** Loads the cover's colours off the main thread; cached per file version so returning to a book is instant. */
@Composable
internal fun rememberCoverArt(coverPath: String?): CoverArt? {
    val key = remember(coverPath) {
        coverPath?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)?.let { "${it.absolutePath}:${it.lastModified()}:${it.length()}" }
    }
    val art by produceState(key?.let(coverArtCache::get), key) {
        value = key?.let(coverArtCache::get)
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) { runCatching { decodeCoverArt(File(requireNotNull(coverPath))) }.getOrNull() }
            ?.also { coverArtCache.put(key, it) }
    }
    return art
}

private fun decodeCoverArt(file: File): CoverArt? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMBNAIL_WIDTH * 2) sample *= 2
    val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val height = (THUMBNAIL_WIDTH * decoded.height.toFloat() / decoded.width).toInt().coerceAtLeast(1)
    val small = Bitmap.createScaledBitmap(decoded, THUMBNAIL_WIDTH, height, true)
    if (small !== decoded) decoded.recycle()
    val pixels = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
    val palette = extractCoverPalette(pixels)
    // Soften the thumbnail itself, so devices without RenderEffect blur still get a smooth field of colour.
    small.setPixels(boxBlur(pixels, small.width, small.height, radius = 3), 0, small.width, 0, 0, small.width, small.height)
    return CoverArt(small.asImageBitmap(), palette)
}

/** Two-pass box blur over ARGB pixels; separable, so it stays trivial on a 36 px thumbnail. */
internal fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
    fun pass(source: IntArray, horizontal: Boolean): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
            for (k in -radius..radius) {
                val sx = if (horizontal) (x + k).coerceIn(0, width - 1) else x
                val sy = if (horizontal) y else (y + k).coerceIn(0, height - 1)
                val p = source[sy * width + sx]
                a += p ushr 24; r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
            }
            out[y * width + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
        }
        return out
    }
    return pass(pass(pixels, true), false)
}

/**
 * The page backdrop: the cover blown up into a soft field of colour, washed with the book's tone and
 * fading into the page. [scrollFade] (0 = at the top, 1 = scrolled past the hero) is read in the layer
 * block only, so scrolling never recomposes the backdrop.
 */
@Composable
internal fun BoxScope.BookDetailAtmosphere(art: CoverArt?, atmosphere: CoverAtmosphere, scrollFade: () -> Float) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(520)) }
    Box(Modifier.matchParentSize().graphicsLayer {
        val fade = scrollFade().coerceIn(0f, 1f)
        alpha = reveal.value * (1f - fade * .65f)
        translationY = -fade * 120.dp.toPx()
    }) {
        // Everything fades to transparent, so the page's own backdrop continues below without a seam.
        Box(Modifier.fillMaxWidth().fillMaxHeight(.8f).background(
            Brush.verticalGradient(0f to atmosphere.top, .5f to atmosphere.middle, 1f to atmosphere.middle.copy(alpha = 0f))))
        if (art != null) Image(
            bitmap = art.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
            // Upscaling a 36 px thumbnail with bilinear filtering is already a blur on every API level;
            // Android 12+ smooths the remaining banding with a real blur.
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxWidth().fillMaxHeight(.62f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .blur(36.dp, BlurredEdgeTreatment.Rectangle)
                .drawWithContent {
                    drawContent()
                    // Wash the cover in the book's tone so text above it always sits on a calm, readable field.
                    drawRect(atmosphere.top.copy(alpha = .62f))
                    drawRect(Brush.verticalGradient(0f to Color.Black, .35f to Color.Black.copy(alpha = .8f), 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn)
                }
        )
    }
}

/**
 * The cover as a small physical book: a spine crease and gloss over the art, the page block peeking out,
 * a shadow in the cover's own colour, and a tilt toward wherever it is pressed. Touches are observed but
 * never consumed, so the page still scrolls from here.
 */
@Composable
internal fun DetailBookObject(book: BookEntity, glow: Color, modifier: Modifier = Modifier) {
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)
    Box(modifier
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun aim(position: Offset) {
                    val x = (position.x / size.width - .5f).coerceIn(-.5f, .5f)
                    val y = (position.y / size.height - .5f).coerceIn(-.5f, .5f)
                    scope.launch { tiltY.animateTo(x * 22f, spring(stiffness = Spring.StiffnessMediumLow)) }
                    scope.launch { tiltX.animateTo(-y * 18f, spring(stiffness = Spring.StiffnessMediumLow)) }
                }
                aim(down.position)
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    aim(change.position)
                }
                scope.launch { tiltY.animateTo(0f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessLow)) }
                scope.launch { tiltX.animateTo(0f, spring(dampingRatio = .45f, stiffness = Spring.StiffnessLow)) }
            }
        }
        .graphicsLayer {
            rotationX = tiltX.value; rotationY = tiltY.value
            cameraDistance = 10f * density
        }) {
        // Page block: two paper sheets peeking out at the fore-edge and the foot.
        Box(Modifier.matchParentSize().padding(start = 6.dp, top = 4.dp).graphicsLayer { translationX = 4.dp.toPx(); translationY = 3.dp.toPx() }
            .shadow(14.dp, shape, ambientColor = glow, spotColor = glow)
            .background(Color(0xFFF1ECE2), shape)
            .drawWithContent {
                drawContent()
                val ink = Color(0xFFCFC6B6)
                for (line in 1..3) {
                    val inset = line * 1.2.dp.toPx()
                    drawLine(ink, Offset(size.width - inset, 6.dp.toPx()), Offset(size.width - inset, size.height - 6.dp.toPx()), .6.dp.toPx())
                }
            })
        HeroCover(book, Modifier.matchParentSize().drawWithContent {
            drawContent()
            // Spine crease: a dark fold, then a soft highlight just past it.
            drawRect(Brush.horizontalGradient(
                0f to Color.Black.copy(alpha = .32f), .035f to Color.Black.copy(alpha = .06f), .06f to Color.White.copy(alpha = .22f),
                .1f to Color.Transparent, endX = size.width))
            // Gloss that slides with the tilt, as if the light stays put while the book turns.
            val shift = (tiltY.value / 22f) * size.width
            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = .16f), Color.Transparent),
                start = Offset(-size.width * .2f - shift, 0f), end = Offset(size.width * .9f - shift, size.height)))
        }, shape = shape, shadow = false)
    }
}
