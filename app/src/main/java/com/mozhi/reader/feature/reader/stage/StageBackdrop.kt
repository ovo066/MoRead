package com.mozhi.reader.feature.reader.stage

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.StageHalo
import com.mozhi.reader.core.datastore.StagePaperTexture
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.datastore.StageWeather
import com.mozhi.reader.core.stage.StageEra
import com.mozhi.reader.core.stage.StageImage
import com.mozhi.reader.core.stage.StageIntensity
import com.mozhi.reader.core.stage.StageScene
import com.mozhi.reader.core.stage.StageWeatherCue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 舞台上真正画出来的天气（原文雾天画成飘动的薄雾，其余是粒子）。 */
internal enum class StageWeatherKind { RAIN, SNOW, FOG, PETALS, LEAVES, FIREFLIES, DUST }

/** 设置与原文场景合成后的天气；克制档与系统「移除动画」时一律没有。 */
internal fun stageWeather(settings: StageSettings, scene: StageScene?, intensity: StageIntensity, reduceMotion: Boolean): StageWeatherKind? {
    if (reduceMotion || intensity == StageIntensity.RESTRAINED) return null
    return when (settings.weather) {
        StageWeather.OFF -> null
        StageWeather.AUTO -> when (scene?.weather) {
            StageWeatherCue.RAIN, StageWeatherCue.STORM -> StageWeatherKind.RAIN
            StageWeatherCue.SNOW -> StageWeatherKind.SNOW
            StageWeatherCue.FOG -> StageWeatherKind.FOG
            StageWeatherCue.PETALS -> StageWeatherKind.PETALS
            StageWeatherCue.LEAVES -> StageWeatherKind.LEAVES
            StageWeatherCue.FIREFLIES -> StageWeatherKind.FIREFLIES
            StageWeatherCue.DUST -> StageWeatherKind.DUST
            null -> null
        }
        StageWeather.RAIN -> StageWeatherKind.RAIN
        StageWeather.SNOW -> StageWeatherKind.SNOW
        StageWeather.PETALS -> StageWeatherKind.PETALS
        StageWeather.LEAVES -> StageWeatherKind.LEAVES
        StageWeather.FIREFLIES -> StageWeatherKind.FIREFLIES
        StageWeather.DUST -> StageWeatherKind.DUST
    }
}

/**
 * 舞台背景：底色 → 背景图（褪向底色、可模糊）或水墨布景 → 光晕 → 情绪颜料 → 纸面质感 → 泛黄 → 天气。
 * 全部画在文字后面；粒子按帧推进，只在有天气时跑帧；布景的镜头呼吸是低频更新。
 */
@Composable
internal fun StageBackdrop(
    settings: StageSettings,
    palette: StagePalette,
    imagePath: String?,
    scene: StageScene?,
    era: StageEra?,
    seed: Int,
    weather: StageWeatherKind?,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxSize().drawBehind { drawRect(palette.background); drawRect(palette.backdrop) }) {
        if (imagePath != null) {
            val bitmap = rememberStageImage(StageImage(imagePath), 1600)
            if (bitmap != null) {
                val blur = settings.backgroundBlur
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (blur > 0.01f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur((blur * 22f).dp) else Modifier)
                )
                Box(Modifier.fillMaxSize().drawBehind { drawRect(palette.background.copy(alpha = settings.backgroundDim)) })
            }
        } else if (settings.scenery) {
            StageScenery(scene, era, palette, seed, animate)
        }
        val mood by animateColorAsState(if (settings.moodTint) moodPigment(scene?.mood, palette.isDark) else Color.Transparent, tween(1_600), label = "mood")
        val texture = rememberStageTexture(settings.texture, palette.isDark)
        Box(
            Modifier.fillMaxSize().drawBehind {
                drawHalo(settings, palette)
                // 情绪颜料只染四周，中间留给文字：像电影调色的边缘偏色。
                if (mood.alpha > 0f) {
                    drawRect(
                        Brush.radialGradient(
                            0.3f to Color.Transparent,
                            1f to mood.copy(alpha = (mood.alpha * 2.2f).coerceAtMost(0.4f)),
                            center = Offset(size.width / 2f, size.height * 0.45f),
                            radius = size.maxDimension * 0.72f
                        )
                    )
                }
                if (texture != null) {
                    drawRect(ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)), alpha = if (palette.isDark) 0.55f else 0.8f)
                }
                if (settings.aging > 0f) {
                    drawRect(Color(0xFFC9A066), alpha = settings.aging * (if (palette.isDark) 0.10f else 0.20f), blendMode = BlendMode.Multiply)
                    drawRect(Color(0xFFE8D5A8), alpha = settings.aging * (if (palette.isDark) 0.04f else 0.10f))
                }
            }
        )
        Crossfade(targetState = weather, animationSpec = tween(1_200), label = "weather") { kind ->
            if (kind != null) StageWeatherLayer(kind, settings.weatherDensity, palette)
        }
    }
}

