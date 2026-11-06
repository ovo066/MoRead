package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.stage.CardLineRole
import com.mozhi.reader.core.stage.SpanStyle
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.ui.theme.MoReadSpacing
import kotlin.math.abs

/** 语音消息：声波条 + 原文写的秒数。 */
@Composable
internal fun VoiceBubble(text: String, palette: StagePalette) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, tint = palette.inkSoft, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(MoReadSpacing.s))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(6, 11, 8, 14, 9, 12, 6).forEach { h -> Box(Modifier.width(2.dp).height(h.dp).clip(CircleShape).background(palette.inkSoft)) }
        }
        if (text.isNotBlank()) {
            Spacer(Modifier.width(MoReadSpacing.s))
            Text(text, color = palette.inkSoft, fontSize = 12.sp)
        }
    }
}

/**
 * 弹幕：每条从右向左飘过一次，分几条轨道错开；最新一条入场，之前的继续在各自轨道上。
 * 「移除动画」时退化为一列静止的胶囊。
 */
@Composable
internal fun StageDanmaku(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, reduceMotion: Boolean, modifier: Modifier) {
    val items = frame.items
    if (reduceMotion || frame.instant) {
        Column(modifier.widthIn(max = 330.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            items.takeLast(6).forEachIndexed { i, beat -> DanmakuPill(beat, palette, fonts, Modifier.align(if (i % 2 == 0) Alignment.Start else Alignment.End)) }
        }
        return
    }
    BoxWithConstraints(modifier.fillMaxWidth().height(LANE_HEIGHT * LANES + 12.dp)) {
        val width = maxWidth
        items.forEachIndexed { index, beat ->
            val key = beat.range?.start ?: index
            val lane = index % LANES
            val progress = remember(key) { Animatable(if (index < items.size - LANES * 2) 1f else 0f) }
            LaunchedEffect(key) { progress.animateTo(1f, tween(6_500 + (abs(key) % 5) * 700, easing = LinearEasing)) }
            if (progress.value < 1f) {
                DanmakuPill(
                    beat, palette, fonts,
                    Modifier
                        .offset(y = LANE_HEIGHT * lane)
                        .graphicsLayer { translationX = (width.toPx() + 40.dp.toPx()) * (1f - progress.value) - size.width * progress.value }
                )
            }
        }
    }
}

private val LANE_HEIGHT = 38.dp
private const val LANES = 4

@Composable
private fun DanmakuPill(beat: StageBeat, palette: StagePalette, fonts: StageFonts, modifier: Modifier = Modifier) {
    val tint = palette.roleColor(beat.text.take(2))
    Text(
        beat.text,
        color = if (palette.isDark) Color.White else palette.ink,
        fontFamily = fonts.body,
        fontSize = 14.sp,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = if (palette.isDark) 0.28f else 0.14f))
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = MoReadSpacing.m, vertical = 6.dp)
    )
}

