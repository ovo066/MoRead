package com.mozhi.reader.feature.reader.stage

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.stage.BeatKind
import com.mozhi.reader.core.stage.LetterLineRole
import com.mozhi.reader.core.stage.StageBeat
import com.mozhi.reader.core.stage.StageContainer
import com.mozhi.reader.core.stage.StageContainerType
import com.mozhi.reader.core.stage.StageTextRules
import com.mozhi.reader.ui.theme.MoReadSpacing
import kotlinx.coroutines.delay

/** 版式容器里已经出场的项：最新一项正在揭示，之前的静止显示。 */
internal data class StageContainerFrame(
    val container: StageContainer,
    val items: List<StageBeat>,
    /** 最新一项在 [items] 里的位置；为 -1 时整卡静止（旁白或系统提示盖在上面）。 */
    val latest: Int,
    val instant: Boolean,
    /** 沿用精排书的框与气泡颜色。 */
    val look: Boolean = true
)

@Composable
internal fun StageContainerView(
    frame: StageContainerFrame,
    palette: StagePalette,
    fonts: StageFonts,
    clock: StageRevealClock,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier
) {
    when (frame.container.type) {
        StageContainerType.CHAT -> StageChatCard(frame, palette, fonts, modifier)
        StageContainerType.FORUM -> StageForumCard(frame, palette, fonts, clock, reduceMotion, modifier)
        StageContainerType.LETTER -> StageLetterCard(frame, palette, fonts, clock, reduceMotion, modifier)
        StageContainerType.STATUS -> StageStatusPanel(frame, palette, fonts, modifier)
        StageContainerType.VERSE -> StageVerse(frame, palette, fonts, modifier)
        StageContainerType.DANMAKU -> StageDanmaku(frame, palette, fonts, reduceMotion, modifier)
        StageContainerType.NEWS -> StageNewsCard(frame, palette, fonts, modifier)
        StageContainerType.SOCIAL -> StageSocialCard(frame, palette, fonts, modifier)
        StageContainerType.DOCUMENT -> StageDocumentCard(frame, palette, fonts, modifier)
        StageContainerType.NOTEBOOK -> StageNotebookCard(frame, palette, fonts, clock, reduceMotion, modifier)
    }
}

@Composable
internal fun StageCard(palette: StagePalette, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .widthIn(max = 322.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.card)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(24.dp))
    ) { content() }
}

/** 新出场的一项：从下方或侧边滑入；之前的项不再动。 */
@Composable
internal fun Entering(animate: Boolean, fromSide: Int = 0, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(!animate).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = state,
        enter = fadeIn(tween(320)) + if (fromSide != 0) {
            slideInHorizontally(tween(380)) { it / 10 * fromSide }
        } else {
            slideInVertically(tween(380)) { it / 3 }
        }
    ) { content() }
}

@Composable
private fun StageChatCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(frame.items.size) { if (frame.items.isNotEmpty()) list.animateScrollToItem(frame.items.lastIndex) }
    val multiParty = frame.items.filter { !(it.item?.isMe ?: false) }.mapNotNull { it.item?.sender }.distinct().size > 1
    StageCard(palette, modifier) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = palette.inkFaint, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(MoReadSpacing.xs))
                Text(frame.container.title.orEmpty(), color = palette.ink, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = 0.dp).background(palette.cardBorder).heightIn(min = 1.dp, max = 1.dp))
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(MoReadSpacing.m),
                verticalArrangement = Arrangement.spacedBy(MoReadSpacing.s + 2.dp)
            ) {
                itemsIndexed(frame.items, key = { _, beat -> beat.range?.start ?: 0 }) { index, beat ->
                    val animate = index == frame.latest && !frame.instant
                    StageChatMessage(beat, animate, multiParty, palette, fonts, frame.look)
                }
            }
        }
    }
}

@Composable
private fun StageChatMessage(beat: StageBeat, animate: Boolean, multiParty: Boolean, palette: StagePalette, fonts: StageFonts, look: Boolean = true) {
    val item = beat.item
    val me = item?.isMe == true
    val sender = item?.sender.orEmpty()
    val bookBubble = item?.bubble?.takeIf { look }
    // 别人的消息先出「正在输入」再出气泡；自己的直接发出。
    var typing by remember { mutableStateOf(animate && !me) }
    LaunchedEffect(Unit) { if (typing) { delay(520); typing = false } }
    Entering(animate, fromSide = if (me) 1 else -1) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (me) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            if (!me) {
                ChatAvatar(sender, palette, item?.avatar)
                Spacer(Modifier.width(MoReadSpacing.s))
            }
            Column(horizontalAlignment = if (me) Alignment.End else Alignment.Start, modifier = Modifier.widthIn(max = 230.dp)) {
                if (multiParty && !me && sender.isNotEmpty()) {
                    Text(sender, color = palette.inkFaint, fontSize = 10.5.sp, modifier = Modifier.padding(start = MoReadSpacing.xs, bottom = 2.dp))
                }
                val shape = if (me) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
                Box(
                    Modifier
                        .clip(shape)
                        .background(bookBubble?.backgroundArgb?.let { bookFill(it, palette) } ?: if (me) palette.bubbleMe else palette.bubble)
                        .then(
                            when {
                                bookBubble?.borderArgb != null && bookBubble.borderWidthPx > 0f -> Modifier.border(1.dp, bookInk(bookBubble.borderArgb, palette.background, palette).copy(alpha = 0.5f), shape)
                                me || bookBubble?.backgroundArgb != null -> Modifier
                                else -> Modifier.border(1.dp, palette.cardBorder, shape)
                            }
                        )
                        .padding(horizontal = MoReadSpacing.m, vertical = if (typing) MoReadSpacing.m else 9.dp)
                ) {
                    if (typing) {
                        TypingDots(palette)
                    } else if (item?.voice == true) {
                        VoiceBubble(beat.text, palette)
                    } else {
                        Text(
                            beat.text,
                            color = if (item?.sticker == true) palette.inkSoft else palette.ink,
                            fontFamily = fonts.body,
                            fontSize = if (item?.sticker == true) 12.sp else 14.sp,
                            lineHeight = 1.6.em
                        )
                    }
                }
            }
            if (me) {
                Spacer(Modifier.width(MoReadSpacing.s))
                ChatAvatar(sender, palette, item?.avatar)
            }
        }
    }
}

