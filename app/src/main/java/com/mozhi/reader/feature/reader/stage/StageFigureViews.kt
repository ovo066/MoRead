package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle as ComposeSpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.datastore.StageTextReveal
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageFigures
import com.mozhi.reader.core.stage.StagePresentation
import kotlinx.coroutines.delay

private val han = Regex("^[\\u3400-\\u9fff，。、！？：；…—～·“”「」]+$")
private val trailingPunctuation = Regex("[，。！？；：、…—～!?.,;:]+$")
/** 竖排时侧转 90° 的符号。 */
private const val ROTATED = "…—～"
/** 竖排时缩小靠右上的符号。 */
private const val SMALL_MARKS = "，。、"

/**
 * 海报：短促有力的一句独占一屏。汉字短句竖排成一两列（对仗按逗号分列），朱砂色的行书大字从墨里一个个显出来，
 * 落定时四周甩出几点溅墨；其余横排放大。字一个不改，只换排法与笔意。
 */
@Composable
internal fun StagePoster(
    beat: StageBeat,
    instant: Boolean,
    reveal: StageTextReveal,
    palette: StagePalette,
    fonts: StageFonts,
    reduceMotion: Boolean
) {
    val text = beat.text
    val vertical = remember(text) { han.matches(text) && text.length <= 16 }
    val color = palette.accent
    val still = instant || reduceMotion || reveal == StageTextReveal.NONE
    val progress = remember(beat) { Animatable(if (still) 1f else 0f) }
    val perGlyph = if (reveal == StageTextReveal.TYPE || reveal == StageTextReveal.LINES) 0.085f else 0.06f
    val total = remember(text, perGlyph) { (text.length * perGlyph + 0.5f).coerceAtLeast(0.8f) }
    LaunchedEffect(beat) {
        if (progress.value < 1f) progress.animateTo(1f, tween((total * 1000).toInt(), easing = LinearEasing))
    }
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val brush = fonts.brush ?: fonts.body
    val seed = remember(text) { text.hashCode() }
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val maxH = maxHeight.value.takeIf { it.isFinite() && it > 0f } ?: 560f
        val density = LocalDensity.current
        if (vertical) {
            val columns = remember(text) { posterColumns(text) }
            val longest = columns.maxOf { it.length }.coerceAtLeast(1)
            val cell = (maxH * 0.72f / longest).coerceIn(34f, 76f)
            val style = TextStyle(fontFamily = brush, fontSize = (cell * 0.88f).sp, color = color, fontWeight = if (fonts.brush == null) FontWeight.Black else FontWeight.Normal)
            val small = style.copy(fontSize = (cell * 0.5f).sp)
            val layouts = remember(columns, style) {
                columns.map { col -> col.map { ch -> measurer.measure(ch.toString(), if (ch in SMALL_MARKS) small else style) } }
            }
            val gap = cell * 0.38f
            val widthDp = cell * columns.size + gap * (columns.size - 1)
            val heightDp = cell * 1.08f * longest
            Canvas(Modifier.size(widthDp.dp, heightDp.dp)) {
                val cellPx = cell.dp.toPx()
                val gapPx = gap.dp.toPx()
                val emPx = (cell * 0.88f).dp.toPx()
                val p = progress.value * total
                var order = 0
                // 溅墨在字底下：最后一个字落定时甩出去。
                val landed = ((p - (text.length * perGlyph)) / 0.35f).coerceIn(0f, 1f)
                if (!reduceMotion) inkSplatter(Offset(size.width / 2f, size.height / 2f), size.maxDimension * 0.62f, color, seed, 26, if (instant) 1f else landed)
                for (c in columns.indices) {
                    // 竖排从右往左读：第一列在最右。
                    val x0 = size.width - (c + 1) * cellPx - c * gapPx
                    for ((k, layout) in layouts[c].withIndex()) {
                        val ch = columns[c][k]
                        val local = if (instant) 1f else ((p - order * perGlyph) / 0.42f).coerceIn(0f, 1f)
                        order++
                        val y0 = k * cellPx * 1.08f
                        val isSmall = ch in SMALL_MARKS
                        val x = if (isSmall) x0 + cellPx - layout.size.width - cellPx * 0.08f else x0 + (cellPx - layout.size.width) / 2f
                        val y = if (isSmall) y0 else y0 + (cellPx * 1.08f - layout.size.height) / 2f
                        if (ch in ROTATED) {
                            withTransform({ rotate(90f, Offset(x0 + cellPx / 2f, y0 + cellPx * 0.54f)) }) {
                                drawInkGlyph(layout, Offset(x, y), local, color, emPx)
                            }
                        } else {
                            drawInkGlyph(layout, Offset(x, y), local, color, emPx)
                        }
                    }
                }
            }
        } else {
            val posterSize = (300f / text.length.coerceAtLeast(1) * 1.6f).coerceIn(26f, 46f)
            val style = TextStyle(
                fontFamily = brush,
                fontSize = (if (fonts.brush != null) posterSize * 1.12f else posterSize).sp,
                fontWeight = if (fonts.brush == null) FontWeight.Bold else FontWeight.Normal,
                letterSpacing = 0.08.em,
                lineHeight = 1.35.em,
                textAlign = TextAlign.Center,
                lineBreak = LineBreak.Heading,
                color = palette.ink
            )
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.matchParentSize()) {
                    if (!reduceMotion) inkSplatter(center, size.maxDimension * 0.7f, palette.ink, seed, 18, ((progress.value * total - 0.3f) / 0.4f).coerceIn(0f, 1f))
                }
                Text(
                    text,
                    style = style,
                    modifier = Modifier
                        .widthIn(max = 330.dp)
                        .graphicsLayer {
                            val p = (progress.value * total / 0.5f).coerceIn(0f, 1f)
                            alpha = p
                            val s = 1.12f - 0.12f * (1f - (1f - p) * (1f - p))
                            scaleX = s
                            scaleY = s
                        }
                )
            }
        }
    }
}