private fun DrawScope.drawHalo(settings: StageSettings, palette: StagePalette) {
    if (settings.halo == StageHalo.NONE || settings.haloStrength <= 0f) return
    val base = StageHaloColors.getOrElse(settings.haloColor) { StageHaloColors[1] }
    val a = settings.haloStrength * (if (palette.isDark) 0.5f else 0.36f)
    val w = size.width
    val h = size.height
    fun glow(center: Offset, radius: Float, alpha: Float) =
        drawRect(Brush.radialGradient(listOf(base.copy(alpha = alpha), base.copy(alpha = alpha * 0.35f), Color.Transparent), center, radius))
    when (settings.halo) {
        StageHalo.TOP -> glow(Offset(w / 2f, -h * 0.06f), h * 0.62f, a)
        StageHalo.CENTER -> glow(Offset(w / 2f, h * 0.44f), h * 0.5f, a * 0.8f)
        StageHalo.BOTTOM -> glow(Offset(w / 2f, h * 1.06f), h * 0.6f, a)
        StageHalo.STARRY -> {
            glow(Offset(w * 0.3f, h * 0.2f), h * 0.42f, a)
            glow(Offset(w * 0.86f, h * 0.82f), h * 0.3f, a * 0.6f)
            val dot = 1.dp.toPx()
            for (i in 0 until 46) {
                val x = hash(i, 1) * w
                val y = hash(i, 2) * h
                val r = dot * (0.6f + hash(i, 3) * 1.2f)
                drawCircle(base.copy(alpha = (0.18f + hash(i, 4) * 0.4f) * settings.haloStrength), r, Offset(x, y))
            }
        }
        StageHalo.NONE -> Unit
    }
}

