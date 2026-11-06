package com.mozhi.reader.feature.reader.stage

import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.stage.BeatEnter
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.DialogueVoice
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageInk
import com.mozhi.reader.core.stage.StagePresentation
import com.mozhi.reader.core.stage.StageReveal
import com.mozhi.reader.core.stage.StageSpeaker
import com.mozhi.reader.core.stage.StageTextRules
import com.mozhi.reader.ui.theme.MoReadSpacing
import java.io.File
import kotlin.math.sin

/** 舞台用的两套字体：正文跟随阅读字体，系统面板用等宽。 */
@Stable
internal data class StageFonts(
    val body: FontFamily,
    val mono: FontFamily = FontFamily.Monospace,
    /** 精排书内嵌字体：族名（小写）→ 字体。 */
    val book: Map<String, FontFamily> = emptyMap(),
    /** 内置行书（章名、海报、拟声、喊叫、书信、印章）；关掉「书法题字」时为 null，回落到正文字体。 */
    val brush: FontFamily? = null
)

/** 系统自带的思源宋体（简体在 ttc 的第 3 个字面）；系统「serif」不含中文字形，汉字会落回黑体。 */
private val songFamily: FontFamily? by lazy {
    val file = listOf("/system/fonts/NotoSerifCJK-Regular.ttc", "/system/fonts/NotoSerifCJKsc-Regular.otf").map(::File).firstOrNull { it.exists() }
        ?: return@lazy null
    runCatching {
        val face = if (file.name.endsWith(".ttc") && android.os.Build.VERSION.SDK_INT >= 26) {
            Typeface.Builder(file).setTtcIndex(2).build()
        } else Typeface.createFromFile(file)
        FontFamily(face)
    }.getOrNull()
}

private fun systemSong(): FontFamily? = songFamily

internal fun stageFonts(settings: ReaderSettings, typeface: com.mozhi.reader.core.datastore.StageTypeface = com.mozhi.reader.core.datastore.StageTypeface.FOLLOW): StageFonts {
    if (typeface == com.mozhi.reader.core.datastore.StageTypeface.SONG) return StageFonts(systemSong() ?: FontFamily.Serif)
    if (typeface == com.mozhi.reader.core.datastore.StageTypeface.SANS) return StageFonts(FontFamily.SansSerif)
    val custom = if (settings.font == ReaderFont.CUSTOM) {
        settings.customFontPath?.let { path -> runCatching { Typeface.createFromFile(File(path)) }.getOrNull() }
    } else null
    val body = when {
        custom != null -> FontFamily(custom)
        settings.font == ReaderFont.SERIF -> FontFamily.Serif
        settings.font == ReaderFont.SANS_SERIF -> FontFamily.SansSerif
        settings.font == ReaderFont.MONOSPACE -> FontFamily.Monospace
        else -> FontFamily.Default
    }
    return StageFonts(body)
}

/** 舞台的字号阶（sp）：旁白 20、对白 21、心声 18、章名 30、字幕 14；超过四行降一档。 */
internal object StageType {
    const val NARRATION = 20f
    const val DIALOGUE = 21f
    const val THOUGHT = 18f
    const val TITLE = 30f
    const val CAPTION = 14f
    const val SYSTEM = 15f
    const val ASIDE = 13.5f
    const val NOTE = 13f
    const val LONG_TEXT = 64
}

internal fun lineStyle(beat: StageBeat, presentation: StagePresentation, palette: StagePalette, fonts: StageFonts): TextStyle {
    val expression = presentation.expression
    var size = when (beat.kind) {
        BeatKind.DIALOGUE -> StageType.DIALOGUE
        BeatKind.THOUGHT -> StageType.THOUGHT
        else -> StageType.NARRATION
    }
    if (beat.text.length > StageType.LONG_TEXT) size -= 2f
    if (expression.softer) size *= 0.92f
    if (expression.louder) size *= 1.14f
    val color = when {
        beat.kind == BeatKind.THOUGHT || expression.subdued -> palette.inkSoft
        else -> palette.ink
    }
    return TextStyle(
        fontFamily = if (beat.voice == DialogueVoice.SYSTEM) fonts.mono else fonts.body,
        fontSize = size.sp,
        lineHeight = 1.85.em,
        letterSpacing = 0.02.em,
        fontWeight = if (expression.bolder || expression.louder) FontWeight.Bold else FontWeight.Normal,
        color = if (expression.softer) color.copy(alpha = 0.8f) else color,
        textAlign = TextAlign.Center,
        lineBreak = LineBreak.Heading
    )
}

