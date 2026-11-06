package com.mozhi.reader.feature.reader.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.stage.RevealSchedule
import com.mozhi.reader.core.stage.SpanStyle
import com.mozhi.reader.core.stage.StageSpan
import kotlinx.coroutines.isActive

/**
 * 一拍文字的揭示时钟。开始时间取第一帧，[finished] 由舞台在时长走完或用户点按时置位；
 * 两者都只在 draw 阶段读，打字过程不触发重组。
 */
@Stable
internal class StageRevealClock(val schedule: RevealSchedule, instant: Boolean) {
    var startMillis by mutableLongStateOf(-1L)
    var finished by mutableStateOf(instant)
    val frame = mutableLongStateOf(0L)

    fun elapsed(frameMillis: Long): Long = when {
        finished -> Long.MAX_VALUE / 4
        startMillis < 0 -> 0L
        else -> frameMillis - startMillis
    }
}

/** 拍级的逐字效果标志（颤、回弹），叠加在行内风格之上。 */
internal data class StageTextMotion(
    val tremble: Boolean = false,
    val pop: Boolean = false,
    val reduceMotion: Boolean = false
)

private class Glyph(
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

/**
 * 演出页的自绘文字：整段先按均衡折行排一次版（定下每个字的位置），
 * 再按字形簇逐个绘制，每簇的淡入、落位、颤动来自 [StageGlyphMotion]。
 */
@Composable
internal fun StageKineticText(
    text: String,
    spans: List<StageSpan>,
    style: TextStyle,
    palette: StagePalette,
    clock: StageRevealClock,
    motion: StageTextMotion,
    modifier: Modifier = Modifier,
    /** 按整行宽度排版：居中、右对齐与两端对齐的短句也占满一行，对齐才有意义。 */
    fillWidth: Boolean = false
) {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val annotated = remember(text, spans, palette) { stageAnnotated(text, spans, palette) }
    val continuous = remember(spans, motion) {
        !motion.reduceMotion && (motion.tremble || spans.any { span ->
            SpanStyle.SHAKE in span.styles || SpanStyle.WAVE in span.styles || SpanStyle.GLITCH in span.styles
        })
    }
    LaunchedEffect(clock, continuous) {
        while (isActive) {
            withFrameMillis { now ->
                if (clock.startMillis < 0) clock.startMillis = now
                clock.frame.longValue = now
            }
            val done = clock.finished || clock.elapsed(clock.frame.longValue) > clock.schedule.totalMillis + 400
            if (done && !continuous) break
        }
    }
    BoxWithConstraints(modifier) {
        val maxWidth = constraints.maxWidth.coerceAtLeast(1)
        val layout = remember(annotated, style, maxWidth, fillWidth) {
            measurer.measure(annotated, style, constraints = Constraints(minWidth = if (fillWidth) maxWidth else 0, maxWidth = maxWidth))
        }
        val glyphs = remember(layout, clock.schedule, motion, palette.theater) {
            buildGlyphs(measurer, annotated, style, layout, clock.schedule, spans, motion, palette.theater)
        }
        val density = LocalDensity.current
        val emPx = with(density) { (style.fontSize.takeIf { it.isSp } ?: 20.sp).toPx() }
        val glowColor = palette.glow
        val split = palette.cold
        Canvas(Modifier.size(with(density) { layout.size.width.toDp() }, with(density) { layout.size.height.toDp() })) {
            val frame = clock.frame.longValue
            val elapsed = clock.elapsed(frame)
            for (g in glyphs) {
                val progress = if (elapsed >= Long.MAX_VALUE / 8) 1f else (elapsed - g.startMillis) / StageGlyphMotion.REVEAL_MILLIS
                if (progress <= 0f) continue
                val reveal = if (motion.reduceMotion) {
                    GlyphPose(alpha = progress.coerceIn(0f, 1f))
                } else {
                    StageGlyphMotion.reveal(progress, g.pop)
                }
                val sustain = StageGlyphMotion.sustain(g.flags, frame, g.index, g.seed, motion.reduceMotion)
                val dx = (reveal.dxEm + sustain.dxEm) * emPx
                val dy = (reveal.dyEm + sustain.dyEm) * emPx
                val alpha = (reveal.alpha * sustain.alpha).coerceIn(0f, 1f)
                val scale = reveal.scale
                withTransform({
                    translate(g.x + dx, g.top + dy)
                    if (scale != 1f) scale(scale, scale, pivot = Offset(g.layout.size.width / 2f, g.layout.size.height / 2f))
                }) {
                    if (sustain.splitEm != 0f) {
                        translate(left = sustain.splitEm * emPx) { drawText(g.layout, color = split.copy(alpha = 0.55f), alpha = alpha) }
                    }
                    drawText(
                        g.layout,
                        alpha = alpha,
                        shadow = if (g.glow && glowColor != Color.Transparent) Shadow(glowColor, Offset.Zero, emPx * 0.6f) else null
                    )
                }
            }
        }
    }
}

private fun buildGlyphs(
    measurer: TextMeasurer,
    annotated: AnnotatedString,
    style: TextStyle,
    layout: TextLayoutResult,
    schedule: RevealSchedule,
    spans: List<StageSpan>,
    motion: StageTextMotion,
    theater: Boolean
): List<Glyph> {
    val singles = HashMap<AnnotatedString, TextLayoutResult>()
    // 单个字形簇按左对齐、不缩进、不折行测量；位置取自整段排版，所以对齐方式与首行缩进都不影响结果。
    val singleStyle = style.copy(textAlign = TextAlign.Start, textIndent = null)
    return schedule.clusters.mapIndexedNotNull { i, range ->
        if (range.first >= annotated.length) return@mapIndexedNotNull null
        val end = (range.last + 1).coerceAtMost(annotated.length)
        val piece = annotated.subSequence(range.first, end)
        if (piece.text.isBlank()) return@mapIndexedNotNull null
        val single = singles.getOrPut(piece) { measurer.measure(piece, singleStyle, softWrap = false, maxLines = 1) }
        val line = layout.getLineForOffset(range.first)
        val x = layout.getHorizontalPosition(range.first, usePrimaryDirection = true)
        val top = layout.getLineBaseline(line) - single.firstBaseline
        var flags = 0
        var glow = false
        for (span in spans) if (range.first >= span.start && range.first < span.end) {
            if (SpanStyle.SHAKE in span.styles) flags = flags or StageGlyphMotion.SHAKE
            if (SpanStyle.WAVE in span.styles) flags = flags or StageGlyphMotion.WAVE
            if (SpanStyle.GLITCH in span.styles) flags = flags or StageGlyphMotion.GLITCH
            if (SpanStyle.GLOW in span.styles && theater) glow = true
        }
        if (motion.tremble) flags = flags or StageGlyphMotion.TREMBLE
        val pop = motion.pop && piece.text.any { it in "！？!?" }
        Glyph(single, x, top, schedule.startMillis.getOrElse(i) { 0 }, flags, StageGlyphMotion.seed(i, annotated.length), i, pop, glow)
    }
}

/** 行内风格 → Compose 样式：颜色、字重、字号、字距、下划线；位移类效果在绘制时叠加。 */
internal fun stageAnnotated(text: String, spans: List<StageSpan>, palette: StagePalette, fonts: StageFonts? = null): AnnotatedString = buildAnnotatedString {
    append(text)
    for (span in spans) {
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (start >= end) continue
        span.ink?.let { ink ->
            // 书里给这几个字定的样子：字体、颜色、粗斜、字号。
            addStyle(
                ComposeSpanStyle(
                    color = ink.colorArgb?.let { bookInk(it, palette.background, palette) } ?: Color.Unspecified,
                    fontFamily = ink.fontFamily?.let { fonts?.book?.get(it) },
                    fontWeight = if (ink.bold) FontWeight.Bold else null,
                    fontStyle = if (ink.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                    fontSize = ink.sizeEm?.coerceIn(0.85f, 1.3f)?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified
                ),
                start,
                end
            )
        }
        if (span.styles.isEmpty()) continue
        val styles = span.styles
        var color: Color = Color.Unspecified
        var weight: FontWeight? = null
        var scale: Float? = null
        var alpha = 1f
        if (SpanStyle.NAME in styles) {
            color = span.nameKey?.let(palette::roleColor) ?: palette.accent
            weight = FontWeight.SemiBold
        }
        if (SpanStyle.STRESS in styles) { color = palette.accent; weight = FontWeight.Bold }
        if (SpanStyle.GLOW in styles) color = palette.accent
        if (SpanStyle.COLD in styles) color = palette.cold
        if (SpanStyle.WARM in styles) color = palette.warm
        if (SpanStyle.GLITCH in styles && color == Color.Unspecified) color = palette.ink
        if (SpanStyle.SHOUT in styles) { scale = 1.2f; weight = FontWeight.Bold }
        if (SpanStyle.WHISPER in styles) { scale = 0.86f; alpha = 0.7f }
        if (SpanStyle.STRIKE in styles) alpha = minOf(alpha, 0.55f)
        val base = if (color == Color.Unspecified) Color.Unspecified else color
        addStyle(
            ComposeSpanStyle(
                color = if (alpha < 1f) (if (base == Color.Unspecified) palette.ink else base).copy(alpha = alpha) else base,
                fontWeight = weight,
                fontSize = scale?.em ?: androidx.compose.ui.unit.TextUnit.Unspecified,
                letterSpacing = if (SpanStyle.SPACED in styles) 0.3.em else androidx.compose.ui.unit.TextUnit.Unspecified,
                textDecoration = when {
                    SpanStyle.STRIKE in styles -> TextDecoration.LineThrough
                    SpanStyle.TERM in styles -> TextDecoration.Underline
                    else -> null
                }
            ),
            start,
            end
        )
    }
}
