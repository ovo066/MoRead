package com.mozhi.reader.feature.reader.stage

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mozhi.reader.core.stage.StageEra
import com.mozhi.reader.core.stage.StageLight
import com.mozhi.reader.core.stage.StageMood
import com.mozhi.reader.core.stage.StageScene
import com.mozhi.reader.core.stage.StageSetting
import com.mozhi.reader.core.stage.StageTime
import com.mozhi.reader.core.stage.StageWeatherCue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** 布景缓冲相对屏幕的缩小倍数：水墨本来就柔，三分之一分辨率放大后反而更润，也省内存。 */
private const val SCENERY_SCALE = 3

/**
 * 一幅布景的全部输入；同样的 key 总是画出同一幅图（缓存靠它，换拍不重画）。
 * 情绪不进 key：情绪是运行时叠的一层颜料，变了只换颜色、不重画山水。
 */
@Immutable
internal data class StageSceneryKey(
    val setting: StageSetting?,
    val time: StageTime?,
    val overcast: Boolean,
    val era: StageEra?,
    val dark: Boolean,
    val ink: Int,
    val paper: Int,
    val width: Int,
    val height: Int,
    /** 每章一个种子：同一本书不同章节的山形、楼影各不相同，同一章里回看不变。 */
    val seed: Int
) {
    /** 主体偏左还是偏右（马远「一角」式构图，把屏幕中间留给文字）。 */
    val side: Int get() = if (StageNoise.hash(seed, 3) < 0.5f) -1 else 1
}

/** 一幅布景的两层：远景（天色、日月、远山）与近景（中近山、楼、竹、船），镜头微动时各自错开形成纵深。 */
internal class StageSceneryLayers(val back: ImageBitmap, val front: ImageBitmap)

/** 布景缓存：两三幅够回看与来回切换用；每幅只有屏幕九分之一的像素。 */
private object StageSceneryCache {
    private val cache = LruCache<StageSceneryKey, StageSceneryLayers>(4)
    fun get(key: StageSceneryKey): StageSceneryLayers? = cache.get(key)
    fun put(key: StageSceneryKey, layers: StageSceneryLayers) { cache.put(key, layers) }
}