/** 拍的整体入场：淡入、浮起、砸下、慢淡、错位闪入；打字型入场由逐字揭示完成。 */
@Composable
internal fun Modifier.stageEnter(enter: BeatEnter?, key: Any, instant: Boolean): Modifier {
    val animated = enter != null && enter != BeatEnter.TYPE && enter != BeatEnter.INSTANT && !instant
    val progress = remember(key) { Animatable(if (animated) 0f else 1f) }
    LaunchedEffect(key) {
        if (animated) {
            val duration = when (enter) {
                BeatEnter.SLAM -> 340
                BeatEnter.WHISPER -> 1600
                BeatEnter.GLITCH -> 520
                BeatEnter.RISE -> 600
                else -> 700
            }
            val easing = when (enter) {
                BeatEnter.SLAM -> CubicBezierEasing(0.2f, 1.45f, 0.4f, 1f)
                BeatEnter.GLITCH -> LinearEasing
                else -> FastOutSlowInEasing
            }
            progress.animateTo(1f, tween(duration, easing = easing))
        }
    }
    return graphicsLayer {
        val p = progress.value
        when (enter) {
            BeatEnter.FADE, BeatEnter.WHISPER -> alpha = p
            BeatEnter.RISE -> {
                alpha = p
                translationY = (1f - p) * 16.dp.toPx()
            }
            BeatEnter.SLAM -> {
                alpha = (p * 3f).coerceAtMost(1f)
                val scale = 1.55f - 0.55f * p
                scaleX = scale
                scaleY = scale
            }
            BeatEnter.GLITCH -> {
                alpha = if (p < 0.2f) p * 5f else 1f
                translationX = if (p < 1f) sin(p * 40f) * (1f - p) * 6.dp.toPx() else 0f
            }
            else -> Unit
        }
    }
}

/** 旁白、对白、心声：名牌 + 提示语旁注 + 自绘文字。 */
@Composable
internal fun StageLineBeat(
    beat: StageBeat,
    presentation: StagePresentation,
    palette: StagePalette,
    fonts: StageFonts,
    clock: StageRevealClock,
    names: Set<String>,
    showAsides: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    val asideStyle = TextStyle(
        fontFamily = fonts.body,
        fontSize = StageType.ASIDE.sp,
        lineHeight = 1.7.em,
        color = palette.inkSoft,
        textAlign = TextAlign.Center,
        lineBreak = LineBreak.Heading
    )
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        beat.speaker?.let { speaker ->
            StageNameTag(speaker, speaker.label in names, palette, fonts, stamp = !reduceMotion)
            Spacer(Modifier.height(MoReadSpacing.l))
        }
        if (showAsides) beat.asideBefore?.let { aside ->
            Text(aside.text, style = asideStyle, modifier = Modifier.widthIn(max = 300.dp).padding(bottom = MoReadSpacing.m))
        }
        val bracketFrame = beat.voice == DialogueVoice.BRACKET || beat.voice == DialogueVoice.SYSTEM
        val thoughtFrame = beat.kind == BeatKind.THOUGHT
        Box(
            modifier = Modifier
                .widthIn(max = 316.dp)
                .then(if (thoughtFrame || bracketFrame) Modifier.stageSideRules(palette, beat.voice == DialogueVoice.SYSTEM) else Modifier)
                .padding(horizontal = if (thoughtFrame || bracketFrame) MoReadSpacing.l else 0.dp)
        ) {
            StageKineticText(
                text = beat.text,
                spans = presentation.spans,
                style = lineStyle(beat, presentation, palette, fonts),
                palette = palette,
                clock = clock,
                motion = StageTextMotion(presentation.expression.tremble, presentation.expression.pop, reduceMotion)
            )
        }
        if (beat.continued) {
            Text("···", color = palette.inkFaint, fontSize = 12.sp, modifier = Modifier.padding(top = MoReadSpacing.xs))
        }
        if (showAsides) beat.asideAfter?.let { aside ->
            Text(aside.text, style = asideStyle, modifier = Modifier.widthIn(max = 300.dp).padding(top = MoReadSpacing.m))
        }
    }
}

/** 心声与传音两侧的细竖线：括号换成了框，不丢字也不再显示括号本身。 */
private fun Modifier.stageSideRules(palette: StagePalette, system: Boolean): Modifier = drawBehind {
    val color = if (system) palette.systemBorder else palette.inkFaint.copy(alpha = 0.5f)
    val inset = size.height * 0.12f
    val stroke = 1.dp.toPx()
    drawLine(color, Offset(0f, inset), Offset(0f, size.height - inset), stroke)
    drawLine(color, Offset(size.width, inset), Offset(size.width, size.height - inset), stroke)
}

