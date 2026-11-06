package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.datastore.StageTextReveal
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.DialogueVoice
import com.mozhi.reader.core.stage.SpanStyle
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageInk
import com.mozhi.reader.core.stage.StagePresentation
import com.mozhi.reader.core.stage.StageSpeaker
import com.mozhi.reader.ui.theme.MoReadSpacing
import kotlin.math.PI
import kotlin.math.sin

/**
 * 气泡的形：沿用漫画的气泡语法——普通说话圆角带尾巴，低声虚线，喊叫爆炸边，发抖的边会颤，
 * 传音双线框，系统音方框，心声是一团无框的淡墨，只有标点的反应（？、……）是小圆泡。
 */
internal enum class BalloonForm { SPEECH, WHISPER, SHOUT, TREMBLE, BRACKET, SYSTEM, THOUGHT, REACTION }

private val reactionText = Regex("^[？?！!。…\\.、—～~\\s]+$")

internal fun balloonForm(beat: StageBeat, presentation: StagePresentation?): BalloonForm {
    val x = presentation?.expression
    val shouted = beat.spans.any { SpanStyle.SHOUT in it.styles && it.end - it.start >= beat.text.length / 2 }
    return when {
        beat.voice == DialogueVoice.SYSTEM -> BalloonForm.SYSTEM
        beat.voice == DialogueVoice.BRACKET -> BalloonForm.BRACKET
        beat.kind == BeatKind.THOUGHT -> BalloonForm.THOUGHT
        beat.text.isNotEmpty() && reactionText.matches(beat.text) -> BalloonForm.REACTION
        x?.softer == true -> BalloonForm.WHISPER
        x?.louder == true || x?.bolder == true || shouted -> BalloonForm.SHOUT
        x?.tremble == true -> BalloonForm.TREMBLE
        else -> BalloonForm.SPEECH
    }
}

/** 气泡下沿留给尾巴的高度。 */
private val TailHeight = 7.dp
/** 爆炸边向外突出的幅度。 */
private val BurstAmplitude = 5.dp

// 普通对白是平整的圆角块（不画尾巴，更像书页上的排版而不是漫画）；只有喊叫与发抖保留尾巴强调说话人。
private fun BalloonForm.hasTail() = this == BalloonForm.SHOUT || this == BalloonForm.TREMBLE

/**
 * 一句对白：名牌或提示语在上，气泡居中线一侧，余下的提示语在下。
 * [animate] 时文字按设置的方式揭示，气泡本身从说话人一侧轻轻弹出。
 */
