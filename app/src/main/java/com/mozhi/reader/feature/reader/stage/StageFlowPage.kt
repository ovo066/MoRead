package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.datastore.StageLayoutMode
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageBox
import com.mozhi.reader.core.stage.StagePresentation
import kotlin.math.abs
import kotlin.math.roundToInt

/** 整页里的排版参数：跟随阅读页的字号倍率、行高、段距与首行缩进。 */
internal data class StageTypography(
    val fontScale: Float,
    val proseSize: Float,
    val lineHeightEm: Float,
    val paragraphEm: Float,
    val indentEm: Float,
    val letterSpacingEm: Float
)

/** 整页镜头里的一块：一段旁白（同一原文段落里连续的几拍）、一句对白或心声、一条小字注。 */
private sealed interface FlowBlock {
    val first: Int
    val last: Int
}

private data class ProseBlock(override val first: Int, override val last: Int, val indent: Boolean) : FlowBlock
private data class BalloonBlock(val index: Int) : FlowBlock {
    override val first get() = index
    override val last get() = index
}
private data class NoteBlock(val index: Int) : FlowBlock {
    override val first get() = index
    override val last get() = index
}

private fun flowBlocks(beats: List<StageBeat>, plan: StagePlan, shot: StageShot, upTo: Int): List<FlowBlock> {
    val out = mutableListOf<FlowBlock>()
    var k = shot.first
    while (k <= upTo) {
        val b = beats[k]
        when (b.kind) {
            BeatKind.NARRATION -> {
                var end = k
                // 下一拍也是旁白且不另起一段（planner 已按原文换行判过），就接在同一段里。
                while (end + 1 <= upTo && beats[end + 1].kind == BeatKind.NARRATION && !plan.newParagraph[end + 1]) end++
                out += ProseBlock(k, end, indent = plan.newParagraph[k] && !plan.carriesParagraph[k])
                k = end + 1
            }
            BeatKind.NOTE -> { out += NoteBlock(k); k++ }
            else -> { out += BalloonBlock(k); k++ }
        }
    }
    return out
}

/**
 * 整页镜头：旁白排成书页式的段落，对白是左右交替的气泡，心声是云朵；
 * 新的一拍出现在下面，之前的压淡（可关）。内容超出一屏时贴底，顶上渐隐。
 */
