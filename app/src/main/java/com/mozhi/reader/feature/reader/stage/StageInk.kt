package com.mozhi.reader.feature.reader.stage

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 内置行书（志莽行书，OFL）：章名、海报、拟声、喊叫、书信与印章。 */
internal val StageBrushFamily: FontFamily = FontFamily(Font(R.font.stage_brush))

/**
 * 印章：方印、四角微圆、边缘不齐，印泥里透出几点纸色；字是阴刻（纸色）。
 * [stamp] 时像盖章一样从大到小压下去、略微回弹。印的歪斜由字决定，同一个人每次都一样。
 */
@Composable
internal fun StageSeal(
    text: String,
    color: Color,
    paper: Color,
    fonts: StageFonts,
    size: Dp,
    stamp: Boolean,
    key: Any,
    modifier: Modifier = Modifier,
    outline: Boolean = false
) {
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val press = remember(key) { Animatable(if (stamp) 0f else 1f) }
    LaunchedEffect(key) {
        if (press.value < 1f) press.animateTo(1f, tween(320, easing = CubicBezierEasing(0.2f, 1.5f, 0.45f, 1f)))
    }
    val seed = text.hashCode()
    val tilt = (StageNoise.hash(seed, 7) - 0.5f) * 9f
    Canvas(modifier.size(size)) {
        val p = press.value
        val scale = 1.45f - 0.45f * p
        withTransform({
            scale(scale, scale, pivot = center)
            rotate(tilt, pivot = center)
        }) {
            val alpha = (p * 2f).coerceAtMost(1f)
            val path = sealPath(this.size, seed)
            if (outline) {
                drawPath(path, color.copy(alpha = 0.8f * alpha), style = Stroke(1.2.dp.toPx()))
            } else {
                drawPath(path, color.copy(alpha = 0.92f * alpha))
            }
            val glyphSize = (this.size.minDimension * (if (text.length > 1) 0.46f else 0.70f)).toSp()
            val layout = measurer.measure(
                text,
                TextStyle(fontFamily = fonts.brush ?: fonts.body, fontSize = glyphSize, fontWeight = if (fonts.brush == null) FontWeight.Bold else FontWeight.Normal, color = if (outline) color else paper)
            )
            drawText(
                layout,
                topLeft = Offset((this.size.width - layout.size.width) / 2f, (this.size.height - layout.size.height) / 2f),
                alpha = alpha
            )
            if (!outline) {
                // 印泥斑驳：几点纸色透出来。
                for (k in 0 until 16) {
                    val x = StageNoise.hash(k, seed + 11) * this.size.width
                    val y = StageNoise.hash(k, seed + 12) * this.size.height
                    val r = (0.4f + 1.4f * StageNoise.hash(k, seed + 13).pow(3)) * this.size.minDimension / 22f
                    drawCircle(paper.copy(alpha = (0.35f + 0.5f * StageNoise.hash(k, seed + 14)) * alpha), r, Offset(x, y))
                }
            }
        }
    }
}

