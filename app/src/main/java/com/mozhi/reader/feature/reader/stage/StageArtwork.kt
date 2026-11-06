package com.mozhi.reader.feature.reader.stage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.caverock.androidsvg.SVG
import com.mozhi.reader.core.library.EpubArchiveAsset
import com.mozhi.reader.core.library.EpubArchivePool
import com.mozhi.reader.core.stage.StageImage
import com.mozhi.reader.ui.theme.MoReadSpacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 舞台用的书内图片：按需解码、按边长采样，最近用过的留在内存里（翻回去不闪）。 */
internal object StageImages {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val archives = EpubArchivePool()

    suspend fun load(path: String, maxEdge: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "$path@$maxEdge"
        cache.get(key)?.let { return@withContext it.asImageBitmap() }
        val bitmap = runCatching { decode(path, maxEdge) }.getOrNull() ?: return@withContext null
        cache.put(key, bitmap)
        bitmap.asImageBitmap()
    }

    private fun bytes(path: String): ByteArray? {
        val asset = EpubArchiveAsset.parse(path)
        return if (asset != null) synchronized(archives) { archives.read(asset) } else File(path).takeIf(File::isFile)?.readBytes()
    }

    private fun decode(path: String, maxEdge: Int): Bitmap? {
        val data = bytes(path) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return decodeSvg(data, maxEdge)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun decodeSvg(data: ByteArray, maxEdge: Int): Bitmap? = runCatching {
        val svg = SVG.getFromInputStream(data.inputStream())
        val w = svg.documentWidth.takeIf { it > 0f } ?: svg.documentViewBox?.width() ?: 512f
        val h = svg.documentHeight.takeIf { it > 0f } ?: svg.documentViewBox?.height() ?: 512f
        val scale = maxEdge / maxOf(w, h)
        val bitmap = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        svg.setDocumentWidth(bitmap.width.toFloat())
        svg.setDocumentHeight(bitmap.height.toFloat())
        svg.renderToCanvas(Canvas(bitmap))
        bitmap
    }.getOrNull()
}

@Composable
internal fun rememberStageImage(image: StageImage?, maxEdge: Int = 1280): ImageBitmap? {
    val state by produceState<ImageBitmap?>(null, image?.path, maxEdge) {
        value = image?.let { StageImages.load(it.path, maxEdge) }
    }
    return state
}

/** 插图拍：书里的图慢慢显影，图注在下方；比例先按图片尺寸占位，解码完成不改变高度。 */
@Composable
internal fun StageImageBeat(image: StageImage, palette: StagePalette, fonts: StageFonts, key: Any, instant: Boolean) {
    val bitmap = rememberStageImage(image)
    val reveal = remember(key) { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(key, bitmap != null) { if (bitmap != null && !instant) reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val ratio = when {
        bitmap != null -> bitmap.width.toFloat() / bitmap.height
        image.width > 0 && image.height > 0 -> image.width.toFloat() / image.height
        else -> 0.75f
    }.coerceIn(0.4f, 2.4f)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 0.74f)
                .clip(RoundedCornerShape(14.dp))
                .graphicsLayer {
                    alpha = reveal.value
                    val s = 1.04f - 0.04f * reveal.value
                    scaleX = s
                    scaleY = s
                }
        ) {
            if (bitmap != null) Image(bitmap, contentDescription = image.caption, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        image.caption?.let {
            Spacer(Modifier.height(MoReadSpacing.m))
            Text(
                it, color = palette.inkSoft, fontFamily = fonts.body, fontSize = 12.5.sp, letterSpacing = 0.15.em,
                textAlign = TextAlign.Center, modifier = Modifier.graphicsLayer { alpha = reveal.value }
            )
        }
    }
}

/** 章首题图：章名卡后面淡淡铺开的书内图片。 */
@Composable
internal fun BoxScope.StageTitleArt(image: StageImage?, palette: StagePalette, key: Any, instant: Boolean) {
    val bitmap = rememberStageImage(image, 960) ?: return
    val reveal = remember(key) { Animatable(if (instant) 1f else 0f) }
    LaunchedEffect(key) { if (!instant) reveal.animateTo(1f, tween(1600)) }
    Image(
        bitmap, contentDescription = null, contentScale = ContentScale.Fit,
        modifier = Modifier
            .align(Alignment.Center)
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .graphicsLayer { alpha = reveal.value * if (palette.isDark) 0.55f else 0.85f }
    )
}

/** 书里自带的分隔图：场景分隔时短暂居中显示。 */
@Composable
internal fun StageBreakArt(image: StageImage, width: Dp = 220.dp) {
    val bitmap = rememberStageImage(image, 720) ?: return
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = width).fillMaxWidth().heightIn(max = 64.dp))
}

/** 书里自带的聊天头像。 */
@Composable
internal fun StageBookAvatar(image: StageImage, size: Dp = 30.dp): Boolean {
    val bitmap = rememberStageImage(image, 192) ?: return false
    Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(CircleShape))
    return true
}