/** 开章动画的总时长：墨滴落下、洇开、章名逐字显出、钤印。 */
internal const val STAGE_TITLE_MILLIS = 2_100

/**
 * 章名卡：一滴墨落下洇开，竖排的行书章名从墨里一个字一个字显出来，左边一列小字章号，最后钤一方印。
 * 章名带西文、太长或精排书自定了章名样式时改为横排（照书里的样子）。墨滴落纸的那一刻回调 [onImpact]（配音用）。
 */
@Composable
internal fun StageTitleCard(
    title: String,
    palette: StagePalette,
    fonts: StageFonts,
    key: Any,
    instant: Boolean,
    withArt: Boolean = false,
    ink: StageInk? = null,
    onImpact: () -> Unit = {}
) {
    val (number, name) = remember(title) { StageTextRules.splitChapterTitle(title) }
    val progress = remember(key) { Animatable(if (instant) 1f else 0f) }
    val currentImpact by rememberUpdatedState(onImpact)
    LaunchedEffect(key) {
        if (instant) return@LaunchedEffect
        launch {
            delay((STAGE_TITLE_MILLIS * INK_DROP_IMPACT).toLong())
            currentImpact()
        }
        progress.animateTo(1f, tween(STAGE_TITLE_MILLIS, easing = LinearEasing))
    }
    // 演到一半被点按（或后退回来）：直接收到最后一帧。
    LaunchedEffect(instant) { if (instant && progress.value < 1f) progress.snapTo(1f) }
    val vertical = ink == null && StageVertical.suits(name, 12)
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val brush = fonts.brush ?: fonts.body
    val nameSize = when {
        name.length <= 5 -> 46f
        name.length <= 8 -> 40f
        else -> 33f
    }
    val glyphStyle = TextStyle(fontFamily = brush, fontSize = nameSize.sp, color = palette.ink, fontWeight = if (fonts.brush == null) FontWeight.SemiBold else FontWeight.Normal)
    val numberStyle = TextStyle(fontFamily = fonts.body, fontSize = 13.sp, color = palette.inkSoft, letterSpacing = 0.2.em)
    val glyphs = remember(name, glyphStyle) { if (vertical) name.map { measurer.measure(it.toString(), glyphStyle) } else emptyList() }
    val numberGlyphs = remember(number, numberStyle) { if (vertical) number.map { measurer.measure(it.toString(), numberStyle) } else emptyList() }
    val seed = remember(title) { title.hashCode() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val emPx = with(density) { nameSize.sp.toPx() }
        val cell = emPx * 1.14f
        val columnHeight = cell * (if (vertical) name.length else 1)
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val columnX = widthPx * 0.56f
        val top = ((heightPx - columnHeight) / 2f).coerceAtLeast(0f)
        val dropTarget = Offset(if (vertical) columnX else widthPx / 2f, if (vertical) top + cell * 0.45f else heightPx / 2f)
        Canvas(Modifier.fillMaxSize()) {
            val p = progress.value
            drawInkDrop(p, dropTarget, palette.ink, seed)
            if (!vertical) return@Canvas
            for ((i, layout) in glyphs.withIndex()) {
                val local = ((p - INK_DROP_IMPACT - 0.03f - i * 0.055f) / 0.34f).coerceIn(0f, 1f)
                val small = name[i] in StageVertical.SMALL_MARKS
                val x = columnX - layout.size.width / 2f + (if (small) emPx * 0.3f else 0f)
                val y = top + i * cell + (cell - layout.size.height) / 2f - (if (small) emPx * 0.3f else 0f)
                if (name[i] in StageVertical.ROTATED) {
                    withTransform({ rotate(90f, Offset(columnX, top + i * cell + cell / 2f)) }) {
                        drawInkGlyph(layout, Offset(x, y), local, palette.ink, emPx)
                    }
                } else {
                    drawInkGlyph(layout, Offset(x, y), local, palette.ink, emPx)
                }
            }
            // 章号：章名左侧一列小字，跟在章名后面淡入。
            val numberAlpha = ((p - 0.62f) / 0.22f).coerceIn(0f, 1f)
            if (numberAlpha > 0f) {
                val numberX = columnX - cell * 1.05f
                val step = 18.dp.toPx()
                for ((i, layout) in numberGlyphs.withIndex()) {
                    drawText(layout, topLeft = Offset(numberX - layout.size.width / 2f, top + cell * 0.1f + i * step), alpha = numberAlpha)
                }
            }
        }
        if (!vertical) {
            // 横排：章号在上，章名在中，下面一道朱砂短线；也从墨里显出来。
            Column(
                Modifier.align(Alignment.Center).widthIn(max = 300.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (number.isNotEmpty()) {
                    Text(number, style = numberStyle, modifier = Modifier.graphicsLayer { alpha = ((progress.value - 0.5f) / 0.3f).coerceIn(0f, 1f) })
                    Spacer(Modifier.height(MoReadSpacing.l))
                }
                Text(
                    name,
                    style = TextStyle(
                        color = palette.ink,
                        fontFamily = fonts.body,
                        fontSize = StageType.TITLE.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.em,
                        textAlign = TextAlign.Center,
                        lineHeight = 1.4.em
                    ).withInk(ink?.copy(sizeEm = null), fonts, palette),
                    modifier = Modifier.graphicsLayer { alpha = ((progress.value - INK_DROP_IMPACT) / 0.4f).coerceIn(0f, 1f) }
                )
                Spacer(Modifier.height(MoReadSpacing.xl))
                Box(
                    Modifier
                        .height(1.dp)
                        .width(56.dp)
                        .graphicsLayer {
                            scaleX = ((progress.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
                            alpha = scaleX
                        }
                        .background(palette.accent)
                )
            }
        } else {
            // 钤印：章号下方一方朱印，刻章序。
            val sealX = with(density) { (columnX - cell * 1.05f).toDp() } - 13.dp
            val sealY = with(density) { (top + cell * 0.1f + 18.dp.toPx() * numberGlyphs.size + 10.dp.toPx()).toDp() }
            // 只有真有章序时才钤印（封面、前言这类没有序号的篇章不钤）。
            val showSeal = number.isNotEmpty() && (instant || progress.value >= 0.80f)
            if (showSeal) {
                StageSeal(
                    text = remember(number) { chapterOrdinal(number) },
                    color = palette.accent,
                    paper = palette.background,
                    fonts = fonts,
                    size = 26.dp,
                    stamp = !instant,
                    key = key,
                    modifier = Modifier.offset(x = sealX, y = sealY)
                )
            }
        }
    }
}

/** 章号里的序数：「第十二章」刻「十二」，「第128章」刻「128」；认不出就刻「章」。 */
internal fun chapterOrdinal(number: String): String {
    val core = number.removePrefix(StageTextRules.ORDINAL_PREFIX).trimEnd { it in StageTextRules.ORDINAL_SUFFIXES || it == ' ' }
    return core.takeIf { it.isNotBlank() && it.length <= 3 } ?: StageTextRules.ORDINAL_FALLBACK
}

@Composable
internal fun StageCaption(beat: StageBeat, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.width(28.dp).height(1.dp).background(palette.inkFaint))
        Spacer(Modifier.width(MoReadSpacing.m))
        StageKineticText(
            text = beat.text,
            spans = emptyList(),
            style = TextStyle(fontFamily = fonts.body, fontSize = StageType.CAPTION.sp, letterSpacing = 0.3.em, color = palette.inkSoft, textAlign = TextAlign.Center),
            palette = palette,
            clock = clock,
            motion = StageTextMotion(reduceMotion = reduceMotion),
            modifier = Modifier.widthIn(max = 240.dp)
        )
        Spacer(Modifier.width(MoReadSpacing.m))
        Box(Modifier.width(28.dp).height(1.dp).background(palette.inkFaint))
    }
}

/** 拟声：特大字逐个砸下；每个拟声字的出现时刻用于触发轻震屏。 */
internal fun sfxSchedule(text: String, speed: Float): com.mozhi.reader.core.stage.RevealSchedule {
    val base = StageReveal.schedule(text, emptyList(), typing = false)
    var t = 0
    val starts = IntArray(base.clusters.size)
    base.clusters.forEachIndexed { i, range ->
        starts[i] = t
        val piece = text.substring(range.first, range.last + 1)
        t += if (piece.any { it.isLetter() }) (380 / speed).toInt() else 40
    }
    return base.copy(startMillis = starts, totalMillis = t + 400)
}

@Composable
internal fun StageSfx(beat: StageBeat, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean) {
    // 一行放得下为准：按字数从 80sp 往下缩，最小 42sp；行书比黑体更有砸下来的笔势。
    val glyphSize = (320f / beat.text.length.coerceAtLeast(1)).coerceIn(42f, 80f)
    val brush = fonts.brush
    val seed = remember(beat.text) { beat.text.hashCode() }
    Box(contentAlignment = Alignment.Center) {
        if (!reduceMotion) {
            // 溅墨：第一个拟声字砸下时甩出去，读进度只在绘制阶段。
            Canvas(Modifier.matchParentSize()) {
                val elapsed = clock.elapsed(clock.frame.longValue)
                val p = if (elapsed >= Long.MAX_VALUE / 8) 1f else ((elapsed - 120f) / 420f).coerceIn(0f, 1f)
                inkSplatter(center, size.maxDimension * 0.55f, palette.ink, seed, 22, p)
            }
        }
        StageKineticText(
            text = beat.text,
            spans = emptyList(),
            style = TextStyle(
                fontFamily = brush ?: fonts.body,
                fontSize = glyphSize.sp,
                fontWeight = if (brush == null) FontWeight.Black else FontWeight.Normal,
                color = palette.ink,
                textAlign = TextAlign.Center,
                lineHeight = 1.15.em
            ),
            palette = palette,
            clock = clock,
            motion = StageTextMotion(pop = true, reduceMotion = reduceMotion),
            modifier = Modifier.widthIn(max = 330.dp)
        )
    }
}

/** 系统面板：等宽字体、四角框线、原文【】里的短标题作页眉。 */
@Composable
internal fun StageSystemPanel(beat: StageBeat, presentation: StagePresentation, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean, look: Boolean = true) {
    val box = beat.box?.takeIf { look }
    if (box != null) {
        // 精排书自己画的系统框：照书里的底色、边框与字体。
        Column(Modifier.widthIn(min = 220.dp, max = 316.dp).stageBookBox(box, palette), horizontalAlignment = Alignment.CenterHorizontally) {
            beat.systemLabel?.let { label ->
                Text(label, color = palette.inkSoft, fontSize = 11.sp, letterSpacing = 0.24.em, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(MoReadSpacing.s))
            }
            StageKineticText(
                text = beat.text,
                spans = presentation.spans,
                style = TextStyle(fontFamily = fonts.body, fontSize = StageType.SYSTEM.sp, lineHeight = 1.7.em, color = palette.ink, textAlign = TextAlign.Center)
                    .withInk(beat.ink ?: box.ink, fonts, palette),
                palette = palette,
                clock = clock,
                motion = StageTextMotion(reduceMotion = reduceMotion)
            )
        }
        return
    }
    Column(
        modifier = Modifier
            .widthIn(min = 220.dp, max = 316.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(palette.systemPanel)
            .border(1.dp, palette.systemBorder, RoundedCornerShape(6.dp))
            .drawBehind {
                val corner = 10.dp.toPx()
                val stroke = 2.dp.toPx()
                val c = palette.systemInk.copy(alpha = 0.8f)
                drawLine(c, Offset(0f, 0f), Offset(corner, 0f), stroke)
                drawLine(c, Offset(0f, 0f), Offset(0f, corner), stroke)
                drawLine(c, Offset(size.width, size.height), Offset(size.width - corner, size.height), stroke)
                drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - corner), stroke)
                var y = 0f
                val gap = 3.dp.toPx()
                while (y < size.height) {
                    drawLine(Color.White.copy(alpha = 0.03f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += gap
                }
            }
            .padding(horizontal = MoReadSpacing.l + 2.dp, vertical = MoReadSpacing.l)
    ) {
        beat.systemLabel?.let { label ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(palette.systemInk))
                Spacer(Modifier.width(MoReadSpacing.s))
                Text(label, color = palette.systemInk.copy(alpha = 0.85f), fontSize = 11.sp, letterSpacing = 0.24.em, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(MoReadSpacing.s + 2.dp))
        }
        StageKineticText(
            text = beat.text,
            spans = presentation.spans,
            style = TextStyle(fontFamily = fonts.mono, fontSize = StageType.SYSTEM.sp, lineHeight = 1.7.em, color = palette.systemInk, textAlign = TextAlign.Start),
            palette = palette,
            clock = clock,
            motion = StageTextMotion(reduceMotion = reduceMotion)
        )
    }
}

@Composable
internal fun StageNote(beat: StageBeat, palette: StagePalette, fonts: StageFonts) {
    Text(
        beat.text,
        color = palette.inkFaint,
        fontFamily = fonts.body,
        fontSize = StageType.NOTE.sp,
        letterSpacing = 0.08.em,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 280.dp)
    )
}

/** 舞台中心区左右留白与最大宽度。 */
internal val StageContentMaxWidth: Dp = 340.dp