/** 0..1 的确定性散列：同一个粒子每帧落在同一条轨迹上。 */
private fun hash(i: Int, salt: Int): Float {
    var x = (i.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (salt.toLong() * 0xBF58476D1CE4E5B9uL.toLong())
    x = x xor (x ushr 31)
    x *= 0x94D049BB133111EBuL.toLong()
    x = x xor (x ushr 29)
    return ((x ushr 11) and 0xFFFFF).toFloat() / 0xFFFFF.toFloat()
}

@Composable
private fun StageWeatherLayer(kind: StageWeatherKind, density: Float, palette: StagePalette) {
    val time = remember { mutableLongStateOf(0L) }
    LaunchedEffect(kind) {
        var start = -1L
        while (isActive) withFrameMillis { now ->
            if (start < 0) start = now
            time.longValue = now - start
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val t = time.longValue / 1000f
        when (kind) {
            StageWeatherKind.RAIN -> drawRain(t, density, palette)
            StageWeatherKind.SNOW -> drawSnow(t, density, palette)
            StageWeatherKind.FOG -> drawFog(t, palette)
            StageWeatherKind.PETALS -> drawFalling(t, density, palette, petals = true)
            StageWeatherKind.LEAVES -> drawFalling(t, density, palette, petals = false)
            StageWeatherKind.FIREFLIES -> drawFireflies(t, density, palette)
            StageWeatherKind.DUST -> drawDust(t, density, palette)
        }
    }
}

private fun DrawScope.drawRain(t: Float, density: Float, palette: StagePalette) {
    val w = size.width
    val h = size.height
    val count = (40 + 120 * density).toInt()
    val slant = 0.18f
    val color = if (palette.isDark) Color.White else palette.ink
    for (i in 0 until count) {
        val speed = 1.1f + 0.7f * hash(i, 2)
        val len = (14f + 18f * hash(i, 3)).dp.toPx()
        val y = (((hash(i, 4) + t * speed / 1.2f) % 1.15f) - 0.08f) * h
        val x = ((hash(i, 1) * w + y * slant) % w + w) % w
        val alpha = (if (palette.isDark) 0.16f else 0.12f) + 0.14f * hash(i, 5)
        drawLine(color.copy(alpha = alpha), Offset(x, y), Offset(x - len * slant, y - len), strokeWidth = (0.8f + hash(i, 6) * 0.7f).dp.toPx())
    }
}

private fun DrawScope.drawSnow(t: Float, density: Float, palette: StagePalette) {
    val w = size.width
    val h = size.height
    val count = (30 + 90 * density).toInt()
    val color = if (palette.isDark) Color.White else Color(0xFF8C9AB0)
    for (i in 0 until count) {
        val speed = 0.06f + 0.10f * hash(i, 2)
        val r = (1.4f + 2.2f * hash(i, 3)).dp.toPx()
        val y = (((hash(i, 4) + t * speed) % 1.1f) - 0.05f) * h
        val sway = sin(t * (0.6f + hash(i, 5)) + hash(i, 6) * 6.28f) * 14.dp.toPx()
        val x = ((hash(i, 1) * w + sway) % w + w) % w
        drawCircle(color.copy(alpha = (if (palette.isDark) 0.45f else 0.38f) + 0.35f * hash(i, 7)), r, Offset(x, y))
    }
}

private fun DrawScope.drawFog(t: Float, palette: StagePalette) {
    val w = size.width
    val h = size.height
    val color = if (palette.isDark) Color(0xFFB8C2CC) else Color.White
    for (i in 0 until 6) {
        val cx = ((hash(i, 1) + t * (0.008f + 0.01f * hash(i, 2))) % 1.4f - 0.2f) * w
        val cy = (0.2f + 0.7f * hash(i, 3)) * h
        val r = (0.35f + 0.3f * hash(i, 4)) * w
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = if (palette.isDark) 0.07f else 0.22f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
}

private fun DrawScope.drawFalling(t: Float, density: Float, palette: StagePalette, petals: Boolean) {
    val w = size.width
    val h = size.height
    val count = ((if (petals) 12 else 8) + (if (petals) 26 else 18) * density).toInt()
    val colors = if (petals) listOf(Color(0xFFF2B5C4), Color(0xFFEFA0B4), Color(0xFFF8CBD6))
    else listOf(Color(0xFFC98A3A), Color(0xFFB5652B), Color(0xFFD9A441))
    for (i in 0 until count) {
        val speed = 0.07f + 0.07f * hash(i, 2)
        val y = (((hash(i, 4) + t * speed) % 1.15f) - 0.08f) * h
        val drift = t * (14f + 20f * hash(i, 8)).dp.toPx()
        val sway = sin(t * (0.9f + hash(i, 5)) + hash(i, 6) * 6.28f) * 22.dp.toPx()
        val x = ((hash(i, 1) * w + sway + drift) % w + w) % w
        val angle = (t * (40f + 70f * hash(i, 3)) + hash(i, 7) * 360f) % 360f
        val c = colors[(hash(i, 9) * colors.size).toInt().coerceIn(0, colors.lastIndex)].copy(alpha = if (palette.isDark) 0.75f else 0.85f)
        val len = (if (petals) 7f else 9f).dp.toPx() * (0.8f + 0.5f * hash(i, 10))
        translate(x, y) {
            rotate(angle, Offset.Zero) {
                if (petals) {
                    drawOval(c, Offset(-len / 2f, -len / 3.6f), Size(len, len / 1.8f))
                } else {
                    val leaf = Path().apply {
                        moveTo(-len / 2f, 0f)
                        quadraticTo(0f, -len / 2.6f, len / 2f, 0f)
                        quadraticTo(0f, len / 2.6f, -len / 2f, 0f)
                        close()
                    }
                    drawPath(leaf, c)
                }
            }
        }
    }
}

private fun DrawScope.drawFireflies(t: Float, density: Float, palette: StagePalette) {
    val w = size.width
    val h = size.height
    val count = (10 + 24 * density).toInt()
    val core = Color(0xFFF2F59A)
    for (i in 0 until count) {
        val x = (hash(i, 1) + 0.06f * sin(t * (0.3f + 0.3f * hash(i, 2)) + hash(i, 3) * 6.28f)) * w
        val y = (hash(i, 4) + 0.05f * cos(t * (0.25f + 0.3f * hash(i, 5)) + hash(i, 6) * 6.28f)) * h
        val blink = 0.5f + 0.5f * sin(t * (1.2f + hash(i, 7)) + hash(i, 8) * 6.28f)
        val a = (blink * blink) * (if (palette.isDark) 0.9f else 0.6f)
        val glow = 9.dp.toPx()
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = a * 0.5f), Color.Transparent), Offset(x, y), glow), glow, Offset(x, y))
        drawCircle(core.copy(alpha = a), 1.6.dp.toPx(), Offset(x, y))
    }
}

