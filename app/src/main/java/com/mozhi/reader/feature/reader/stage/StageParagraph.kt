package com.mozhi.reader.feature.reader.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle as ComposeSpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.datastore.StageTextReveal
import com.mozhi.reader.core.stage.SpanStyle
import com.mozhi.reader.core.stage.StageSpan
import java.text.BreakIterator
import kotlinx.coroutines.isActive

/** 一段文字里的一拍：展示文字与行内强调（下标相对这拍文字）。 */
@Immutable
internal data class StageTextSegment(val text: String, val spans: List<StageSpan> = emptyList())

private class SegmentGlyph(
    val layout: TextLayoutResult,
    val x: Float,
    val top: Float,
    val startMillis: Int,
    val flags: Int,
    val seed: Float,
    val index: Int,
    val pop: Boolean,
    val glow: Boolean
)

/** 逐行浮现时，相邻两行开始出现的间隔。 */
private const val LINE_STAGGER_MS = 90

/**
 * 整页里的一段：几拍文字排成一个段落，折行与两端对齐按整段算，前面的字不会因为新句子加进来而移动。
 * [animate] 指向正在揭示的那一拍：它按字形簇逐个绘制，淡入、逐行或逐字由 [reveal] 决定；
 * 其余各拍静止绘制。揭示进度与颤动相位只在绘制阶段读取，不触发重组。
 */
@Composable
internal fun StageParagraphText(
    segments: List<StageTextSegment>,
    animate: Int,
    clock: StageRevealClock?,
    style: TextStyle,
    palette: StagePalette,
    reveal: StageTextReveal,
    motion: StageTextMotion,
    modifier: Modifier = Modifier,
    /** 按整行宽度排版：居中、右对齐与两端对齐的短句也占满一行，对齐才有意义。 */
    fillWidth: Boolean = false,
    /** 行内书内样式要用到的字体。 */
    fonts: StageFonts? = null,
    /** 这一拍已经完整显示、在等读者点按：段末亮一颗呼吸的朱砂墨点。 */
    cue: Boolean = false
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val starts = remember(segments) {
        var at = 0
        IntArray(segments.size) { i -> at.also { at += segments[i].text.length } }
    }
    val full = remember(segments, palette, fonts) {
        val text = segments.joinToString("") { it.text }
        val spans = segments.flatMapIndexed { i, s -> s.spans.map { it.copy(start = it.start + starts[i], end = it.end + starts[i]) } }
        stageAnnotated(text, spans, palette, fonts)
    }
    // 直接显示：整段静态绘制，不走逐字。
    val active = animate in segments.indices && clock != null && reveal != StageTextReveal.NONE
    val segStart = if (active) starts[animate] else 0
    val segEnd = if (active) segStart + segments[animate].text.length else 0
    // 正在揭示的一拍先在整段排版里画成透明，由逐字绘制补上。
    val drawn = remember(full, active, segStart, segEnd) {
        if (!active || segStart >= segEnd) full else buildAnnotatedString {
            append(full)
            addStyle(ComposeSpanStyle(color = Color.Transparent), segStart, segEnd)
        }
    }
    val continuous = active && !motion.reduceMotion && (motion.tremble || segments[animate].spans.any { span ->
        SpanStyle.SHAKE in span.styles || SpanStyle.WAVE in span.styles || SpanStyle.GLITCH in span.styles
    })
    if (active) LaunchedEffect(clock, continuous) {
        val c = requireNotNull(clock)
        while (isActive) {
            withFrameMillis { now ->
                if (c.startMillis < 0) c.startMillis = now
                c.frame.longValue = now
            }
            val done = c.finished || c.elapsed(c.frame.longValue) > c.schedule.totalMillis + 600
            if (done && !continuous) break
        }
    }
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth.coerceAtLeast(1)
        val layout = remember(drawn, style, maxWidth, fillWidth) {
            measurer.measure(drawn, style, constraints = Constraints(minWidth = if (fillWidth) maxWidth else 0, maxWidth = maxWidth))
        }
        val glyphs = remember(layout, full, active, segStart, segEnd, reveal, clock?.schedule, motion, palette.theater) {
            if (!active || segStart >= segEnd) emptyList() else segmentGlyphs(
                measurer, full, style, layout, segStart, segEnd, segments[animate].spans, reveal,
                requireNotNull(clock).schedule.startMillis, motion, palette.theater
            )
        }
        val density = LocalDensity.current
        val emPx = with(density) { (style.fontSize.takeIf { it.isSp } ?: 18.sp).toPx() }
        val glowColor = palette.glow
        val split = palette.cold
        val bleedColor = style.color.takeIf { it != Color.Unspecified } ?: palette.ink
        Canvas(Modifier.size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() })) {
            drawText(layout)
            if (glyphs.isEmpty()) return@Canvas
            val c = requireNotNull(clock)
            val frame = c.frame.longValue
            val elapsed = c.elapsed(frame)
            val duration = if (reveal == StageTextReveal.INK) StageGlyphMotion.INK_MILLIS else StageGlyphMotion.REVEAL_MILLIS
            for (g in glyphs) {
                val progress = if (elapsed >= Long.MAX_VALUE / 8) 1f else (elapsed - g.startMillis) / duration
                if (progress <= 0f) continue
                val pose = when {
                    motion.reduceMotion -> GlyphPose(alpha = progress.coerceIn(0f, 1f))
                    reveal == StageTextReveal.INK -> StageGlyphMotion.ink(progress, g.pop)
                    reveal == StageTextReveal.FADE -> StageGlyphMotion.soft(progress, g.pop)
                    else -> StageGlyphMotion.reveal(progress, g.pop)
                }
                val sustain = StageGlyphMotion.sustain(g.flags, frame, g.index, g.seed, motion.reduceMotion)
                val alpha = (pose.alpha * sustain.alpha).coerceIn(0f, 1f)
                withTransform({
                    translate(g.x + (pose.dxEm + sustain.dxEm) * emPx, g.top + (pose.dyEm + sustain.dyEm) * emPx)
                    if (pose.scale != 1f) scale(pose.scale, pose.scale, pivot = Offset(g.layout.size.width / 2f, g.layout.size.height / 2f))
                }) {
                    if (pose.bleedAlpha > 0.01f) {
                        // 墨晕：只画字形洇开的影（字本身透明），影的浓淡取影色自己的透明度。
                        drawText(g.layout, color = Color.Transparent, shadow = Shadow(bleedColor.copy(alpha = pose.bleedAlpha.coerceAtMost(0.95f)), Offset.Zero, (pose.bleedEm * emPx).coerceAtLeast(1f)))
                    }
                    if (sustain.splitEm != 0f) translate(left = sustain.splitEm * emPx) { drawText(g.layout, color = split.copy(alpha = 0.55f), alpha = alpha) }
                    drawText(g.layout, alpha = alpha, shadow = if (g.glow && glowColor != Color.Transparent) Shadow(glowColor, Offset.Zero, emPx * 0.6f) else null)
                }
            }
        }
        if (cue) {
            val end = layout.layoutInput.text.length
            val line = layout.lineCount - 1
            val x = if (end > 0) layout.getHorizontalPosition(end, usePrimaryDirection = true) else 0f
            val baseline = layout.getLineBaseline(line)
            StageInkCue(
                x = x + emPx * 0.32f,
                y = baseline - emPx * 0.36f,
                radius = emPx * 0.13f,
                color = palette.accent
            )
        }
    }
}