/** 0..1 的确定性噪声：值噪声与分形叠加。布景的山脊、墨块与雾都从这里来。 */
internal object StageNoise {
    fun hash(i: Int, salt: Int): Float {
        var x = (i.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (salt.toLong() * 0xBF58476D1CE4E5B9uL.toLong())
        x = x xor (x ushr 31)
        x *= 0x94D049BB133111EBuL.toLong()
        x = x xor (x ushr 29)
        return ((x ushr 11) and 0xFFFFF).toFloat() / 0xFFFFF.toFloat()
    }

    private fun smooth(f: Float) = f * f * (3f - 2f * f)

    fun value1(x: Float, salt: Int): Float {
        val i = floor(x).toInt()
        val s = smooth(x - i)
        return hash(i, salt) * (1 - s) + hash(i + 1, salt) * s
    }

    fun fbm1(x: Float, salt: Int, octaves: Int = 5): Float {
        var v = 0f
        var amp = 0.5f
        var freq = 1f
        var total = 0f
        for (o in 0 until octaves) {
            v += amp * value1(x * freq, salt + o * 17)
            total += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return v / total
    }

    fun value2(x: Float, y: Float, salt: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val sx = smooth(x - xi)
        val sy = smooth(y - yi)
        fun h(i: Int, j: Int) = hash(i * 73856093 + j * 19349663, salt)
        val top = h(xi, yi) * (1 - sx) + h(xi + 1, yi) * sx
        val bottom = h(xi, yi + 1) * (1 - sx) + h(xi + 1, yi + 1) * sx
        return top * (1 - sy) + bottom * sy
    }

    fun fbm2(x: Float, y: Float, salt: Int, octaves: Int = 4): Float {
        var v = 0f
        var amp = 0.5f
        var freq = 1f
        var total = 0f
        for (o in 0 until octaves) {
            v += amp * value2(x * freq, y * freq, salt + o * 31)
            total += amp
            amp *= 0.5f
            freq *= 2.1f
        }
        return v / total
    }
}

/** 国画颜料：布景、情绪与强调都从这几味色里取，整个舞台像一张画里的颜色。 */
internal object StagePigments {
    val Ink = Color(0xFF282522)
    val Vermilion = Color(0xFFB2402E)   // 朱砂
    val Gamboge = Color(0xFFD6A03C)     // 藤黄
    val Indigo = Color(0xFF2E4262)      // 花青
    val Ochre = Color(0xFF8C5834)       // 赭石
    val Malachite = Color(0xFF3A6254)   // 石绿
    val Rouge = Color(0xFFAA4658)       // 胭脂
}

/** 一块预乘透明度的浮点画布：每层都是「颜色 × 透明度」叠上去，最后转成位图。 */
private class InkCanvas(val w: Int, val h: Int) {
    val r = FloatArray(w * h)
    val g = FloatArray(w * h)
    val b = FloatArray(w * h)
    val a = FloatArray(w * h)

    fun over(i: Int, alpha: Float, color: FloatArray) {
        if (alpha <= 0.0005f) return
        val k = if (alpha > 1f) 1f else alpha
        val inv = 1f - k
        r[i] = r[i] * inv + color[0] * k
        g[i] = g[i] * inv + color[1] * k
        b[i] = b[i] * inv + color[2] * k
        a[i] = a[i] * inv + k
    }

    fun toArgb(): IntArray {
        val px = IntArray(w * h)
        for (i in px.indices) {
            val alpha = a[i]
            if (alpha <= 0.002f) continue
            val inv = 1f / alpha
            val ai = (alpha * 255f).toInt().coerceIn(0, 255)
            val ri = (r[i] * inv).toInt().coerceIn(0, 255)
            val gi = (g[i] * inv).toInt().coerceIn(0, 255)
            val bi = (b[i] * inv).toInt().coerceIn(0, 255)
            px[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        return px
    }
}

private fun rgb(argb: Int) = floatArrayOf(((argb shr 16) and 0xFF).toFloat(), ((argb shr 8) and 0xFF).toFloat(), (argb and 0xFF).toFloat())
private fun rgb(color: Color) = rgb(color.toArgb())
private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

/**
 * 程序化水墨布景。纯 CPU、后台线程、确定性：同一个 key 永远同一幅画。
 * 构图守一条规矩——主体压在下方一角，中间留白给文字；远山淡、近山浓，层层之间隔着雾。
 */
internal object StageSceneryPainter {

    fun paint(key: StageSceneryKey): StageSceneryLayers {
        val (back, front) = paintPixels(key)
        return StageSceneryLayers(
            Bitmap.createBitmap(back, key.width, key.height, Bitmap.Config.ARGB_8888).asImageBitmap(),
            Bitmap.createBitmap(front, key.width, key.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        )
    }

    /** 远景与近景两层的非预乘 ARGB 像素（纯 Kotlin，JVM 单测可直接出图核对）。 */
    fun paintPixels(key: StageSceneryKey): Pair<IntArray, IntArray> {
        val back = InkCanvas(key.width, key.height)
        val front = InkCanvas(key.width, key.height)
        Scene(key, back, front).draw()
        return back.toArgb() to front.toArgb()
    }

    private class Scene(val key: StageSceneryKey, val back: InkCanvas, val front: InkCanvas) {
        val w = key.width
        val h = key.height
        /** 以 640 像素高的缓冲为基准的比例尺：参数按这个尺度写，换屏幕比例不走样。 */
        val u = h / 640f
        val ink = rgb(key.ink)
        val paper = rgb(key.paper)
        val side = key.side
        val seed = key.seed
        val dark = key.dark

        fun draw() {
            sky()
            celestial()
            when (key.setting) {
                StageSetting.MOUNTAIN -> mountains()
                StageSetting.WATER -> water()
                StageSetting.SEA -> sea()
                StageSetting.FOREST -> forest()
                StageSetting.BAMBOO -> bamboo()
                StageSetting.CITY -> city(lit = key.time == StageTime.NIGHT || key.time == StageTime.DUSK)
                StageSetting.INTERIOR -> interior()
                StageSetting.CLASSICAL -> classical()
                StageSetting.CAMPUS -> campus()
                StageSetting.VILLAGE -> village()
                StageSetting.DESERT -> desert()
                null -> Unit
            }
        }

        // —— 天色 ——

        private fun sky() {
            val c = back
            val tint = when {
                key.overcast -> rgb(if (dark) Color(0xFF8E96A3) else Color(0xFF6E7378))
                key.time == StageTime.NIGHT && !dark -> rgb(StagePigments.Indigo)
                key.time == StageTime.DUSK -> rgb(StagePigments.Gamboge)
                key.time == StageTime.DAWN -> rgb(StagePigments.Rouge)
                else -> return
            }
            for (y in 0 until h) {
                val t = y / h.toFloat()
                val alpha = when {
                    key.overcast -> clamp01(1f - t * 1.5f).pow(1.2f) * (if (dark) 0.08f else 0.10f)
                    key.time == StageTime.NIGHT -> clamp01(1f - t * 1.6f).pow(1.4f) * 0.10f
                    key.time == StageTime.DUSK -> exp(-((t - 0.64f) / 0.16f).let { it * it }) * (if (dark) 0.10f else 0.13f)
                    else -> exp(-((t - 0.68f) / 0.2f).let { it * it }) * 0.08f
                }
                if (alpha <= 0.001f) continue
                for (x in 0 until w) {
                    val n = StageNoise.fbm2(x / (120f * u), y / (60f * u), seed + 5, 3)
                    c.over(y * w + x, alpha * (0.7f + 0.6f * n), tint)
                }
            }
            if (key.time == StageTime.DUSK) {
                // 天顶一层极淡的赭石，晚霞不至于只是一条带子。
                val ochre = rgb(StagePigments.Ochre)
                for (y in 0 until (h * 0.45f).toInt()) {
                    val alpha = clamp01(1f - y / (h * 0.45f)) * 0.05f
                    for (x in 0 until w) c.over(y * w + x, alpha, ochre)
                }
            }
        }

        /** 日月：夜里纸页上「烘云托月」——月轮留白、四周淡墨烘染；暗场上是一轮发光的月。黄昏与清晨是朱砂色的日。 */
        private fun celestial() {
            // 室内看不到日月；阴天也不出。没有布景时只留月（在上方一角），不让一轮日悬在文字中间。
            if (key.overcast || key.setting == StageSetting.INTERIOR) return
            val c = back
            // 日月放在与山势相反的一侧，画面才平衡。
            val cx = if (side > 0) w * 0.24f else w * 0.76f
            when (key.time) {
                StageTime.NIGHT -> {
                    val cy = h * 0.12f
                    val r = 19f * u
                    if (dark) {
                        val moonColor = rgb(Color(0xFFEEE9DC))
                        forDisc(cx, cy, r * 4.6f) { i, d ->
                            val disc = clamp01((r - d) / 1.2f) * 0.88f
                            val halo = if (d > r) clamp01(1f - (d - r) / (r * 3.6f)).pow(2.4f) * 0.13f else 0f
                            c.over(i, max(disc, halo), moonColor)
                        }
                        stars()
                    } else {
                        val wash = rgb(StagePigments.Indigo)
                        forDisc(cx, cy, r * 4.5f) { i, d ->
                            if (d <= r) return@forDisc
                            val x = i % w
                            val y = i / w
                            val n = StageNoise.fbm2(x / (45f * u), y / (45f * u), seed + 77, 4)
                            val a = clamp01(1f - (d - r) / (r * 3.4f)).pow(1.5f) * (0.5f + 0.7f * n) * clamp01((d - r) / 1.8f)
                            c.over(i, a * 0.18f, wash)
                        }
                    }
                }
                StageTime.DUSK, StageTime.DAWN -> {
                    if (key.setting == null) return
                    val cy = h * (if (key.time == StageTime.DUSK) 0.76f else 0.78f)
                    val r = 22f * u
                    val sun = rgb(StagePigments.Vermilion)
                    val alpha = if (key.time == StageTime.DUSK) 0.30f else 0.22f
                    forDisc(cx, cy, r * 3.5f) { i, d ->
                        val disc = clamp01((r - d) / 1.0f) * alpha
                        val glow = if (d > r) clamp01(1f - (d - r) / (r * 2.5f)).pow(2f) * alpha * 0.25f else 0f
                        c.over(i, max(disc, glow), sun)
                    }
                }
                else -> Unit
            }
        }

        private fun stars() {
            val star = rgb(Color(0xFFEDE7D8))
            for (k in 0 until 60) {
                val x = (StageNoise.hash(k, seed + 11) * w).toInt()
                val y = (StageNoise.hash(k, seed + 12) * h * 0.55f).toInt()
                val a = 0.25f + 0.55f * StageNoise.hash(k, seed + 13)
                if (x in 0 until w && y in 0 until h) back.over(y * w + x, a, star)
            }
        }

        private inline fun forDisc(cx: Float, cy: Float, radius: Float, block: (Int, Float) -> Unit) {
            val x0 = max(0, (cx - radius).toInt())
            val x1 = min(w - 1, (cx + radius).toInt())
            val y0 = max(0, (cy - radius).toInt())
            val y1 = min(h - 1, (cy + radius).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val d = sqrt(dx * dx + dy * dy)
                if (d <= radius) block(y * w + x, d)
            }
        }

        // —— 山 ——

        /** 几座主峰（不对称、尖顶微圆）取最大值，再叠细碎起伏；side 非 0 时主峰挤在那一侧。 */
        private fun ridge(salt: Int, peaks: Int, width: Float, height: Float, side: Int): FloatArray {
            val out = FloatArray(w)
            for (x in 0 until w) {
                var r = 0f
                for (p in 0 until peaks) {
                    val t = StageNoise.hash(p, salt)
                    val c = if (side != 0) (0.5f + side * 0.42f * (0.4f + 0.6f * t)) * w else t * w * 1.2f - w * 0.1f
                    val hp = height * (0.5f + 0.5f * StageNoise.hash(p, salt + 1)) * (if (p == 0) 1f else 0.75f)
                    val wl = width * (0.55f + 0.7f * StageNoise.hash(p, salt + 2))
                    val wr = width * (0.55f + 0.7f * StageNoise.hash(p, salt + 3))
                    val d = if (x < c) (c - x) / wl else (x - c) / wr
                    if (d >= 1f) continue
                    val dc = clamp01(d)
                    val bump = hp * (1f - dc).pow(1.35f) * (0.85f + 0.15f * cos(dc * PI.toFloat()))
                    if (bump > r) r = bump
                }
                if (r > 0.5f) r += (StageNoise.fbm1(x / (18f * u), salt + 7) - 0.5f) * height * 0.16f
                out[x] = r
            }
            return out
        }

        /**
         * 一层山：墨积在山脊（墨边），往下淡成雾；山体里有成块的墨韵与细竖的皴擦。
         * [base] 是山脚所在的高度，[edge]/[body] 是山脊与山体的浓淡，[fade] 是往下淡出的距离。
         */
        private fun mountain(c: InkCanvas, salt: Int, base: Float, height: Float, width: Float, peaks: Int, side: Int, edge: Float, body: Float, fade: Float, color: FloatArray = ink) {
            val ridge = ridge(salt, peaks, width, height, side)
            var top = h
            for (x in 0 until w) top = min(top, (base - ridge[x] - height * 0.05f).toInt())
            for (y in max(0, top) until h) {
                for (x in 0 until w) {
                    val depth = y - (base - ridge[x] - height * 0.05f)
                    if (depth <= 0f) continue
                    val e = exp(-depth / (3.5f * u))
                    val bd = exp(-depth / (fade * u))
                    var a = (e * edge + bd * body) * clamp01(depth / (0.8f * u) + 0.5f)
                    if (a <= 0.002f) continue
                    val blot = StageNoise.fbm2(x / (22f * u), y / (16f * u), salt + 99, 4)
                    val streak = StageNoise.fbm2(x / (2.2f * u), y / (9f * u), salt + 55, 2)
                    a *= 0.62f + 0.55f * blot + 0.22f * (streak - 0.5f)
                    c.over(y * w + x, a, color)
                }
            }
        }

        /** 一道横雾：纸色压在山脚上，把前后两层山隔开。 */
        private fun mist(c: InkCanvas, salt: Int, y0: Float, thick: Float, strength: Float) {
            val t = thick * u
            val from = max(0, (y0 - t * 2.6f).toInt())
            val to = min(h - 1, (y0 + t * 2.6f).toInt())
            for (y in from..to) {
                val dy = (y - y0) / t
                val band = exp(-dy * dy)
                for (x in 0 until w) {
                    val n = StageNoise.fbm2(x / (110f * u), y / (30f * u), salt, 3)
                    c.over(y * w + x, band * (0.35f + n) * strength, paper)
                }
            }
        }

        private fun mountains() {
            mountain(back, seed + 3, h * 0.80f, 230f * u, w * 0.33f, 3, side, 0.10f, 0.10f, 60f)
            mist(back, seed + 4, h * 0.78f, 18f, 0.75f)
            mountain(front, seed + 8, h * 0.90f, 150f * u, w * 0.25f, 3, side, 0.22f, 0.18f, 30f)
            mist(front, seed + 9, h * 0.90f, 14f, 0.8f)
            mountain(front, seed + 13, h * 1.01f, 110f * u, w * 0.2f, 2, -side, 0.34f, 0.26f, 18f)
        }

        // —— 水 ——

        /** 扁舟：细长船身、一个撑篙的人。 */
        private fun boat(c: InkCanvas, cx: Float, cy: Float) {
            val s = 1.6f * u
            val x0 = max(0, (cx - 10 * s).toInt())
            val x1 = min(w - 1, (cx + 10 * s).toInt())
            val y0 = max(0, (cy - 8 * s).toInt())
            val y1 = min(h - 1, (cy + 3 * s).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x - cx
                val dy = y - cy
                val hull = abs(dy) < 0.9f * s && abs(dx) < 9 * s && y > cy - 0.9f * s + (dx / (9 * s)).pow(2) * 1.5f * s
                val bx = x - (cx + 2 * s)
                val body = abs(bx) < 0.7f * s && y < cy && y > cy - 4.2f * s
                val head = bx * bx + (y - (cy - 4.8f * s)).pow(2) < (0.9f * s).pow(2)
                val pole = abs(bx - (y - (cy - 3 * s)) * -0.55f) < 0.25f * s && y > cy - 7 * s && y < cy + 2 * s
                val alpha = when {
                    hull -> 0.55f
                    body || head -> 0.6f
                    pole -> 0.4f
                    else -> 0f
                }
                c.over(y * w + x, alpha, ink)
            }
        }

        /** 一道横笔：两头尖、中间略粗，像毛笔轻轻一拖。 */
        private fun stroke(c: InkCanvas, x0: Float, y0: Float, length: Float, thick: Float, alpha: Float, bow: Float = 0f) {
            if (length < 1f) return
            val xa = max(0, x0.toInt())
            val xb = min(w - 1, (x0 + length).toInt())
            for (x in xa..xb) {
                val t = (x - x0) / length
                val taper = sin(clamp01(t) * PI.toFloat()).pow(0.7f)
                val yc = y0 - bow * sin(clamp01(t) * PI.toFloat())
                val half = thick * taper
                val ya = max(0, (yc - half - 1f).toInt())
                val yb = min(h - 1, (yc + half + 1f).toInt())
                for (y in ya..yb) {
                    val d = abs(y - yc)
                    val cover = clamp01(half + 0.5f - d)
                    if (cover > 0f) c.over(y * w + x, alpha * cover * (0.75f + 0.25f * taper), ink)
                }
            }
        }

        /** 水面：近岸处细长的横笔一道接一道，越往近处越疏越长；远山在水里的倒影被水纹切成断续的横条。 */
        private fun waterSurface(c: InkCanvas, salt: Int, horizon: Float, strength: Float) {
            val rows = 26
            for (r in 0 until rows) {
                val t = (r + StageNoise.hash(r, salt)) / rows
                val y = horizon + (h - horizon) * t.pow(1.7f)
                val count = 2 + (StageNoise.hash(r, salt + 1) * 3f).toInt()
                for (k in 0 until count) {
                    val len = (10f + 60f * t * (0.4f + 0.6f * StageNoise.hash(r * 7 + k, salt + 2))) * u
                    val x = StageNoise.hash(r * 7 + k, salt + 3) * (w + len) - len
                    stroke(c, x, y, len, (0.35f + 0.9f * t) * u, (0.07f + 0.14f * t) * strength)
                }
            }
        }

        /** 倒影：把远景里水平线以上的一段墨按水平线翻下来，压淡、按行断开。 */
        private fun reflection(c: InkCanvas, horizon: Float, depth: Float, strength: Float) {
            val hz = horizon.toInt()
            val maxD = (depth * u).toInt()
            for (d in 1 until maxD) {
                val src = hz - d
                val dst = hz + d
                if (src < 0 || dst >= h) continue
                // 水纹把倒影切成断续的横条。
                if (StageNoise.value1(d / (2.2f * u), seed + 501) < 0.42f) continue
                val fade = (1f - d / maxD.toFloat()) * strength
                for (x in 0 until w) {
                    val i = src * w + x
                    val a = c.a[i]
                    if (a <= 0.01f) continue
                    val wobble = (StageNoise.value1(x / (6f * u) + d * 0.7f, seed + 502) - 0.5f) * 3f * u
                    val xx = (x + wobble).toInt().coerceIn(0, w - 1)
                    c.over(dst * w + xx, a * fade, ink)
                }
            }
        }

        private fun water() {
            val horizon = h * 0.79f
            mountain(back, seed + 51, horizon - 2f * u, 120f * u, w * 0.3f, 3, side, 0.10f, 0.10f, 30f)
            mist(back, seed + 52, horizon - 4f * u, 9f, 0.75f)
            reflection(back, horizon, 46f, 0.45f)
            waterSurface(front, seed + 53, horizon + 3f * u, 1f)
            boat(front, if (side > 0) w * 0.34f else w * 0.66f, h * 0.87f)
        }

        private fun sea() {
            // 一线天水，远处几点岛影；浪是成排的短弧笔，近大远小、近疏远密。
            val horizon = h * 0.74f
            mountain(back, seed + 61, horizon + 1f * u, 36f * u, w * 0.12f, 2, side, 0.12f, 0.08f, 8f)
            for (x in 0 until w) {
                val y = horizon.toInt()
                if (y in 0 until h) back.over(y * w + x, 0.08f + 0.04f * StageNoise.value1(x / (40f * u), seed + 65), ink)
            }
            mist(back, seed + 62, horizon, 7f, 0.45f)
            val rows = 22
            for (r in 0 until rows) {
                val t = (r + StageNoise.hash(r, seed + 63)) / rows
                val y = horizon + 4f * u + (h - horizon) * t.pow(1.6f)
                val count = 3 + (StageNoise.hash(r, seed + 66) * 4f).toInt()
                for (k in 0 until count) {
                    val len = (8f + 46f * t * (0.5f + 0.5f * StageNoise.hash(r * 9 + k, seed + 67))) * u
                    val x = StageNoise.hash(r * 9 + k, seed + 68) * (w + len) - len
                    val bow = (1f + 5f * t) * u * (0.6f + 0.4f * StageNoise.hash(r * 9 + k, seed + 69))
                    stroke(front, x, y, len, (0.4f + 0.9f * t) * u, 0.08f + 0.16f * t, bow)
                }
            }
        }

        // —— 林 ——

        /** 远处一线树梢：高频起伏的山脊就是一排树冠。 */
        private fun treeLine(c: InkCanvas, salt: Int, base: Float, height: Float, edge: Float, body: Float) {
            val top = FloatArray(w) { x ->
                val crowns = StageNoise.fbm1(x / (7f * u), salt, 3)
                val rise = StageNoise.fbm1(x / (90f * u), salt + 1, 3)
                base - height * (0.45f + 0.55f * rise) - crowns * height * 0.35f
            }
            val minTop = max(0, top.min().toInt())
            for (y in minTop until h) for (x in 0 until w) {
                val depth = y - top[x]
                if (depth <= 0f) continue
                var a = (exp(-depth / (2.5f * u)) * edge + exp(-depth / (40f * u)) * body)
                a *= 0.7f + 0.5f * StageNoise.fbm2(x / (9f * u), y / (9f * u), salt + 9, 3)
                c.over(y * w + x, a, ink)
            }
        }

        /** 近处一棵树：一笔树干，几团点叶（点叶法）。 */
        private fun tree(c: InkCanvas, salt: Int, rootX: Float, rootY: Float, height: Float, tone: Float) {
            val lean = (StageNoise.hash(salt, 1) - 0.5f) * 0.25f
            val trunkW = (2.2f + 1.5f * StageNoise.hash(salt, 2)) * u
            val topY = rootY - height
            for (y in max(0, topY.toInt()) until min(h, rootY.toInt())) {
                val t = (rootY - y) / height
                val xc = rootX + (rootY - y) * lean + sin(t * 5f + salt) * 2f * u
                val half = trunkW * (1.1f - 0.6f * t)
                for (x in max(0, (xc - half).toInt())..min(w - 1, (xc + half).toInt())) c.over(y * w + x, tone * 0.9f, ink)
            }
            // 树冠：十几团小墨点组成的团块。
            val crownX = rootX + height * lean
            val crownY = topY + height * 0.1f
            val radius = height * 0.42f
            for (k in 0 until 140) {
                val ang = StageNoise.hash(k, salt + 3) * 2f * PI.toFloat()
                val rr = sqrt(StageNoise.hash(k, salt + 4)) * radius
                val px = crownX + cos(ang) * rr * 1.25f
                val py = crownY + sin(ang) * rr * 0.75f
                val dot = (1.6f + 2.6f * StageNoise.hash(k, salt + 5)) * u
                val a = tone * (0.35f + 0.5f * StageNoise.hash(k, salt + 6))
                for (y in max(0, (py - dot).toInt())..min(h - 1, (py + dot).toInt())) for (x in max(0, (px - dot * 1.4f).toInt())..min(w - 1, (px + dot * 1.4f).toInt())) {
                    val dx = (x - px) / (dot * 1.4f)
                    val dy = (y - py) / dot
                    if (dx * dx + dy * dy <= 1f) c.over(y * w + x, a, ink)
                }
            }
        }

        private fun forest() {
            treeLine(back, seed + 71, h * 0.84f, 70f * u, 0.07f, 0.06f)
            mist(back, seed + 72, h * 0.84f, 16f, 0.7f)
            treeLine(front, seed + 73, h * 0.95f, 55f * u, 0.16f, 0.12f)
            val x0 = if (side > 0) w * 0.80f else w * 0.20f
            tree(front, seed + 74, x0, h * 1.0f, 190f * u, 0.36f)
            tree(front, seed + 75, x0 + side * 34f * u, h * 1.02f, 140f * u, 0.26f)
        }

        /** 竹：一节一节的竿，节处留白加深，竿梢挑出细长的竹叶。 */
        private fun bamboo() {
            treeLine(back, seed + 81, h * 0.90f, 50f * u, 0.05f, 0.05f)
            mist(back, seed + 82, h * 0.90f, 14f, 0.6f)
            val baseX = if (side > 0) w * 0.76f else 0f
            for (k in 0 until 5) {
                val x = baseX + StageNoise.hash(k, seed + 83) * w * 0.22f
                val lean = (StageNoise.hash(k, seed + 84) - 0.5f) * 0.12f
                val half = (1.6f + StageNoise.hash(k, seed + 85) * 2.2f) * u
                val tone = 0.18f + 0.4f * StageNoise.hash(k, seed + 86)
                val top = h * (0.25f + 0.3f * StageNoise.hash(k, seed + 87))
                val nodeGap = 30f * u
                for (y in max(0, top.toInt()) until h) {
                    val xc = x + (y - h) * lean
                    val seg = (y - top) % nodeGap
                    val node = seg < 1.2f * u
                    val a = tone * (if (node) 0.3f else 1f) + (if (seg > nodeGap - 2f * u) 0.1f else 0f)
                    for (xx in max(0, (xc - half).toInt())..min(w - 1, (xc + half).toInt())) front.over(y * w + xx, a, ink)
                }
                // 竹叶：细长、两头尖的一撇。
                for (j in 0 until 9) {
                    val ly = top + StageNoise.hash(j, seed + 88 + k) * (h - top) * 0.6f
                    val lx = x + (ly - h) * lean
                    val flip = if (StageNoise.hash(j, seed + 89 + k) < 0.5f) 0f else PI.toFloat()
                    val ang = (if (StageNoise.hash(j, seed + 90 + k) < 0.5f) -0.5f else 0.5f) * (0.6f + StageNoise.hash(j, seed + 91 + k)) + flip
                    val len = (14f + StageNoise.hash(j, seed + 92 + k) * 18f) * u
                    leaf(front, lx, ly, ang, len, tone + 0.12f)
                }
            }
        }

        private fun leaf(c: InkCanvas, x0: Float, y0: Float, angle: Float, length: Float, tone: Float) {
            val ca = cos(angle)
            val sa = sin(angle)
            val reach = length + 3f * u
            for (y in max(0, (y0 - reach).toInt())..min(h - 1, (y0 + reach).toInt())) for (x in max(0, (x0 - reach).toInt())..min(w - 1, (x0 + reach).toInt())) {
                val dx = x - x0
                val dy = y - y0
                val along = dx * ca + dy * sa
                if (along <= 0f || along >= length) continue
                val across = -dx * sa + dy * ca
                val halfWidth = 2.6f * u * sin(clamp01(along / length) * PI.toFloat()).pow(0.8f)
                if (abs(across) < halfWidth) c.over(y * w + x, tone, ink)
            }
        }

        // —— 城 ——

        /**
         * 城市：远近两层楼影。远楼淡、密、一字排开在雾里；近楼浓、少，挤在一角（一角构图），
         * 顶上有退台与天线；入夜后近楼亮起零星的暖窗。另一侧一盏路灯的剪影。
         */
        private fun city(lit: Boolean) {
            val lamp = rgb(StagePigments.Gamboge)
            fun skyline(c: InkCanvas, salt: Int, base: Float, maxH: Float, minW: Float, maxW: Float, toneLo: Float, toneHi: Float, corner: Boolean, windows: Boolean) {
                var x = -6f * u
                var k = 0
                while (x < w) {
                    val bw = (minW + StageNoise.hash(k, salt + 1) * (maxW - minW)) * u
                    val cx = (x + bw / 2f) / w
                    // 一角构图：靠主体那侧高，另一侧矮下去。
                    val weight = if (!corner) 0.55f + 0.45f * StageNoise.hash(k, salt + 2) else {
                        val toward = if (side > 0) cx else 1f - cx
                        (0.12f + 0.88f * toward.pow(1.6f)) * (0.55f + 0.45f * StageNoise.hash(k, salt + 2))
                    }
                    val bh = maxH * weight
                    val tone = toneLo + (toneHi - toneLo) * StageNoise.hash(k, salt + 3)
                    val setback = StageNoise.hash(k, salt + 4) > 0.55f
                    val antenna = StageNoise.hash(k, salt + 5) > 0.82f
                    val top = base - bh
                    val x0 = max(0, x.toInt())
                    val x1 = min(w - 1, (x + bw).toInt())
                    for (y in max(0, top.toInt()) until h) {
                        // 退台：上部收窄一截。
                        val inset = if (setback && y < top + bh * 0.22f) bw * 0.22f else 0f
                        val fade = clamp01((y - (base - maxH)) / (maxH * 1.1f))
                        val a = tone * (0.55f + 0.45f * fade)
                        for (xx in max(x0, (x + inset).toInt())..min(x1, (x + bw - inset).toInt())) c.over(y * w + xx, a, ink)
                    }
                    if (antenna) {
                        val ax = (x + bw * 0.5f).toInt().coerceIn(0, w - 1)
                        for (y in max(0, (top - 16f * u).toInt()) until max(0, top.toInt())) c.over(y * w + ax, tone * 0.9f, ink)
                    }
                    if (windows && lit) {
                        val cols = max(1, (bw / (5f * u)).toInt())
                        val rowsN = max(1, (bh / (7f * u)).toInt())
                        for (r in 0 until rowsN) for (q in 0 until cols) {
                            if (StageNoise.hash(r * 31 + q, salt + 6 + k * 13) > 0.16f) continue
                            val wx = (x + 2.5f * u + q * 5f * u).toInt()
                            val wy = (top + 4f * u + r * 7f * u).toInt()
                            val a = 0.35f + 0.45f * StageNoise.hash(r * 31 + q, salt + 7 + k * 13)
                            for (yy in wy..wy + 1) for (xx in wx..wx + 1) if (xx in 0 until w && yy in 0 until h && xx <= x1) c.over(yy * w + xx, a, lamp)
                        }
                    }
                    x += bw + StageNoise.hash(k, salt + 8) * 2.5f * u
                    k++
                }
            }
            skyline(back, seed + 21, h * 0.93f, 120f * u, 10f, 22f, 0.05f, 0.08f, corner = false, windows = false)
            mist(back, seed + 30, h * 0.88f, 20f, 0.6f)
            skyline(front, seed + 41, h * 1.0f, 175f * u, 16f, 34f, 0.10f, 0.15f, corner = true, windows = true)
            mist(front, seed + 31, h * 0.99f, 10f, 0.35f)
            // 路灯：在楼群的另一侧，一根细杆、一个弯臂、一个灯罩。
            val px = if (side > 0) w * 0.13f else w * 0.87f
            val topY = h * 0.70f
            val dir = if (side > 0) 1f else -1f
            for (y in topY.toInt() until h) for (xx in (px - 0.8f * u).toInt()..(px + 0.8f * u).toInt()) if (xx in 0 until w) front.over(y * w + xx, 0.30f, ink)
            for (t in 0..40) {
                val f = t / 40f
                val ax = px + dir * f * 9f * u
                val ay = topY - sin(f * PI.toFloat() * 0.5f) * 5f * u
                for (yy in (ay - 0.8f * u).toInt()..(ay + 0.8f * u).toInt()) {
                    val xx = ax.toInt()
                    if (xx in 0 until w && yy in 0 until h) front.over(yy * w + xx, 0.30f, ink)
                }
            }
            val hx = px + dir * 9f * u
            for (yy in (topY - 5f * u).toInt()..(topY - 2f * u).toInt()) for (xx in (hx - 3f * u).toInt()..(hx + 3f * u).toInt()) {
                if (xx in 0 until w && yy in 0 until h) front.over(yy * w + xx, 0.32f, ink)
            }
            if (lit) {
                // 灯下一小团暖光（只在入夜时）。
                forDiscFront(hx, topY, 26f * u) { i, d -> front.over(i, clamp01(1f - d / (26f * u)).pow(2f) * 0.22f, lamp) }
            }
        }

        private inline fun forDiscFront(cx: Float, cy: Float, radius: Float, block: (Int, Float) -> Unit) = forDisc(cx, cy, radius, block)

        // —— 室内 ——

        /** 室内：一扇窗斜投在纸面上的光影——现代是百叶，古风是花窗格。光本身略提亮，窗棂的影子是淡墨。 */
        private fun interior() {
            val ancient = key.era == StageEra.ANCIENT
            val night = key.time == StageTime.NIGHT
            val light = rgb(if (night) Color(0xFFDDE4EF) else Color(0xFFFFECC4))
            val x0 = if (side > 0) w * 0.40f else w * 0.05f
            val y0 = h * 0.06f
            val ww = w * 0.55f
            val hh = h * 0.42f
            val skew = 0.35f * side
            val shade = if (dark) rgb(Color.Black) else ink
            for (y in 0 until h) for (x in 0 until w) {
                val uu = (x - x0 - (y - y0) * skew) / ww
                val vv = (y - y0) / hh
                if (uu <= 0f || uu >= 1f || vv <= 0f || vv >= 1f) continue
                // 边缘柔：光斑四周慢慢化开；离窗越远越淡。
                val soft = clamp01(minOf(uu * 3.2f, (1f - uu) * 3.2f, vv * 3.2f, (1f - vv) * 3.2f)).let { it * it * (3f - 2f * it) }
                val falloff = 1f - 0.55f * vv
                val shadow = if (!ancient) {
                    // 百叶：正弦带柔化成渐变的条，不是硬边的线。
                    val band = (sin(vv * PI.toFloat() * 16f) * 0.5f + 0.5f)
                    val bars = clamp01((band - 0.42f) / 0.3f).let { it * it * (3f - 2f * it) }
                    val mullion = clamp01(1f - abs(uu - 0.5f) / 0.018f)
                    clamp01(bars * 0.85f + mullion)
                } else {
                    val gu = (uu * 8f) % 1f
                    val gv = (vv * 8f) % 1f
                    val lattice = clamp01(1f - minOf(gu, 1f - gu, gv, 1f - gv) / 0.06f)
                    val diag = clamp01(1f - abs(((uu * 8f + vv * 8f) % 2f) - 1f) / 0.07f) * 0.6f
                    clamp01(lattice + diag)
                }
                val i = y * w + x
                val k = soft * falloff
                if (!dark) back.over(i, k * (1f - shadow) * (if (night) 0.05f else 0.09f), light)
                back.over(i, k * shadow * (if (dark) 0.08f else 0.045f), shade)
                if (dark) back.over(i, k * (1f - shadow) * 0.045f, light)
            }
        }

        // —— 楼阁 ——

        /**
         * 古风：屋檐从上方一角探进来——檐口一道弧线，末端飞檐上翘；檐上瓦垄顺着弧线一行行排开，檐口一排瓦当；
         * 远处雾里一座塔影与几道殿脊。
         */
        private fun classical() {
            mountain(back, seed + 101, h * 0.92f, 60f * u, w * 0.3f, 2, -side, 0.06f, 0.06f, 30f)
            val towerX = if (side > 0) w * 0.30f else w * 0.70f
            for (level in 0 until 5) {
                val y = h * 0.82f - level * 20f * u
                val half = (21f - level * 3f) * u
                // 每层一道翘角的檐：中间平、两端上挑。
                for (xx in max(0, (towerX - half).toInt())..min(w - 1, (towerX + half).toInt())) {
                    val t = abs(xx - towerX) / half
                    val lift = t.pow(3f) * 3.5f * u
                    for (yy in max(0, (y - 2.2f * u - lift).toInt())..min(h - 1, (y - lift).toInt())) back.over(yy * w + xx, 0.11f, ink)
                }
                for (yy in max(0, y.toInt())..min(h - 1, (y + 15f * u).toInt())) {
                    for (xx in max(0, (towerX - half * 0.5f).toInt())..min(w - 1, (towerX + half * 0.5f).toInt())) back.over(yy * w + xx, 0.06f, ink)
                }
            }
            mist(back, seed + 102, h * 0.86f, 20f, 0.6f)
            // 近处屋檐：从上方一角伸出，L 是檐长。
            val length = w * 0.66f
            fun eave(t: Float): Float {
                val tt = clamp01(t)
                // 檐口：先微微下垂，到末端急翘起来（飞檐）。
                return h * 0.115f + sin(tt * PI.toFloat()) * 4f * u - tt.pow(5f) * 22f * u
            }
            for (x in 0 until w) {
                val xs = if (side > 0) w - 1 - x.toFloat() else x.toFloat()
                val t = xs / length
                if (t > 1.02f) continue
                val edge = eave(t)
                val roofTop = edge - (18f + 26f * (1f - t)) * u
                for (y in 0 until min(h, (edge + 1f).toInt())) {
                    val i = y * w + x
                    if (y < roofTop) continue
                    // 瓦垄：沿檐长方向等距的竖向笔触，越靠檐口越清楚。
                    val near = clamp01((y - roofTop) / (edge - roofTop))
                    val ridge = sin(xs / (5.5f * u) * PI.toFloat()).let { it * it }
                    val a = 0.10f + 0.10f * ridge * near
                    front.over(i, a, ink)
                }
                // 檐口的深色边与一排瓦当。
                for (y in max(0, (edge - 1.6f * u).toInt())..min(h - 1, (edge + 1.2f * u).toInt())) front.over(y * w + x, 0.32f, ink)
                if ((xs / (5.5f * u)).toInt() % 2 == 0 && t < 0.97f) {
                    val cy = edge + 2.4f * u
                    for (y in max(0, (cy - 1.4f * u).toInt())..min(h - 1, (cy + 1.4f * u).toInt())) front.over(y * w + x, 0.22f, ink)
                }
                // 檐下一道梁。
                if (t < 0.9f) for (y in max(0, (edge + 9f * u).toInt())..min(h - 1, (edge + 11f * u).toInt())) front.over(y * w + x, 0.16f * (1f - t), ink)
            }
            // 檐角下挂一盏灯笼，入夜时点亮。
            val tipX = if (side > 0) w - length * 0.9f else length * 0.9f
            val tipY = eave(0.9f) + 14f * u
            val lantern = rgb(StagePigments.Vermilion)
            for (y in (tipY - 10f * u).toInt()..tipY.toInt()) {
                val xx = tipX.toInt()
                if (xx in 0 until w && y in 0 until h) front.over(y * w + xx, 0.25f, ink)
            }
            forDisc(tipX, tipY + 6f * u, 6f * u) { i, d -> front.over(i, clamp01((6f * u - d) / (1.2f * u)) * 0.55f, lantern) }
            if (key.time == StageTime.NIGHT || key.time == StageTime.DUSK) {
                val glow = rgb(StagePigments.Gamboge)
                forDisc(tipX, tipY + 6f * u, 34f * u) { i, d -> front.over(i, clamp01(1f - d / (34f * u)).pow(2f) * 0.16f, glow) }
            }
        }

        /** 校园：一栋长长的教学楼（一排排浅窗）、旗杆与小旗、另一侧一棵树。 */
        private fun campus() {
            treeLine(back, seed + 111, h * 0.90f, 45f * u, 0.05f, 0.05f)
            val left = if (side > 0) w * 0.50f else w * 0.04f
            val right = left + w * 0.46f
            val top = h * 0.81f
            val roof = rgb(StagePigments.Ink)
            for (y in max(0, top.toInt()) until h) for (x in max(0, left.toInt())..min(w - 1, right.toInt())) {
                val row = (y - top) / (13f * u)
                val inRow = (row % 1f) in 0.3f..0.75f
                val col = ((x - left) / (9f * u)) % 1f
                val window = inRow && col in 0.2f..0.8f && row < 7f
                val fade = clamp01((y - top) / (h * 0.25f))
                val a = if (window) 0.02f else 0.06f * (0.7f + 0.3f * fade)
                back.over(y * w + x, a, ink)
            }
            for (x in max(0, (left - 3f * u).toInt())..min(w - 1, (right + 3f * u).toInt())) for (y in max(0, (top - 2f * u).toInt())..max(0, top.toInt())) back.over(y * w + x, 0.14f, roof)
            // 旗杆与小旗。
            val poleX = (if (side > 0) left - 14f * u else right + 14f * u).coerceIn(2f, w - 3f)
            for (y in max(0, (top - 48f * u).toInt()) until h) back.over(y * w + poleX.toInt(), 0.16f, ink)
            val flag = rgb(StagePigments.Vermilion)
            for (y in max(0, (top - 47f * u).toInt())..max(0, (top - 39f * u).toInt())) for (dx in 1..(13f * u).toInt()) {
                val xx = poleX.toInt() + dx
                val wave = sin(dx / (4f * u)) * 1.2f * u
                if (xx in 0 until w && (y + wave).toInt() in 0 until h) back.over((y + wave).toInt() * w + xx, 0.35f, flag)
            }
            mist(back, seed + 112, h * 0.90f, 12f, 0.45f)
            val x0 = if (side > 0) w * 0.16f else w * 0.84f
            tree(front, seed + 113, x0, h * 1.0f, 150f * u, 0.30f)
        }

        private fun village() {
            mountain(back, seed + 121, h * 0.80f, 90f * u, w * 0.5f, 3, 0, 0.07f, 0.07f, 40f)
            mist(back, seed + 122, h * 0.80f, 14f, 0.7f)
            // 两三间小屋：人字顶、白墙留白，屋后一缕炊烟。
            val hx = if (side > 0) w * 0.64f else w * 0.14f
            for (k in 0 until 3) {
                val cx = hx + k * 34f * u
                val base = h * (0.88f + 0.01f * k)
                val half = (13f + 3f * k) * u
                val roofTop = base - 22f * u
                for (y in max(0, roofTop.toInt()) until min(h, base.toInt())) for (x in max(0, (cx - half - 4f * u).toInt())..min(w - 1, (cx + half + 4f * u).toInt())) {
                    val dx = abs(x - cx)
                    val roofLine = roofTop + dx * 0.55f
                    val i = y * w + x
                    if (y < roofTop + 9f * u && y >= roofLine) front.over(i, 0.30f, ink)
                    else if (y >= roofTop + 9f * u && dx < half) front.over(i, if (dx > half - 1.4f * u) 0.22f else 0.03f, ink)
                }
                if (k == 1) {
                    for (y in max(0, (roofTop - 60f * u).toInt()) until max(0, roofTop.toInt())) {
                        val t = (roofTop - y) / (60f * u)
                        val sx = cx + sin(t * 5f) * 6f * u * t
                        for (x in max(0, (sx - 3f * u).toInt())..min(w - 1, (sx + 3f * u).toInt())) {
                            val fall = clamp01(1f - abs(x - sx) / (3f * u))
                            front.over(y * w + x, 0.08f * (1f - t) * fall, ink)
                        }
                    }
                }
            }
            // 田垄：几道微微起伏的横线。
            for (k in 0 until 6) {
                val y0 = h * (0.92f + 0.015f * k)
                for (x in 0 until w) {
                    val y = (y0 + sin(x / (50f * u) + k) * 2f * u).toInt()
                    if (y in 0 until h) front.over(y * w + x, 0.08f, ink)
                }
            }
        }

        /** 沙丘：尖的脊线（一侧迎光留白、一侧背光淡赭），三层由远及近；起伏用噪声，不是正弦。 */
        private fun desert() {
            val ochre = rgb(StagePigments.Ochre)
            for (layer in 0 until 3) {
                val c = if (layer == 0) back else front
                val base = h * (0.80f + 0.07f * layer)
                val amp = (30f + 18f * layer) * u
                val scale = (230f - 40f * layer) * u
                // 两个八度的值噪声折成尖脊：脊线利落、坡面圆润（沙丘不是锯齿山）。
                val crest = FloatArray(w) { x ->
                    val n = StageNoise.fbm1(x / scale + layer * 3.1f, seed + 140 + layer, 2)
                    val ridged = 1f - abs(n * 2f - 1f)
                    base - amp * ridged.pow(1.25f)
                }
                for (x in 1 until w - 1) {
                    // 背光程度随坡度连续变化，不按正负硬切，避免竖条纹。
                    val slope = (crest[x + 1] - crest[x - 1]) / (2f * u)
                    val shadeSide = clamp01(0.5f - slope * 1.6f).let { it * it * (3f - 2f * it) }
                    for (y in max(0, crest[x].toInt()) until h) {
                        val depth = y - crest[x]
                        val edge = exp(-depth / (2.4f * u)) * (0.16f + 0.05f * layer)
                        val fall = clamp01(1f - depth / (amp * 1.8f) + 0.35f)
                        c.over(y * w + x, edge, ink)
                        c.over(y * w + x, 0.075f * shadeSide * (0.6f + 0.2f * layer) * fall, ochre)
                        c.over(y * w + x, 0.02f * (1f - shadeSide) * fall, ink)
                    }
                }
                if (layer == 0) mist(back, seed + 131, base, 10f, 0.5f)
            }
        }
    }
}

/**
 * 舞台上的水墨布景：随原文的布景、时段与天气换画，换时慢慢化开；镜头以极慢的速度呼吸，远近两层错开成纵深。
 * 光源（烛火、灯光、篝火、霓虹）与情绪颜料在运行时叠加，烛火会摇曳。
 */
@Composable
internal fun StageScenery(
    scene: StageScene?,
    era: StageEra?,
    palette: StagePalette,
    seed: Int,
    animate: Boolean,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = (constraints.maxWidth / SCENERY_SCALE).coerceAtLeast(1)
        val height = (constraints.maxHeight / SCENERY_SCALE).coerceAtLeast(1)
        val key = remember(scene?.setting, scene?.time, scene?.weather, era, palette.isDark, palette.ink, palette.background, width, height, seed) {
            StageSceneryKey(
                setting = scene?.setting,
                time = scene?.time,
                overcast = scene?.weather == StageWeatherCue.RAIN || scene?.weather == StageWeatherCue.STORM || scene?.weather == StageWeatherCue.SNOW,
                era = era,
                dark = palette.isDark,
                ink = palette.sceneryInk.toArgb(),
                paper = palette.background.toArgb(),
                width = width,
                height = height,
                seed = seed
            )
        }
        var shown by remember { mutableStateOf(StageSceneryCache.get(key)) }
        var leaving by remember { mutableStateOf<StageSceneryLayers?>(null) }
        val mix = remember { Animatable(1f) }
        LaunchedEffect(key) {
            val next = StageSceneryCache.get(key) ?: withContext(Dispatchers.Default) {
                StageSceneryPainter.paint(key).also { StageSceneryCache.put(key, it) }
            }
            if (next === shown) return@LaunchedEffect
            leaving = shown
            shown = next
            mix.snapTo(0f)
            mix.animateTo(1f, tween(if (leaving == null) 900 else 1_600, easing = FastOutSlowInEasing))
            leaving = null
        }
        // 镜头呼吸：每秒十五次就够平滑（移动极慢），比逐帧省电。
        var time by remember { mutableFloatStateOf(0f) }
        if (animate) LaunchedEffect(Unit) {
            val start = System.nanoTime()
            while (isActive) {
                time = (System.nanoTime() - start) / 1e9f
                delay(66)
            }
        }
        val density = LocalDensity.current
        val drift = with(density) { 5f * this.density }
        val light = scene?.light
        Canvas(Modifier.fillMaxSize()) {
            val t = time
            val dxBack = sin(t * 2f * PI.toFloat() / 47f) * drift * 0.5f
            val dxFront = sin(t * 2f * PI.toFloat() / 47f) * drift * 1.2f
            val dy = sin(t * 2f * PI.toFloat() / 61f) * drift * 0.4f
            val m = mix.value
            leaving?.let { drawLayers(it, 1f - m, dxBack, dxFront, dy, rise = 0f) }
            shown?.let { drawLayers(it, m, dxBack, dxFront, dy, rise = (1f - m) * 10f * density.density) }
            if (light != null) drawLight(light, t, palette)
        }
    }
}

/** 两层位图放大铺满（略放大 4%，镜头微动时边缘不露底），近景位移更大。 */
private fun DrawScope.drawLayers(layers: StageSceneryLayers, alpha: Float, dxBack: Float, dxFront: Float, dy: Float, rise: Float) {
    if (alpha <= 0.01f) return
    val dst = IntSize((size.width * 1.04f).toInt(), (size.height * 1.04f).toInt())
    val origin = IntOffset((-size.width * 0.02f).toInt(), (-size.height * 0.02f).toInt())
    withTransform({ translate(dxBack, dy + rise * 0.5f) }) {
        drawImage(layers.back, dstOffset = origin, dstSize = dst, alpha = alpha, filterQuality = FilterQuality.Medium)
    }
    withTransform({ translate(dxFront, dy * 1.6f + rise) }) {
        drawImage(layers.front, dstOffset = origin, dstSize = dst, alpha = alpha, filterQuality = FilterQuality.Medium)
    }
}

/** 光源：烛火与篝火摇曳，灯光恒定，霓虹带一点冷暖交替。都是放在下方一角的柔光。 */
private fun DrawScope.drawLight(light: StageLight, t: Float, palette: StagePalette) {
    val flicker = when (light) {
        StageLight.CANDLE, StageLight.FIRE -> 0.82f + 0.18f * (StageNoise.fbm1(t * 6f, 41, 3) * 2f - 0.5f).coerceIn(0f, 1f)
        else -> 1f
    }
    val (color, radius, center, alpha) = when (light) {
        StageLight.CANDLE -> LightSpec(StagePigments.Gamboge, size.minDimension * 0.55f, Offset(size.width * 0.5f, size.height * 0.96f), 0.18f)
        StageLight.LAMP -> LightSpec(Color(0xFFF2D49A), size.minDimension * 0.9f, Offset(size.width * 0.82f, size.height * 0.10f), 0.14f)
        StageLight.FIRE -> LightSpec(Color(0xFFE07A3C), size.minDimension * 0.85f, Offset(size.width * 0.5f, size.height * 1.02f), 0.22f)
        StageLight.NEON -> LightSpec(if (sin(t * 0.9f) > 0f) Color(0xFFB45CC8) else Color(0xFF4FA3C7), size.minDimension * 0.9f, Offset(size.width * 0.9f, size.height * 0.85f), 0.12f)
    }
    val a = alpha * flicker * (if (palette.isDark) 1.3f else 1f)
    drawRect(Brush.radialGradient(listOf(color.copy(alpha = a), color.copy(alpha = a * 0.35f), Color.Transparent), center, radius))
}

private data class LightSpec(val color: Color, val radius: Float, val center: Offset, val alpha: Float)

/** 情绪颜料：在布景之上很淡地染一层（阴森偏石绿、紧张偏朱砂、温情偏胭脂、悲伤偏花青、温暖偏藤黄）。 */
internal fun moodPigment(mood: StageMood?, dark: Boolean): Color = when (mood) {
    StageMood.EERIE -> StagePigments.Malachite.copy(alpha = if (dark) 0.16f else 0.08f)
    StageMood.TENSE -> StagePigments.Vermilion.copy(alpha = if (dark) 0.12f else 0.06f)
    StageMood.TENDER -> StagePigments.Rouge.copy(alpha = if (dark) 0.10f else 0.05f)
    StageMood.SORROW -> StagePigments.Indigo.copy(alpha = if (dark) 0.16f else 0.07f)
    StageMood.WARM -> StagePigments.Gamboge.copy(alpha = if (dark) 0.08f else 0.07f)
    null -> Color.Transparent
}