private fun DrawScope.drawDust(t: Float, density: Float, palette: StagePalette) {
    val w = size.width
    val h = size.height
    val count = (20 + 50 * density).toInt()
    val color = if (palette.isDark) Color(0xFFE8DCC0) else Color(0xFF9A8A6A)
    for (i in 0 until count) {
        val x = ((hash(i, 1) + t * (0.004f + 0.01f * hash(i, 2)) + 0.02f * sin(t * 0.4f + hash(i, 3) * 6.28f)) % 1f) * w
        val y = ((hash(i, 4) - t * (0.006f + 0.012f * hash(i, 5)) % 1f + 1f) % 1f) * h
        drawCircle(color.copy(alpha = 0.12f + 0.25f * hash(i, 6)), (0.7f + 1f * hash(i, 7)).dp.toPx(), Offset(x, y))
    }
}

/** 纸面纹理：程序生成一块 256 像素的可平铺小图，按种类与明暗缓存。 */
@Composable
private fun rememberStageTexture(kind: StagePaperTexture, dark: Boolean): ImageBitmap? {
    if (kind == StagePaperTexture.NONE) return null
    val state by produceState<ImageBitmap?>(StageTextures.cached(kind, dark), kind, dark) {
        value = withContext(Dispatchers.Default) { StageTextures.tile(kind, dark) }
    }
    return state
}

internal object StageTextures {
    private const val SIZE = 256
    private val cache = HashMap<Pair<StagePaperTexture, Boolean>, ImageBitmap>()

    fun cached(kind: StagePaperTexture, dark: Boolean): ImageBitmap? = synchronized(cache) { cache[kind to dark] }

    fun tile(kind: StagePaperTexture, dark: Boolean): ImageBitmap = synchronized(cache) {
        cache.getOrPut(kind to dark) { Bitmap.createBitmap(pixels(kind, dark), SIZE, SIZE, Bitmap.Config.ARGB_8888).asImageBitmap() }
    }