/** 等待点按的提示：一颗朱砂墨点，缓缓呼吸，外圈一道淡晕。只有这一小块在逐帧重画。 */
@Composable
private fun StageInkCue(x: Float, y: Float, radius: Float, color: Color) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "cue")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1_800, easing = androidx.compose.animation.core.LinearEasing)),
        label = "cue-phase"
    )
    val appear = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, androidx.compose.animation.core.tween(420)) }
    val density = LocalDensity.current
    val box = radius * 6f
    Canvas(
        Modifier
            .offset { androidx.compose.ui.unit.IntOffset((x - box / 2f).toInt(), (y - box / 2f).toInt()) }
            .size(with(density) { box.toDp() })
    ) {
        val breath = 0.5f - 0.5f * kotlin.math.cos(phase * 2f * kotlin.math.PI.toFloat())
        val c = Offset(size.width / 2f, size.height / 2f)
        val a = appear.value
        drawCircle(color.copy(alpha = 0.18f * a * (1f - phase)), radius * (1.2f + 1.6f * phase), c)
        drawCircle(color.copy(alpha = (0.55f + 0.4f * breath) * a), radius * (0.9f + 0.12f * breath), c)
    }
}

private fun segmentGlyphs(
    measurer: androidx.compose.ui.text.TextMeasurer,
    full: AnnotatedString,
    style: TextStyle,
    layout: TextLayoutResult,
    segStart: Int,
    segEnd: Int,
    spans: List<StageSpan>,
    reveal: StageTextReveal,
    typedStarts: IntArray,
    motion: StageTextMotion,
    theater: Boolean
): List<SegmentGlyph> {
    // 单个字形簇按左对齐、不缩进、不折行测量；位置取自整段排版。
    val single = style.copy(textAlign = TextAlign.Start, textIndent = null)
    val text = full.text.substring(segStart, segEnd)
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val firstLine = layout.getLineForOffset(segStart)
    val cache = HashMap<AnnotatedString, TextLayoutResult>()
    val out = mutableListOf<SegmentGlyph>()
    var cluster = 0
    var a = iterator.first()
    var b = iterator.next()
    while (b != BreakIterator.DONE) {
        val gs = segStart + a
        val ge = (segStart + b).coerceAtMost(full.length)
        val piece = full.subSequence(gs, ge)
        if (piece.text.isNotBlank()) {
            val measured = cache.getOrPut(piece) { measurer.measure(piece, single, softWrap = false, maxLines = 1) }
            val line = layout.getLineForOffset(gs)
            val x = layout.getHorizontalPosition(gs, usePrimaryDirection = true)
            val top = layout.getLineBaseline(line) - measured.firstBaseline
            var flags = 0
            var glow = false
            for (span in spans) if (a >= span.start && a < span.end) {
                if (SpanStyle.SHAKE in span.styles) flags = flags or StageGlyphMotion.SHAKE
                if (SpanStyle.WAVE in span.styles) flags = flags or StageGlyphMotion.WAVE
                if (SpanStyle.GLITCH in span.styles) flags = flags or StageGlyphMotion.GLITCH
                if (SpanStyle.GLOW in span.styles && theater) glow = true
            }
            if (motion.tremble) flags = flags or StageGlyphMotion.TREMBLE
            // 逐字与淡入都照揭示时间表走（淡入时只有标了慢出的词才有先后）；逐行按行错开。
            val start = when (reveal) {
                StageTextReveal.LINES -> (line - firstLine) * LINE_STAGGER_MS
                StageTextReveal.INK -> typedStarts.getOrElse(cluster) { 0 } + StageGlyphMotion.inkDelay(cluster, text.length)
                else -> typedStarts.getOrElse(cluster) { 0 }
            }
            val pop = motion.pop && piece.text.any { it in "！？!?" }
            out += SegmentGlyph(measured, x, top, start, flags, StageGlyphMotion.seed(cluster, text.length), cluster, pop, glow)
        }
        cluster++
        a = b
        b = iterator.next()
    }
    return out
}