@Composable
internal fun StageFlowPage(
    shot: StageShot,
    plan: StagePlan,
    beats: List<StageBeat>,
    presentations: List<StagePresentation>,
    current: Int,
    clock: StageRevealClock,
    instant: Boolean,
    settings: StageSettings,
    palette: StagePalette,
    fonts: StageFonts,
    names: Set<String>,
    typography: StageTypography,
    reduceMotion: Boolean,
    /** 当前拍已完整显示、在等点按：在它末尾亮一颗朱砂墨点。 */
    cue: Boolean = false
) {
    val upTo = minOf(current, shot.last)
    val blocks = remember(shot, upTo, plan) { flowBlocks(beats, plan, shot, upTo) }
    val single = settings.layout == StageLayoutMode.SINGLE
    val look = settings.bookStyles
    // 同一个书内框里的连续几块画在一个框里（系统框、信纸框、作者的话……）。
    val groups = remember(blocks, look) {
        val out = mutableListOf<Pair<StageBox?, List<FlowBlock>>>()
        for (block in blocks) {
            val box = beats[block.first].box?.takeIf { look }
            val last = out.lastOrNull()
            if (last != null && box != null && last.first == box) out[out.lastIndex] = box to (last.second + block) else out += box to listOf(block)
        }
        out
    }
    StageViewport {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(spring(stiffness = 420f, dampingRatio = 0.9f)),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            for ((box, members) in groups) key(members.first().first) {
                if (box != null) {
                    Column(Modifier.fillMaxWidth().stageBookBox(box, palette), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (block in members) key(block.first) {
                            FlowBlockView(block, shot, plan, beats, presentations, current, upTo, clock, instant, settings, palette, fonts, names, typography, reduceMotion, single, cue)
                        }
                    }
                } else {
                    for (block in members) key(block.first) {
                        FlowBlockView(block, shot, plan, beats, presentations, current, upTo, clock, instant, settings, palette, fonts, names, typography, reduceMotion, single, cue)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlowBlockView(
    block: FlowBlock,
    shot: StageShot,
    plan: StagePlan,
    beats: List<StageBeat>,
    presentations: List<StagePresentation>,
    current: Int,
    upTo: Int,
    clock: StageRevealClock,
    instant: Boolean,
    settings: StageSettings,
    palette: StagePalette,
    fonts: StageFonts,
    names: Set<String>,
    typography: StageTypography,
    reduceMotion: Boolean,
    single: Boolean,
    cue: Boolean
) {
    val isCurrent = current in block.first..block.last
    val target = if (!settings.focusDim || single || isCurrent) 1f else if (block is ProseBlock) 0.72f else 0.78f
    val alpha by animateFloatAsState(target, tween(360), label = "focus")
    val animate = isCurrent && !instant
    val ink = beats[block.first].ink?.takeIf { settings.bookStyles }
    Box(Modifier.fillMaxWidth().graphicsLayer { this.alpha = alpha }) {
        when (block) {
            is ProseBlock -> {
                val shown = block.first..minOf(block.last, upTo)
                val segments = shown.map { StageTextSegment(beats[it].text, presentations[it].spans) }
                StageParagraphText(
                    segments = segments,
                    animate = if (animate) current - block.first else -1,
                    clock = clock.takeIf { animate },
                    style = proseStyle(typography, palette, fonts, block.indent, single, beats[block.first].text.length).withInk(ink, fonts, palette),
                    palette = palette,
                    reveal = settings.textReveal,
                    motion = StageTextMotion(presentations[current.coerceIn(shown)].expression.tremble, false, reduceMotion),
                    modifier = Modifier.fillMaxWidth(),
                    fillWidth = true,
                    fonts = fonts,
                    cue = cue && isCurrent
                )
            }
            is BalloonBlock -> {
                val beat = beats[block.index]
                val previous = beats.getOrNull(block.index - 1)
                val repeatName = previous?.speaker?.label != null && previous.speaker?.label == beat.speaker?.label &&
                    previous.kind == BeatKind.DIALOGUE && block.index - 1 >= shot.first
                StageBalloon(
                    beat = beat,
                    presentation = presentations[block.index],
                    side = plan.sides[block.index] ?: StageSide.LEFT,
                    showName = !repeatName,
                    showAsides = settings.showAsides,
                    animate = animate,
                    clock = clock.takeIf { animate },
                    reveal = settings.textReveal,
                    palette = palette,
                    fonts = fonts,
                    names = names,
                    fontScale = typography.fontScale,
                    reduceMotion = reduceMotion,
                    ink = ink,
                    cue = cue && isCurrent
                )
            }
            is NoteBlock -> Text(
                beats[block.index].text,
                color = palette.inkFaint,
                fontFamily = fonts.body,
                fontSize = (13f * typography.fontScale).sp,
                lineHeight = 1.6.em,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
            )
        }
    }
}

private fun proseStyle(t: StageTypography, palette: StagePalette, fonts: StageFonts, indent: Boolean, single: Boolean, length: Int): TextStyle {
    var size = t.proseSize
    if (single) size += 1f
    if (single && length > StageType.LONG_TEXT) size -= 2f
    return TextStyle(
        fontFamily = fonts.body,
        fontSize = size.sp,
        lineHeight = t.lineHeightEm.em,
        letterSpacing = t.letterSpacingEm.em,
        color = palette.ink,
        fontWeight = FontWeight.Normal,
        textAlign = if (single) TextAlign.Center else TextAlign.Justify,
        textIndent = if (indent && !single) TextIndent(firstLine = t.indentEm.em) else null,
        lineBreak = if (single) LineBreak.Heading else LineBreak.Paragraph
    )
}

/**
 * 舞台中区的视口：内容放得下时垂直居中；放不下时贴底跟随最新一拍，顶上渐隐。
 * 不接受手指滚动（上滑是回看、下滑是退出），只由程序跟随。
 */
@Composable
internal fun StageViewport(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scroll = rememberScrollState()
    val fade = with(LocalDensity.current) { 56.dp.toPx() }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (scroll.value > 0 && size.height > fade) {
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, fade / size.height to Color.Black), blendMode = BlendMode.DstIn)
                    }
                }
                .verticalScroll(scroll, enabled = false)
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = viewport), contentAlignment = Alignment.Center) { content() }
        }
    }
    LaunchedEffect(scroll) {
        // 首帧直接贴底（翻回一页已读完的长页时不演滚动），之后按指数缓动跟随。
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
        snapshotFlow { scroll.maxValue }.collect {
            while (scroll.value != scroll.maxValue) {
                withFrameNanos { }
                val target = scroll.maxValue
                val now = scroll.value
                val step = ((target - now) * 0.22f).roundToInt()
                scroll.scrollTo(if (abs(step) < 2) target else now + step)
            }
        }
    }
}

/** 分页估算用的尺寸，按与绘制相同的字号与行高换算成像素。 */
internal fun stageMetrics(size: IntSize, density: androidx.compose.ui.unit.Density, t: StageTypography): StageMetrics = with(density) {
    val prose = t.proseSize.sp.toPx()
    val balloon = 17f * t.fontScale
    StageMetrics(
        widthPx = size.width.toFloat(),
        heightPx = size.height.toFloat(),
        proseFontPx = prose * (1f + t.letterSpacingEm),
        proseLinePx = prose * t.lineHeightEm,
        paragraphGapPx = 14.dp.toPx(),
        balloonFontPx = balloon.sp.toPx(),
        balloonLinePx = balloon.sp.toPx() * 1.62f,
        balloonPadHPx = 16.dp.toPx(),
        balloonPadVPx = 20.dp.toPx(),
        captionFontPx = (12.5f * t.fontScale).sp.toPx(),
        captionLinePx = (12.5f * t.fontScale).sp.toPx() * 1.55f + 6.dp.toPx(),
        blockGapPx = 14.dp.toPx(),
        indentEm = t.indentEm
    )
}