@Composable
internal fun StageBalloon(
    beat: StageBeat,
    presentation: StagePresentation?,
    side: StageSide,
    showName: Boolean,
    showAsides: Boolean,
    animate: Boolean,
    clock: StageRevealClock?,
    reveal: StageTextReveal,
    palette: StagePalette,
    fonts: StageFonts,
    names: Set<String>,
    fontScale: Float,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    /** 精排书给这句定的字体与颜色。 */
    ink: StageInk? = null,
    /** 已完整显示、在等点按：句末亮一颗朱砂墨点。 */
    cue: Boolean = false
) {
    val form = remember(beat, presentation) { balloonForm(beat, presentation) }
    val align = when (side) {
        StageSide.LEFT -> Alignment.Start
        StageSide.RIGHT -> Alignment.End
        StageSide.CENTER -> Alignment.CenterHorizontally
    }
    val textAlign = when (side) {
        StageSide.LEFT -> TextAlign.Start
        StageSide.RIGHT -> TextAlign.End
        StageSide.CENTER -> TextAlign.Center
    }
    val caption = TextStyle(
        fontFamily = fonts.body,
        fontSize = (12.5f * fontScale).sp,
        lineHeight = 1.55.em,
        color = palette.inkSoft,
        textAlign = textAlign,
        lineBreak = LineBreak.Paragraph
    )
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val full = maxWidth
        Column(Modifier.fillMaxWidth(), horizontalAlignment = align) {
            val speaker = beat.speaker
            if (showName && speaker != null) {
                StageNameTag(
                    speaker, speaker.label in names, palette, fonts, stamp = animate && !reduceMotion,
                    mirrored = side == StageSide.RIGHT,
                    modifier = Modifier.padding(horizontal = 6.dp).padding(bottom = 6.dp)
                )
            }
            if (showAsides) beat.asideBefore?.let {
                Text(it.text, style = caption, modifier = Modifier.widthIn(max = full * 0.88f).padding(horizontal = 10.dp).padding(bottom = 6.dp))
            }
            BalloonBody(beat, presentation, form, side, animate, clock, reveal, palette, fonts, fontScale, reduceMotion, full * StagePlanner.BALLOON_WIDTH, ink, cue)
            if (beat.continued) {
                Text("···", color = palette.inkFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp))
            }
            if (showAsides) beat.asideAfter?.let {
                Text(it.text, style = caption, modifier = Modifier.widthIn(max = full * 0.88f).padding(horizontal = 10.dp).padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun BalloonBody(
    beat: StageBeat,
    presentation: StagePresentation?,
    form: BalloonForm,
    side: StageSide,
    animate: Boolean,
    clock: StageRevealClock?,
    reveal: StageTextReveal,
    palette: StagePalette,
    fonts: StageFonts,
    fontScale: Float,
    reduceMotion: Boolean,
    maxWidth: Dp,
    ink: StageInk?,
    cue: Boolean
) {
    val known = beat.speaker?.takeIf { !it.unknown }
    // 说话人的颜色：主角朱砂，其他人各有一味颜料；认不出的人用淡墨。
    val voiceColor = when {
        known == null -> palette.inkFaint
        known.isProtagonist -> palette.accent
        else -> palette.roleColor(known.label)
    }
    val slip = form == BalloonForm.SPEECH || form == BalloonForm.REACTION || form == BalloonForm.SHOUT || form == BalloonForm.TREMBLE
    val fill = when (form) {
        BalloonForm.WHISPER -> Color.Transparent
        BalloonForm.SYSTEM -> palette.systemPanel
        BalloonForm.BRACKET -> palette.cold.copy(alpha = if (palette.isDark) 0.14f else 0.07f)
        BalloonForm.THOUGHT -> Color.Transparent
        else -> palette.slip
    }
    val edge = when (form) {
        BalloonForm.WHISPER -> palette.balloonEdge
        BalloonForm.THOUGHT -> Color.Transparent
        BalloonForm.SHOUT -> palette.ink.copy(alpha = 0.42f)
        BalloonForm.BRACKET -> palette.cold.copy(alpha = 0.55f)
        BalloonForm.SYSTEM -> palette.systemBorder
        else -> Color.Transparent
    }
    val textColor = when (form) {
        BalloonForm.SYSTEM -> palette.systemInk
        BalloonForm.WHISPER, BalloonForm.THOUGHT -> palette.inkSoft
        else -> palette.ink
    }
    val expression = presentation?.expression
    var textSize = 17f * fontScale
    if (beat.text.length > 60) textSize -= 1f
    if (form == BalloonForm.SHOUT) textSize *= if (fonts.brush != null) 1.22f else 1.06f
    if (form == BalloonForm.WHISPER) textSize *= 0.94f
    if (form == BalloonForm.REACTION) textSize = 26f * fontScale
    val style = TextStyle(
        fontFamily = when {
            form == BalloonForm.SYSTEM -> fonts.mono
            form == BalloonForm.SHOUT && fonts.brush != null -> fonts.brush
            else -> fonts.body
        },
        fontSize = textSize.sp,
        lineHeight = if (form == BalloonForm.REACTION) 1.2.em else 1.62.em,
        letterSpacing = if (form == BalloonForm.BRACKET) 0.06.em else 0.02.em,
        fontWeight = if ((form == BalloonForm.SHOUT && fonts.brush == null) || form == BalloonForm.REACTION || expression?.bolder == true) FontWeight.Bold else FontWeight.Normal,
        // 中文没有真正的斜体（只是机械倾斜），心声改用淡墨与留白来区分。
        fontStyle = FontStyle.Normal,
        color = if (expression?.subdued == true) textColor.copy(alpha = 0.8f) else textColor,
        textAlign = if (form == BalloonForm.REACTION) TextAlign.Center else TextAlign.Start,
        lineBreak = LineBreak.Paragraph
    ).withInk(ink, fonts, palette)
    val pop = remember(beat) { Animatable(if (animate && !reduceMotion) 0f else 1f) }
    LaunchedEffect(beat, animate) {
        if (animate && !reduceMotion && pop.value < 1f) {
            val (duration, easing) = when (form) {
                BalloonForm.SHOUT -> 300 to CubicBezierEasing(0.2f, 1.5f, 0.4f, 1f)
                BalloonForm.WHISPER, BalloonForm.THOUGHT -> 520 to FastOutSlowInEasing
                else -> 260 to CubicBezierEasing(0.2f, 1.25f, 0.4f, 1f)
            }
            pop.animateTo(1f, tween(duration, easing = easing))
        }
    }
    val tail = if (form.hasTail()) TailHeight else 0.dp
    val burst = if (form == BalloonForm.SHOUT) BurstAmplitude else 0.dp
    val horizontal = (if (form == BalloonForm.REACTION) 18.dp else if (form == BalloonForm.THOUGHT) 6.dp else 20.dp) + burst
    val vertical = (if (form == BalloonForm.REACTION) 6.dp else 10.dp) + burst
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .graphicsLayer {
                val p = pop.value
                alpha = (p * 1.6f).coerceIn(0f, 1f)
                val s = if (form == BalloonForm.SHOUT) 1.14f - 0.14f * p else 0.94f + 0.06f * p
                scaleX = s
                scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                    when (side) { StageSide.LEFT -> 0.08f; StageSide.RIGHT -> 0.92f; StageSide.CENTER -> 0.5f }, 1f
                )
                translationY = (1f - p) * 6.dp.toPx()
            }
            .drawWithCache {
                val path = balloonPath(size, form, side, density = this)
                val dashed = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
                val inner = if (form == BalloonForm.BRACKET) {
                    val inset = 3.5.dp.toPx()
                    Path().apply {
                        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(4.dp.toPx())))
                    }
                } else null
                val shadowStep = 1.5.dp.toPx()
                val lineInset = 9.dp.toPx()
                val lineWidth = 2.5.dp.toPx()
                val tailPx = if (form.hasTail()) TailHeight.toPx() else 0f
                onDrawBehind {
                    if (slip && fill.alpha > 0f) {
                        // 纸笺的影：两层错开的淡影代替模糊，像一张小笺微微浮在纸上。
                        translate(shadowStep * 0.6f, shadowStep * 1.4f) { drawPath(path, palette.slipShadow) }
                        translate(shadowStep * 0.2f, shadowStep * 0.6f) { drawPath(path, palette.slipShadow.copy(alpha = palette.slipShadow.alpha * 0.8f)) }
                    }
                    if (form == BalloonForm.THOUGHT) {
                        // 心声：身后一团极淡的墨晕，没有框。
                        drawOval(
                            androidx.compose.ui.graphics.Brush.radialGradient(
                                listOf(palette.ink.copy(alpha = if (palette.isDark) 0.08f else 0.045f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height / 2f),
                                radius = maxOf(size.width, size.height) * 0.62f
                            ),
                            topLeft = Offset(-size.width * 0.1f, -size.height * 0.25f),
                            size = Size(size.width * 1.2f, size.height * 1.5f)
                        )
                    }
                    if (fill.alpha > 0f) drawPath(path, fill)
                    if (slip && side != StageSide.CENTER) {
                        val x = if (side == StageSide.LEFT) lineInset else size.width - lineInset - lineWidth
                        val bodyH = size.height - tailPx
                        drawRoundRect(
                            voiceColor.copy(alpha = 0.85f),
                            topLeft = Offset(x, bodyH * 0.24f),
                            size = Size(lineWidth, bodyH * 0.52f),
                            cornerRadius = CornerRadius(lineWidth / 2f)
                        )
                    }
                    if (edge.alpha > 0f) {
                        val width = (if (form == BalloonForm.SHOUT) 1.6.dp else 1.dp).toPx()
                        drawPath(path, edge, style = Stroke(width, pathEffect = if (form == BalloonForm.WHISPER) dashed else null))
                    }
                    inner?.let { drawPath(it, edge.copy(alpha = edge.alpha * 0.6f), style = Stroke(1.dp.toPx())) }
                }
            }
            .padding(start = horizontal, end = horizontal, top = vertical, bottom = vertical + tail)
    ) {
        StageParagraphText(
            segments = listOf(StageTextSegment(beat.text, presentation?.spans ?: beat.spans)),
            animate = if (animate) 0 else -1,
            clock = clock,
            style = style,
            palette = palette,
            reveal = reveal,
            motion = StageTextMotion(expression?.tremble == true || form == BalloonForm.TREMBLE, expression?.pop == true, reduceMotion),
            fonts = fonts,
            cue = cue
        )
    }
}