@Composable
private fun ChatAvatar(name: String, palette: StagePalette, image: com.mozhi.reader.core.stage.StageImage? = null) {
    if (image != null && StageBookAvatar(image)) return
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(if (name.isEmpty()) palette.inkFaint else palette.roleColor(name)),
        contentAlignment = Alignment.Center
    ) {
        Text(name.take(1), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TypingDots(palette: StagePalette) {
    val phase = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) {
        phase.animateTo(1f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer {
                        val t = (phase.value + i * 0.18f) % 1f
                        alpha = 0.3f + 0.7f * (1f - kotlin.math.abs(t - 0.5f) * 2f)
                    }
                    .clip(CircleShape)
                    .background(palette.inkFaint)
            )
        }
    }
}

/** 论坛：版块名与标题作页眉，楼层逐层叠加；同一层的下一段并进同一层。 */
@Composable
private fun StageForumCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean, modifier: Modifier) {
    data class Floor(val first: StageBeat, val paragraphs: MutableList<StageBeat>)
    val floors = remember(frame.items) {
        val out = mutableListOf<Floor>()
        for (beat in frame.items) {
            if (beat.kind != BeatKind.FORUM) continue
            if (beat.item?.continuation == true && out.isNotEmpty()) out.last().paragraphs += beat
            else out += Floor(beat, mutableListOf(beat))
        }
        out
    }
    val latestBeat = frame.items.getOrNull(frame.latest)
    val list = rememberLazyListState()
    LaunchedEffect(floors.size, frame.items.size) { if (floors.isNotEmpty()) list.animateScrollToItem(floors.lastIndex) }
    StageCard(palette, modifier) {
        Column {
            frame.container.board?.takeIf(String::isNotBlank)?.let { board ->
                Row(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(palette.accent))
                    Spacer(Modifier.width(MoReadSpacing.s))
                    Text(board, color = palette.inkSoft, fontSize = 11.5.sp, letterSpacing = 0.06.em, maxLines = 1)
                }
            }
            frame.container.title?.takeIf(String::isNotBlank)?.let { title ->
                Text(
                    title,
                    color = palette.ink,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 1.55.em,
                    modifier = Modifier.padding(start = MoReadSpacing.l, end = MoReadSpacing.l, bottom = MoReadSpacing.m)
                )
            }
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                itemsIndexed(floors, key = { _, floor -> floor.first.range?.start ?: 0 }) { _, floor ->
                    val isLatestFloor = floor.paragraphs.any { it === latestBeat }
                    Entering(isLatestFloor && floor.first === latestBeat && !frame.instant) {
                        ForumFloor(floor.first, floor.paragraphs, latestBeat, palette, fonts, clock, reduceMotion, frame.instant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ForumFloor(
    first: StageBeat,
    paragraphs: List<StageBeat>,
    latest: StageBeat?,
    palette: StagePalette,
    fonts: StageFonts,
    clock: StageRevealClock,
    reduceMotion: Boolean,
    instant: Boolean
) {
    val item = first.item
    val floor = item?.floor.orEmpty()
    val author = item?.author.orEmpty()
    val op = StageTextRules.isOriginalPoster(floor)
    Column {
        Box(Modifier.fillMaxWidth().background(palette.cardBorder).heightIn(min = 1.dp, max = 1.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m)) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(palette.roleColor(author.ifEmpty { floor })),
                contentAlignment = Alignment.Center
            ) {
                Text(author.ifEmpty { floor }.take(1), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(MoReadSpacing.s + 2.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (author.isNotEmpty()) Text(author, color = palette.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (op) {
                        Spacer(Modifier.width(MoReadSpacing.xs + 2.dp))
                        Text(
                            floor,
                            color = Color.White,
                            fontSize = 9.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(palette.accent).padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val meta = listOfNotNull(item?.time?.takeIf(String::isNotBlank), floor.takeIf { !op && it.isNotEmpty() }).joinToString("  ")
                    if (meta.isNotEmpty()) Text(meta, color = palette.inkFaint, fontSize = 10.5.sp, maxLines = 1)
                }
                for (paragraph in paragraphs) {
                    if (paragraph === latest && !instant) {
                        StageKineticText(
                            text = paragraph.text,
                            spans = paragraph.spans,
                            style = TextStyle(fontFamily = fonts.body, fontSize = 13.5.sp, lineHeight = 1.65.em, color = palette.ink, textAlign = TextAlign.Start),
                            palette = palette,
                            clock = clock,
                            motion = StageTextMotion(reduceMotion = reduceMotion),
                            modifier = Modifier.padding(top = MoReadSpacing.xs)
                        )
                    } else {
                        Text(paragraph.text, color = palette.ink, fontFamily = fonts.body, fontSize = 13.5.sp, lineHeight = 1.65.em, modifier = Modifier.padding(top = MoReadSpacing.xs))
                    }
                }
            }
        }
    }
}

/** 书信：信纸、略微倾斜，一拍写一行；称呼抬头、诗句居中、落款右对齐。 */
@Composable
private fun StageLetterCard(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, clock: StageRevealClock, reduceMotion: Boolean, modifier: Modifier) {
    val lineHeight = 32.dp
    Column(
        modifier
            .widthIn(max = 318.dp)
            .fillMaxWidth()
            .rotate(-1.2f)
            .clip(RoundedCornerShape(4.dp))
            .background(palette.letterPaper)
            .drawBehind {
                val step = lineHeight.toPx()
                var y = 24.dp.toPx() + step
                while (y < size.height - 8.dp.toPx()) {
                    drawLine(Color(0x22785A32), Offset(18.dp.toPx(), y), Offset(size.width - 18.dp.toPx(), y), 1f)
                    y += step
                }
            }
            .padding(horizontal = MoReadSpacing.xl + 4.dp, vertical = MoReadSpacing.xl + 4.dp)
    ) {
        frame.items.forEachIndexed { index, beat ->
            val role = beat.item?.letterRole ?: LetterLineRole.BODY
            val align = when (role) {
                LetterLineRole.VERSE -> TextAlign.Center
                LetterLineRole.SIGNATURE -> TextAlign.End
                else -> TextAlign.Start
            }
            // 书信是手写的：有行书就用行书（字面略小，字号相应放大一档）。
            val hand = fonts.brush
            val style = TextStyle(
                fontFamily = hand ?: fonts.body,
                fontSize = (if (role == LetterLineRole.VERSE) 18f else 19f).let { if (hand != null) it + 2.5f else it }.sp,
                lineHeight = 32.sp,
                color = palette.letterInk,
                letterSpacing = if (role == LetterLineRole.VERSE) 0.06.em else 0.02.em,
                lineBreak = androidx.compose.ui.text.style.LineBreak.Heading,
                textAlign = align,
                textIndent = if (role == LetterLineRole.BODY) androidx.compose.ui.text.style.TextIndent(firstLine = 2.em) else null
            )
            Box(Modifier.fillMaxWidth()) {
                if (index == frame.latest && !frame.instant) {
                    StageKineticText(beat.text, beat.spans, style, palette.copy(ink = palette.letterInk), clock, StageTextMotion(reduceMotion = reduceMotion), Modifier.fillMaxWidth(), fillWidth = true)
                } else {
                    Text(beat.text, style = style, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun StageStatusPanel(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    Column(
        modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.card)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = MoReadSpacing.l, vertical = MoReadSpacing.m)
    ) {
        frame.items.forEachIndexed { index, beat ->
            Entering(index == frame.latest && !frame.instant) {
                val label = beat.item?.label
                when {
                    beat.item?.cardRole == com.mozhi.reader.core.stage.CardLineRole.HEADLINE -> Column(Modifier.fillMaxWidth().padding(vertical = MoReadSpacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(beat.text, color = palette.systemInk, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.em)
                        Spacer(Modifier.height(MoReadSpacing.s))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(palette.systemBorder))
                    }
                    label != null -> Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                        Text(label, color = palette.inkSoft, fontSize = 13.5.sp, modifier = Modifier.width(72.dp))
                        Text(beat.text, color = palette.ink, fontFamily = fonts.mono, fontSize = 13.5.sp, lineHeight = 1.5.em)
                    }
                    else -> Text(beat.text, color = palette.inkSoft, fontFamily = fonts.body, fontSize = 13.sp, lineHeight = 1.65.em, modifier = Modifier.padding(vertical = MoReadSpacing.xs))
                }
            }
        }
    }
}

@Composable
private fun StageVerse(frame: StageContainerFrame, palette: StagePalette, fonts: StageFonts, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        frame.items.forEachIndexed { index, beat ->
            Entering(index == frame.latest && !frame.instant) {
                Text(
                    beat.text,
                    color = palette.ink,
                    fontFamily = fonts.body,
                    fontSize = 21.sp,
                    letterSpacing = 0.18.em,
                    lineHeight = 2.1.em,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