/** 报纸：报头、粗细双线、大字标题，正文分栏前对齐；纸色偏黄。 */
@Composable
internal fun StageNewsCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    val ink = palette.letterInk
    Column(
        modifier
            .widthIn(max = 318.dp)
            .fillMaxWidth()
            .rotate(0.6f)
            .background(palette.letterPaper)
            .border(1.dp, ink.copy(alpha = 0.18f))
            .padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.l)
    ) {
        frame.items.forEachIndexed { index, beat ->
            Entering(index == frame.latest && !frame.instant) {
                when (beat.item?.cardRole) {
                    CardLineRole.MASTHEAD -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(beat.text, color = ink, fontFamily = fonts.body, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 0.25.em, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(MoReadSpacing.xs))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(ink))
                        Spacer(Modifier.height(2.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(ink))
                        Spacer(Modifier.height(MoReadSpacing.m))
                    }
                    CardLineRole.HEADLINE -> Text(
                        beat.text, color = ink, fontFamily = fonts.body, fontSize = 19.sp, fontWeight = FontWeight.Bold, lineHeight = 1.45.em,
                        modifier = Modifier.fillMaxWidth().padding(vertical = MoReadSpacing.xs)
                    )
                    else -> Text(
                        beat.text, color = ink.copy(alpha = 0.85f), fontFamily = fonts.body, fontSize = 13.sp, lineHeight = 1.7.em,
                        style = TextStyle(textIndent = TextIndent(firstLine = 2.em), textAlign = TextAlign.Justify),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

/** 社交动态：头像、账号、正文、时间与互动栏。 */
@Composable
internal fun StageSocialCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    val account = frame.items.firstOrNull { it.item?.cardRole == CardLineRole.ACCOUNT }?.text.orEmpty()
    StageCard(palette, modifier) {
        Column(Modifier.padding(MoReadSpacing.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val seed = account.trimStart('@').ifEmpty { "#" }
                Box(Modifier.size(36.dp).clip(CircleShape).background(palette.roleColor(seed)), contentAlignment = Alignment.Center) {
                    Text(seed.take(1), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(MoReadSpacing.m))
                Text(account, color = palette.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.height(MoReadSpacing.m))
            frame.items.forEachIndexed { index, beat ->
                val role = beat.item?.cardRole
                if (role == CardLineRole.ACCOUNT) return@forEachIndexed
                Entering(index == frame.latest && !frame.instant) {
                    if (role == CardLineRole.META) {
                        Text(beat.text, color = palette.inkFaint, fontSize = 11.sp, modifier = Modifier.padding(top = MoReadSpacing.s))
                    } else {
                        Text(beat.text, color = palette.ink, fontFamily = fonts.body, fontSize = 14.sp, lineHeight = 1.65.em, modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(MoReadSpacing.m))
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.cardBorder))
            Row(Modifier.fillMaxWidth().padding(top = MoReadSpacing.s), horizontalArrangement = Arrangement.SpaceAround) {
                Icon(Icons.Outlined.ModeComment, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(16.dp))
                Icon(Icons.Outlined.FavoriteBorder, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** 文件 / 档案 / 清单：左侧装订线，条目带方框，删除线照原文。 */
@Composable
internal fun StageDocumentCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 318.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(palette.card)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(6.dp))
            .drawBehind { drawRect(palette.accent, size = size.copy(width = 3.dp.toPx())) }
            .padding(start = MoReadSpacing.l + 3.dp, end = MoReadSpacing.l, top = MoReadSpacing.m, bottom = MoReadSpacing.m)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(MoReadSpacing.xs))
            Box(Modifier.weight(1f).height(1.dp).drawBehind {
                drawLine(palette.inkFaint, Offset(0f, 0f), Offset(size.width, 0f), 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            })
        }
        Spacer(Modifier.height(MoReadSpacing.s))
        frame.items.forEachIndexed { index, beat ->
            Entering(index == frame.latest && !frame.instant) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    if (beat.item?.cardRole == CardLineRole.ITEM) {
                        Box(Modifier.padding(top = 5.dp).size(10.dp).border(1.dp, palette.inkSoft, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(MoReadSpacing.s))
                    }
                    Text(stageAnnotated(beat.text, beat.spans, palette), color = palette.ink, fontFamily = fonts.mono, fontSize = 13.sp, lineHeight = 1.6.em)
                }
            }
        }
    }
}

/**
 * 笔记 / 日记 / 引文：书里排成方框的段落。方格纸底，左侧竖线；
 * 只有一行时当一张便签，居中略斜。
 */
@Composable
internal fun StageNotebookCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean, modifier: Modifier) {
    val single = frame.items.size == 1 && frame.items[0].text.length <= 40
    val list = rememberLazyListState()
    LaunchedEffect(frame.items.size) { if (frame.items.isNotEmpty()) list.animateScrollToItem(frame.items.lastIndex) }
    val bookBox = frame.container.box?.takeIf { frame.look }
    val paper = palette.letterPaper
    // 书里给这一块画了框：照书里的底色与边框；文字用框内的字色（换到舞台底色上保证可读）。
    val ink = if (bookBox != null) (bookBox.ink?.colorArgb?.let { bookInk(it, palette.background, palette) } ?: palette.ink) else palette.letterInk
    Box(
        if (bookBox != null) modifier.widthIn(max = 318.dp).fillMaxWidth().stageBookBox(bookBox, palette) else modifier
            .widthIn(max = 318.dp)
            .fillMaxWidth()
            .rotate(if (single) -1.5f else 0f)
            .clip(RoundedCornerShape(8.dp))
            .background(paper)
            .drawBehind {
                val step = 22.dp.toPx()
                var y = step
                while (y < size.height) { drawLine(ink.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
                drawLine(palette.accent.copy(alpha = 0.45f), Offset(18.dp.toPx(), 0f), Offset(18.dp.toPx(), size.height), 1.5f)
            }
            .padding(start = 30.dp, end = MoReadSpacing.l, top = MoReadSpacing.l, bottom = MoReadSpacing.l)
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s)) {
            itemsIndexed(frame.items, key = { _, b -> b.range?.start ?: 0 }) { index, beat ->
                val headline = beat.item?.cardRole == CardLineRole.HEADLINE
                val style = TextStyle(
                    fontFamily = fonts.body,
                    fontSize = if (headline) 15.sp else if (single) 17.sp else 14.5.sp,
                    fontWeight = if (headline) FontWeight.Bold else null,
                    lineHeight = 1.7.em,
                    color = ink,
                    textAlign = if (single || headline) TextAlign.Center else TextAlign.Start,
                    textDecoration = if (beat.spans.any { SpanStyle.STRIKE in it.styles && it.start == 0 && it.end == beat.text.length }) TextDecoration.LineThrough else null
                )
                if (index == frame.latest && !frame.instant) {
                    StageKineticText(beat.text, beat.spans, style, palette.copy(ink = ink), clock, StageTextMotion(reduceMotion = reduceMotion), Modifier.fillMaxWidth(), fillWidth = true)
                } else {
                    Text(stageAnnotated(beat.text, beat.spans, palette.copy(ink = ink)), style = style, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