/** 气泡外形；下沿留出 [TailHeight] 画尾巴。 */
private fun balloonPath(size: Size, form: BalloonForm, side: StageSide, density: androidx.compose.ui.unit.Density): Path = with(density) {
    val tailH = if (form.hasTail()) TailHeight.toPx() else 0f
    val w = size.width
    val h = (size.height - tailH).coerceAtLeast(1f)
    val radius = when (form) {
        BalloonForm.SYSTEM -> 4.dp.toPx()
        BalloonForm.BRACKET -> 7.dp.toPx()
        BalloonForm.REACTION -> h / 2f
        else -> minOf(14.dp.toPx(), h / 2f)
    }
    val inset = if (form == BalloonForm.SHOUT) BurstAmplitude.toPx() else 0f
    val body = Path().apply { addRoundRect(RoundRect(inset, inset, w - inset, h - inset, CornerRadius(radius))) }
    val outline = when (form) {
        BalloonForm.SHOUT -> burst(body, BurstAmplitude.toPx(), 14.dp.toPx())
        BalloonForm.TREMBLE -> wobble(body, 1.3.dp.toPx(), 11.dp.toPx())
        else -> body
    }
    if (tailH <= 0f || side == StageSide.CENTER) return outline
    val left = side == StageSide.LEFT
    val base = if (left) minOf(22.dp.toPx(), w * 0.3f) else maxOf(w - 22.dp.toPx(), w * 0.7f)
    val tip = if (left) base - 9.dp.toPx() else base + 9.dp.toPx()
    val tailPath = Path().apply {
        moveTo(base - 7.dp.toPx(), h - 2f)
        quadraticTo(base - 1.dp.toPx(), h + tailH * 0.35f, tip, h + tailH)
        quadraticTo(base + 2.dp.toPx(), h + tailH * 0.25f, base + 8.dp.toPx(), h - 2f)
        close()
    }
    return Path.combine(PathOperation.Union, outline, tailPath)
}