    /** 纯函数：同样的种类与明暗总是同一块纹理（单测可直接校验平铺无缝与透明度上限）。 */
    fun pixels(kind: StagePaperTexture, dark: Boolean): IntArray {
        val px = IntArray(SIZE * SIZE)
        fun put(x: Int, y: Int, light: Boolean, alpha: Int) {
            val xi = ((x % SIZE) + SIZE) % SIZE
            val yi = ((y % SIZE) + SIZE) % SIZE
            val a = alpha.coerceIn(0, 255)
            val existing = px[yi * SIZE + xi] ushr 24
            if (a <= existing) return
            px[yi * SIZE + xi] = (a shl 24) or (if (light) 0xFFFFFF else (if (dark) 0x000000 else 0x3A2A14))
        }
        when (kind) {
            StagePaperTexture.GRAIN -> for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val v = hash(y * SIZE + x, 11) * 2f - 1f
                put(x, y, v > 0f, (abs(v) * (if (dark) 16f else 22f)).toInt())
            }
            StagePaperTexture.FIBER -> repeat(240) { i ->
                var x = hash(i, 21) * SIZE
                var y = hash(i, 22) * SIZE
                var angle = hash(i, 23) * 6.28f
                val light = hash(i, 24) > 0.5f
                val alpha = (8 + 22 * hash(i, 25)).toInt()
                repeat((10 + 26 * hash(i, 26)).toInt()) { step ->
                    put(x.toInt(), y.toInt(), light, alpha)
                    angle += (hash(i * 97 + step, 27) - 0.5f) * 0.5f
                    x += cos(angle)
                    y += sin(angle)
                }
            }
            StagePaperTexture.LINEN -> for (y in 0 until SIZE) for (x in 0 until SIZE) {
                val row = sin(y * 2f * PI.toFloat() / 4f) * 0.5f + (hash(y, 31) - 0.5f) * 0.6f
                val col = sin(x * 2f * PI.toFloat() / 4f) * 0.5f + (hash(x, 32) - 0.5f) * 0.6f
                val v = (row + col) / 2f + (hash(y * SIZE + x, 33) - 0.5f) * 0.3f
                put(x, y, v > 0f, (abs(v) * (if (dark) 18f else 26f)).toInt())
            }
            StagePaperTexture.SPECKLE -> {
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val v = hash(y * SIZE + x, 41)
                    if (v > 0.997f) put(x, y, false, (40 * (v - 0.997f) / 0.003f).toInt() + 14)
                }
                repeat(70) { i ->
                    val cx = hash(i, 42) * SIZE
                    val cy = hash(i, 43) * SIZE
                    val r = 1f + 3.5f * hash(i, 44)
                    val peak = (14 + 26 * hash(i, 45)).toInt()
                    for (dy in -5..5) for (dx in -5..5) {
                        val d = kotlin.math.sqrt((dx * dx + dy * dy).toFloat())
                        if (d <= r) put((cx + dx).toInt(), (cy + dy).toInt(), dark, (peak * (1f - d / (r + 0.5f))).toInt())
                    }
                }
            }
            StagePaperTexture.CRUMPLED -> {
                val field = FloatArray(SIZE * SIZE) { 0f }
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    field[y * SIZE + x] = valueNoise(x, y, 64) * 0.6f + valueNoise(x, y, 32) * 0.3f + valueNoise(x, y, 16) * 0.1f
                }
                for (y in 0 until SIZE) for (x in 0 until SIZE) {
                    val dx = field[y * SIZE + (x + 1) % SIZE] - field[y * SIZE + (x - 1 + SIZE) % SIZE]
                    val dy = field[((y + 1) % SIZE) * SIZE + x] - field[((y - 1 + SIZE) % SIZE) * SIZE + x]
                    val shade = (dx - dy) * 9f
                    put(x, y, shade > 0f, (abs(shade) * (if (dark) 26f else 34f)).toInt().coerceAtMost(40))
                }
            }
            StagePaperTexture.NONE -> Unit
        }
        return px
    }

    /** 可平铺的值噪声：格点按 [cell] 取，周期等于纹理边长。 */
    private fun valueNoise(x: Int, y: Int, cell: Int): Float {
        val cells = SIZE / cell
        val gx = x / cell
        val gy = y / cell
        val fx = (x % cell) / cell.toFloat()
        val fy = (y % cell) / cell.toFloat()
        fun v(i: Int, j: Int) = hash(((j % cells) + cells) % cells * 131 + ((i % cells) + cells) % cells, cell)
        fun smooth(f: Float) = f * f * (3f - 2f * f)
        val sx = smooth(fx)
        val sy = smooth(fy)
        val top = v(gx, gy) * (1 - sx) + v(gx + 1, gy) * sx
        val bottom = v(gx, gy + 1) * (1 - sx) + v(gx + 1, gy + 1) * sx
        return top * (1 - sy) + bottom * sy
    }
}