/** 对仗句按逗号分两列；单句太长时对半分。 */
private fun posterColumns(text: String): List<String> {
    val comma = text.indexOfFirst { it == '\uFF0C' || it == ',' }
    if (comma in 1 until text.length - 1) return listOf(text.substring(0, comma + 1), text.substring(comma + 1))
    if (text.length <= 8) return listOf(text)
    val half = (text.length + 1) / 2
    return listOf(text.substring(0, half), text.substring(half))
}

/**
 * 阶梯：连续的问句一级一级往右下排，像一句逼一句；之前的台阶留在原处，最新一级按设置揭示。
 */
@Composable
internal fun StageLadder(
    beats: List<StageBeat>,
    presentations: List<StagePresentation>,
    first: Int,
    upTo: Int,
    clock: StageRevealClock,
    instant: Boolean,
    reveal: StageTextReveal,
    palette: StagePalette,
    fonts: StageFonts,
    typography: StageTypography,
    reduceMotion: Boolean
) {
    val size = typography.proseSize + 1f
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        for (k in first..upTo) key(k) {
            val step = (k - first).coerceAtMost(5)
            val current = k == upTo && !instant
            Box(Modifier.fillMaxWidth().padding(start = (step * 26).dp)) {
                StageParagraphText(
                    segments = listOf(StageTextSegment(beats[k].text, presentations[k].spans)),
                    animate = if (current) 0 else -1,
                    clock = clock.takeIf { current },
                    style = TextStyle(
                        fontFamily = fonts.body,
                        fontSize = size.sp,
                        lineHeight = 1.6.em,
                        letterSpacing = 0.04.em,
                        color = if (k == upTo) palette.ink else palette.ink.copy(alpha = 0.62f),
                        fontWeight = if (k == upTo) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    palette = palette,
                    reveal = reveal,
                    motion = StageTextMotion(false, true, reduceMotion)
                )
            }
        }
    }
}

/**
 * 叠句：起头或收尾相同的一串短句叠成一列，相同的部分压淡、变化的部分加深，
 * 一眼看出原文的节奏。新的一句从左边滑入。
 */
@Composable
internal fun StageStack(
    beats: List<StageBeat>,
    group: List<Int>,
    upTo: Int,
    instant: Boolean,
    palette: StagePalette,
    fonts: StageFonts,
    typography: StageTypography,
    reduceMotion: Boolean
) {
    val texts = remember(group) { group.map { beats[it].text } }
    val cores = remember(texts) { texts.map { it.replace(trailingPunctuation, "") } }
    val (prefix, suffix) = remember(cores) { sharedEnds(cores) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            for ((n, k) in group.withIndex()) {
                if (k > upTo) break
                key(k) {
                    val appear = remember { Animatable(if (instant || reduceMotion || k != upTo) 1f else 0f) }
                    LaunchedEffect(Unit) { if (appear.value < 1f) appear.animateTo(1f, tween(380)) }
                    val t = texts[n]
                    val core = cores[n]
                    val head = prefix.coerceAtMost(core.length)
                    val tail = suffix.coerceAtMost(core.length - head)
                    val line = buildAnnotatedString {
                        withStyle(ComposeSpanStyle(color = palette.inkSoft)) { append(t.substring(0, head)) }
                        withStyle(ComposeSpanStyle(color = palette.ink, fontWeight = FontWeight.SemiBold)) { append(t.substring(head, core.length - tail)) }
                        withStyle(ComposeSpanStyle(color = palette.inkSoft)) { append(t.substring(core.length - tail)) }
                    }
                    Text(
                        line,
                        fontFamily = fonts.body,
                        fontSize = (typography.proseSize + 1f).sp,
                        letterSpacing = 0.06.em,
                        modifier = Modifier.graphicsLayer {
                            val p = appear.value
                            alpha = p
                            translationX = (1f - p) * -18.dp.toPx()
                        }
                    )
                }
            }
        }
    }
}

/** 一组短句共享的前缀与后缀长度；整句相同时都记 0。 */
private fun sharedEnds(texts: List<String>): Pair<Int, Int> {
    if (texts.size < 2) return 0 to 0
    val shortest = texts.minOf { it.length }
    var prefix = 0
    while (prefix < shortest && texts.all { it[prefix] == texts[0][prefix] }) prefix++
    var suffix = 0
    while (suffix < shortest - prefix && texts.all { it[it.length - 1 - suffix] == texts[0][texts[0].length - 1 - suffix] }) suffix++
    if (prefix >= shortest) return 0 to 0
    return if (StageFigures.shared(texts) >= 2) prefix to suffix else 0 to 0
}