/** 爆炸边：沿外形每隔 [step] 取一点，单数点往外推 [amplitude]。 */
private fun burst(shape: Path, amplitude: Float, step: Float): Path = perimeter(shape, step) { i, _ -> if (i % 2 == 0) amplitude else -amplitude * 0.15f }

/** 发抖的边：沿外形小幅起伏。 */
private fun wobble(shape: Path, amplitude: Float, wavelength: Float): Path =
    perimeter(shape, wavelength / 4f) { _, distance -> amplitude * sin(distance / wavelength * 2f * PI.toFloat()) }

private inline fun perimeter(shape: Path, step: Float, offset: (Int, Float) -> Float): Path {
    val measure = PathMeasure()
    measure.setPath(shape, true)
    val length = measure.length
    val count = (length / step).toInt().coerceAtLeast(8)
    val bounds = shape.getBounds()
    val cx = bounds.center.x
    val cy = bounds.center.y
    val out = Path()
    for (i in 0 until count) {
        val d = length * i / count
        val p = measure.getPosition(d)
        val t = measure.getTangent(d)
        // 法线取朝外的一侧（不依赖路径绕向）。
        var nx = -t.y
        var ny = t.x
        if (nx * (p.x - cx) + ny * (p.y - cy) < 0f) { nx = -nx; ny = -ny }
        val o = offset(i, d)
        val x = p.x + nx * o
        val y = p.y + ny * o
        if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
    }
    out.close()
    return out
}

/** 名牌：一方小印加称呼；称呼是原文此处的写法。主角在右侧时印在名字右边。 */
@Composable
internal fun StageNameTag(
    speaker: StageSpeaker,
    knownName: Boolean,
    palette: StagePalette,
    fonts: StageFonts,
    stamp: Boolean,
    mirrored: Boolean = false,
    modifier: Modifier = Modifier
) {
    val color = when {
        speaker.unknown -> palette.inkSoft
        speaker.isProtagonist -> palette.accent
        else -> palette.roleColor(speaker.label)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        val seal: @Composable () -> Unit = {
            StageSeal(
                text = if (speaker.unknown) "?" else sealText(speaker.label, knownName),
                color = color,
                paper = palette.background,
                fonts = fonts,
                size = 19.dp,
                stamp = stamp,
                key = speaker.label,
                outline = speaker.unknown
            )
        }
        if (!mirrored) {
            seal()
            Spacer(Modifier.width(MoReadSpacing.xs + 3.dp))
        }
        Text(
            speaker.label,
            color = color,
            fontFamily = fonts.body,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.12.em,
            maxLines = 1
        )
        if (mirrored) {
            Spacer(Modifier.width(MoReadSpacing.xs + 3.dp))
            seal()
        }
    }
}