/** 印的外形：圆角方形，沿边缘取点、按字的散列微微内外抖动，像真的钤出来的边。 */
private fun sealPath(size: Size, seed: Int): Path {
    val w = size.width
    val h = size.height
    val r = min(w, h) * 0.14f
    val jitter = min(w, h) * 0.035f
    val points = 44
    val path = Path()
    for (i in 0 until points) {
        val t = i / points.toFloat()
        val (x, y) = roundedRectPoint(t, w, h, r)
        val n = (StageNoise.hash(i, seed + 21) - 0.5f) * 2f * jitter
        val cx = w / 2f
        val cy = h / 2f
        val dx = x - cx
        val dy = y - cy
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val px = x + dx / len * n
        val py = y + dy / len * n
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    return path
}

/** 圆角矩形周长上第 t（0..1）处的点。 */
private fun roundedRectPoint(t: Float, w: Float, h: Float, r: Float): Pair<Float, Float> {
    val straightW = w - 2 * r
    val straightH = h - 2 * r
    val arc = (PI * r / 2).toFloat()
    val total = 2 * straightW + 2 * straightH + 4 * arc
    var d = t * total
    fun corner(cx: Float, cy: Float, start: Float, dd: Float): Pair<Float, Float> {
        val a = start + dd / r
        return (cx + cos(a) * r) to (cy + sin(a) * r)
    }
    if (d < straightW) return (r + d) to 0f
    d -= straightW
    if (d < arc) return corner(w - r, r, (-PI / 2).toFloat(), d)
    d -= arc
    if (d < straightH) return w to (r + d)
    d -= straightH
    if (d < arc) return corner(w - r, h - r, 0f, d)
    d -= arc
    if (d < straightW) return (w - r - d) to h
    d -= straightW
    if (d < arc) return corner(r, h - r, (PI / 2).toFloat(), d)
    d -= arc
    if (d < straightH) return 0f to (h - r - d)
    d -= straightH
    return corner(r, r, PI.toFloat(), d)
}

/** 印上刻什么字：认得的名字刻姓（第一个字），描述性的称呼刻最后一个字（「撑黑伞的男人」刻「人」）。 */
internal fun sealText(label: String, knownName: Boolean): String {
    val clean = label.filter { it.isLetterOrDigit() }
    if (clean.isEmpty()) return "?"
    return if (knownName) clean.take(1) else clean.takeLast(1)
}

/** 溅墨：围着一个中心洒下的大小墨点（幂律大小、近密远疏），给海报与拟声加一点笔势。确定性：同一句每次一样。 */
internal fun DrawScope.inkSplatter(center: Offset, spread: Float, color: Color, seed: Int, count: Int, progress: Float) {
    if (progress <= 0f) return
    for (k in 0 until count) {
        val ang = StageNoise.hash(k, seed + 1) * 2f * PI.toFloat()
        val dist = spread * (0.35f + 0.9f * StageNoise.hash(k, seed + 2)).pow(1.3f)
        val r = (1.2f + 9f * StageNoise.hash(k, seed + 3).pow(4f)) * density
        // 落点按顺序出现：先近后远，像一甩笔洒出去。
        val local = ((progress - k / count.toFloat() * 0.5f) / 0.5f).coerceIn(0f, 1f)
        if (local <= 0f) continue
        val pos = Offset(center.x + cos(ang) * dist * (0.7f + 0.3f * local), center.y + sin(ang) * dist * 0.8f * (0.7f + 0.3f * local))
        drawCircle(color.copy(alpha = (0.18f + 0.45f * StageNoise.hash(k, seed + 4)) * local), r * (0.6f + 0.4f * local), pos)
    }
}

/** 一团毛边墨块的透明度蒙版（256 像素），化开转场与开章墨晕共用；按种子缓存。 */
private object StageBlots {
    private val cache = HashMap<Int, ImageBitmap>()

    fun get(seed: Int): ImageBitmap = synchronized(cache) {
        cache.getOrPut(seed) {
            val n = 256
            val px = IntArray(n * n)
            for (y in 0 until n) for (x in 0 until n) {
                val dx = (x - n / 2f) / (n / 2f)
                val dy = (y - n / 2f) / (n / 2f)
                val d = sqrt(dx * dx + dy * dy)
                val ang = kotlin.math.atan2(dy, dx)
                // 边缘起伏：低频大起伏 + 高频毛刺。
                val edge = 0.78f + 0.14f * StageNoise.fbm1((ang / (2f * PI.toFloat()) + 0.5f) * 7f, seed, 4) +
                    0.05f * StageNoise.fbm1((ang / (2f * PI.toFloat()) + 0.5f) * 40f, seed + 3, 2)
                val body = ((edge - d) / 0.06f).coerceIn(0f, 1f)
                val inner = 0.82f + 0.18f * StageNoise.fbm2(x / 22f, y / 22f, seed + 5, 3)
                val a = (body * inner * 255f).toInt().coerceIn(0, 255)
                px[y * n + x] = (a shl 24) or 0xFFFFFF
            }
            Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

/** 画一团墨块：以 [center] 为心、半径 [radius]，颜色 [color]（蒙版是白的，用 tint 着色）。 */
internal fun DrawScope.drawBlot(seed: Int, center: Offset, radius: Float, color: Color, alpha: Float) {
    if (alpha <= 0.005f || radius <= 1f) return
    val image = StageBlots.get(seed)
    val side = (radius * 2f).toInt().coerceAtLeast(2)
    drawImage(
        image,
        dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()),
        dstSize = IntSize(side, side),
        alpha = alpha,
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)
    )
}

/** 一笔飞白横画的蒙版（512×56）：许多根长短不一的「笔毛」并排拖过，中间有断续的飞白，两头收锋。按种子缓存。 */
private object StageSweeps {
    private val cache = HashMap<Int, ImageBitmap>()

    fun get(seed: Int): ImageBitmap = synchronized(cache) {
        cache.getOrPut(seed) {
            val w = 512
            val h = 56
            val px = IntArray(w * h)
            val bristles = 34
            for (k in 0 until bristles) {
                // 每根笔毛：一条略弯的横线，起止不齐，按噪声断续（飞白）。
                val y0 = h * (0.18f + 0.64f * k / (bristles - 1)) + (StageNoise.hash(k, seed + 1) - 0.5f) * 3f
                val start = (StageNoise.hash(k, seed + 2) * 0.12f * w).toInt()
                val end = (w - StageNoise.hash(k, seed + 3) * 0.18f * w).toInt()
                val tone = 0.55f + 0.45f * StageNoise.hash(k, seed + 4)
                for (x in start until end) {
                    val t = x / w.toFloat()
                    // 收锋：两头细；笔画中段略往上拱。
                    val taper = (kotlin.math.sin(t * PI.toFloat()).coerceAtLeast(0f)).pow(0.35f)
                    if (kotlin.math.abs(y0 - h / 2f) > h * 0.42f * taper) continue
                    val dry = StageNoise.value1(x / 9f + k * 7.3f, seed + 5)
                    if (dry < 0.18f + 0.35f * t * t) continue
                    val y = (y0 - kotlin.math.sin(t * PI.toFloat()) * 3f).toInt()
                    for (dy in 0..1) {
                        val yy = y + dy
                        if (yy !in 0 until h) continue
                        val a = (tone * (if (dy == 0) 1f else 0.5f) * 255f).toInt().coerceIn(0, 255)
                        val i = yy * w + x
                        if (a > (px[i] ushr 24)) px[i] = (a shl 24) or 0xFFFFFF
                    }
                }
            }
            Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    }
}

/**
 * 场景分隔的转场：一笔飞白横画从左往右扫过屏幕中线（像书里分节处的一道墨线），旧的一场随之淡出，
 * 停一瞬后这一笔慢慢淡去，露出下一场。[progress] 0→1 是落笔，1→2 是淡去；由舞台驱动。
 */
@Composable
internal fun StageInkWipe(progress: Float, color: Color, seed: Int) {
    if (progress <= 0f || progress >= 2f) return
    Canvas(Modifier.fillMaxSize()) {
        val stroke = StageSweeps.get(seed)
        val width = size.width * 0.82f
        val height = width * 56f / 512f
        val left = (size.width - width) / 2f
        val top = size.height * 0.46f - height / 2f
        val draw = min(progress, 1f)
        val eased = 1f - (1f - draw).pow(3)
        val fade = if (progress > 1f) 1f - (progress - 1f) else 1f
        // 落笔时从左往右露出；淡去时整笔变淡。
        clipRect(left = left, top = 0f, right = left + width * eased, bottom = size.height) {
            drawImage(
                stroke,
                dstOffset = IntOffset(left.toInt(), top.toInt()),
                dstSize = IntSize(width.toInt(), height.toInt()),
                alpha = 0.85f * fade,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color)
            )
        }
        // 笔锋起处一点浓墨。
        if (progress < 1.3f) drawCircle(color.copy(alpha = 0.6f * fade * (1f - eased * 0.4f)), height * 0.16f, Offset(left + height * 0.25f, top + height * 0.52f))
    }
}

/** 开章墨滴落到纸上的时刻（占整段开章进度的比例）。 */
internal const val INK_DROP_IMPACT = 0.22f

/**
 * 开章：一滴墨从上方落下（应用图标里那滴墨），落在纸上荡开两圈涟漪，洇成一团淡墨，章名从墨里显出来。
 * 进度 [progress]（0→1）由章名卡驱动；章名本身由章名卡绘制。
 */
internal fun DrawScope.drawInkDrop(progress: Float, target: Offset, color: Color, seed: Int) {
    if (progress >= 1f) return
    run {
        val fall = (progress / INK_DROP_IMPACT).coerceIn(0f, 1f)
        val impact = ((progress - INK_DROP_IMPACT) / (1f - INK_DROP_IMPACT)).coerceIn(0f, 1f)
        if (fall < 1f) {
            // 加速下落的墨滴：上尖下圆。
            val y = -size.height * 0.08f + (target.y + size.height * 0.08f) * fall * fall
            val r = 7.dp.toPx()
            val drop = Path().apply {
                moveTo(target.x, y - r * 2.6f)
                cubicTo(target.x + r * 0.2f, y - r * 1.4f, target.x + r, y - r * 0.6f, target.x + r, y)
                cubicTo(target.x + r, y + r * 0.6f, target.x + r * 0.55f, y + r, target.x, y + r)
                cubicTo(target.x - r * 0.55f, y + r, target.x - r, y + r * 0.6f, target.x - r, y)
                cubicTo(target.x - r, y - r * 0.6f, target.x - r * 0.2f, y - r * 1.4f, target.x, y - r * 2.6f)
                close()
            }
            drawPath(drop, color.copy(alpha = 0.9f))
        } else {
            // 涟漪：两圈细线向外荡开、变淡。
            for (k in 0 until 2) {
                val t = ((impact - k * 0.12f) / 0.6f).coerceIn(0f, 1f)
                if (t <= 0f || t >= 1f) continue
                val radius = 10.dp.toPx() + t * size.minDimension * (0.26f + 0.1f * k)
                drawCircle(color.copy(alpha = 0.22f * (1f - t) * (1f - t)), radius, target, style = Stroke((1.1f - 0.6f * t).dp.toPx()))
            }
            // 墨晕：迅速洇开，再慢慢淡去（到动画结束正好淡尽，不会突然消失）。
            val bloom = 1f - exp(-impact * 4.5f)
            val alpha = 0.10f * (1f - impact).pow(0.8f)
            drawBlot(seed, target, size.minDimension * (0.06f + 0.36f * bloom), color, alpha)
            drawCircle(color.copy(alpha = 0.5f * (1f - impact).pow(2)), 5.dp.toPx() * (1f - impact), target)
        }
    }
}

/** 竖排一列字的布局：标点缩小靠右上、破折号与省略号转 90°。 */
internal object StageVertical {
    const val ROTATED = "…—～-"
    const val SMALL_MARKS = "，。、"
    private val latin = Regex("[A-Za-z0-9]{2,}")

    /** 只有全是汉字与中文标点、且不太长时才竖排（西文、数字串横排更好读）。 */
    fun suits(text: String, max: Int = 16): Boolean = text.length in 1..max && !latin.containsMatchIn(text)
}

/** 带墨晕的一个大字：用于竖排章名与海报。进度只在绘制阶段读取。 */
internal fun DrawScope.drawInkGlyph(layout: androidx.compose.ui.text.TextLayoutResult, topLeft: Offset, progress: Float, color: Color, emPx: Float) {
    if (progress <= 0f) return
    val pose = StageGlyphMotion.ink(progress, false)
    if (pose.bleedAlpha > 0.01f) {
        drawText(layout, color = Color.Transparent, topLeft = topLeft, shadow = Shadow(color.copy(alpha = pose.bleedAlpha.coerceAtMost(0.95f)), Offset.Zero, (pose.bleedEm * emPx).coerceAtLeast(1f)))
    }
    drawText(layout, topLeft = Offset(topLeft.x, topLeft.y + pose.dyEm * emPx), alpha = pose.alpha.coerceIn(0f, 1f))
}

/** 舞台的进退动画用的一条缓动：快起慢收，像墨在纸上走。 */
internal val InkEasing = CubicBezierEasing(0.22f, 0.8f, 0.3f, 1f)

