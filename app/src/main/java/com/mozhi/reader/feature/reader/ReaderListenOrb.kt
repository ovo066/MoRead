package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.FastRewind
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.ai.listen.ListenState
import com.mozhi.reader.core.speech.SleepTimerPlanner
import com.mozhi.reader.core.speech.SleepTimerState
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val ORB_SIZE = 46.dp
private val EDGE_INSET = 10.dp
/** 顶栏（返回/书名/菜单）占掉状态栏以下约 58dp，悬浮球不许压上去。 */
private val TOP_LIMIT = 58.dp
private val BOTTOM_LIMIT = 96.dp

/**
 * 听书悬浮球：默认停在左上角，沉浸阅读（chrome 隐藏）时依然可见——正在播放的东西
 * 必须一直够得着，不能逼用户先点亮界面。
 *
 * 收起态是一颗封面小球（播放时带一圈呼吸光晕）；点一下向右展开成玻璃胶囊，
 * 露出「上一段 / 播放暂停 / 下一段」三键与更多；再点更多摊开第二行（章跳转、
 * 定时、全屏、结束）。可拖动，松手吸到最近的左右边缘，位置持久化。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderListenOrb(
    state: ListenState,
    sleepTimer: SleepTimerState?,
    palette: ReaderPalette,
    onOpenPlayer: () -> Unit,
    onToggle: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSleepTimer: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val orbSizePx = with(density) { ORB_SIZE.toPx() }
        val insetPx = with(density) { EDGE_INSET.toPx() }
        val statusBarPx = WindowInsets.statusBarsIgnoringVisibility.getTop(density).toFloat()
        val topLimitPx = statusBarPx + with(density) { TOP_LIMIT.toPx() }
        val maxXPx = (constraints.maxWidth - orbSizePx - insetPx).coerceAtLeast(insetPx)
        val maxYPx = (constraints.maxHeight - orbSizePx - with(density) { BOTTOM_LIMIT.toPx() })
            .coerceAtLeast(topLimitPx)

        // 默认左上：贴左边缘、状态栏之下。
        var savedX by rememberSaveable { mutableStateOf(-1f) }
        var savedY by rememberSaveable { mutableStateOf(-1f) }
        val offsetX = remember { Animatable(if (savedX >= 0f) savedX else insetPx) }
        val offsetY = remember { Animatable(if (savedY >= 0f) savedY else topLimitPx) }
        var expanded by rememberSaveable { mutableStateOf(false) }
        var showMore by rememberSaveable { mutableStateOf(false) }
        var dragging by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(maxXPx, maxYPx, topLimitPx) {
            offsetX.snapTo(offsetX.value.coerceIn(insetPx, maxXPx))
            offsetY.snapTo(offsetY.value.coerceIn(topLimitPx, maxYPx))
        }

        // 展开方向永远朝屏幕内侧：贴右边时胶囊向左长。
        val screenWidthPx = constraints.maxWidth.toFloat()
        val onLeftEdge = offsetX.value + orbSizePx / 2 < screenWidthPx / 2f
        val idleAlpha by animateFloatAsState(
            targetValue = if (expanded || dragging) 1f else 0.88f,
            animationSpec = tween(200),
            label = "listen-orb-alpha"
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
                .graphicsLayer { alpha = idleAlpha }
                .pointerInput(maxXPx, maxYPx, topLimitPx, screenWidthPx) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            dragging = false
                            val target = if (
                                offsetX.value + orbSizePx / 2 < screenWidthPx / 2f
                            ) insetPx else maxXPx
                            scope.launch {
                                offsetX.animateTo(target, spring(dampingRatio = 0.78f))
                                savedX = offsetX.value
                                savedY = offsetY.value
                            }
                        }
                    ) { change, drag ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo((offsetX.value + drag.x).coerceIn(insetPx, maxXPx))
                            offsetY.snapTo((offsetY.value + drag.y).coerceIn(topLimitPx, maxYPx))
                        }
                    }
                }
        ) {
            Surface(
                shape = RoundedCornerShape(ORB_SIZE / 2),
                color = if (expanded) palette.glassStrong else palette.glass,
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier
                    .shadow(14.dp, RoundedCornerShape(ORB_SIZE / 2), clip = false)
                    .widthIn(min = ORB_SIZE)
            ) {
                Column(
                    horizontalAlignment = if (onLeftEdge) {
                        Alignment.Start
                    } else {
                        Alignment.End
                    }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.padding(
                            horizontal = if (expanded) 5.dp else 0.dp,
                            vertical = 0.dp
                        )
                    ) {
                        if (!onLeftEdge) {
                            ListenOrbControls(
                                expanded = expanded,
                                showMore = showMore,
                                playing = state.isPlaying,
                                palette = palette,
                                onToggle = onToggle,
                                onPrevSentence = onPrevSentence,
                                onNextSentence = onNextSentence,
                                onMore = { showMore = !showMore }
                            )
                        }
                        ListenOrbCore(
                            coverPath = state.coverPath,
                            playing = state.isPlaying,
                            palette = palette,
                            onClick = {
                                if (expanded) {
                                    expanded = false
                                    showMore = false
                                } else {
                                    expanded = true
                                }
                            },
                            onDoubleClick = onOpenPlayer
                        )
                        if (onLeftEdge) {
                            ListenOrbControls(
                                expanded = expanded,
                                showMore = showMore,
                                playing = state.isPlaying,
                                palette = palette,
                                onToggle = onToggle,
                                onPrevSentence = onPrevSentence,
                                onNextSentence = onNextSentence,
                                onMore = { showMore = !showMore }
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded && showMore,
                        enter = fadeIn(tween(160)),
                        exit = fadeOut(tween(120))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.padding(
                                start = 6.dp,
                                end = 6.dp,
                                bottom = 5.dp
                            )
                        ) {
                            OrbIcon(Icons.Outlined.SkipPrevious, "上一章", palette, onPrevChapter)
                            OrbIcon(Icons.Outlined.SkipNext, "下一章", palette, onNextChapter)
                            OrbIcon(Icons.Outlined.OpenInFull, "全屏播放", palette, onOpenPlayer)
                            Surface(
                                onClick = onSleepTimer,
                                shape = CircleShape,
                                color = palette.accentContainer,
                                contentColor = palette.onBackground
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Bedtime,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = sleepTimer?.let(SleepTimerPlanner::label) ?: "定时",
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            OrbIcon(Icons.Outlined.Close, "结束听书", palette, onExit)
                        }
                    }
                }
            }
        }
    }
}

/** 展开后露出的三键 + 更多；收起时整段消失，胶囊缩回一颗球。 */
@Composable
private fun ListenOrbControls(
    expanded: Boolean,
    showMore: Boolean,
    playing: Boolean,
    palette: ReaderPalette,
    onToggle: () -> Unit,
    onPrevSentence: () -> Unit,
    onNextSentence: () -> Unit,
    onMore: () -> Unit
) {
    AnimatedVisibility(
        visible = expanded,
        enter = fadeIn(tween(180)) + expandHorizontally(tween(220)),
        exit = fadeOut(tween(120)) + shrinkHorizontally(tween(180))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            OrbIcon(Icons.Outlined.FastRewind, "上一段", palette, onPrevSentence)
            Surface(
                onClick = onToggle,
                shape = CircleShape,
                color = palette.accent,
                contentColor = palette.onAccent
            ) {
                Icon(
                    imageVector = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = if (playing) "暂停" else "继续",
                    modifier = Modifier.padding(6.dp).size(19.dp)
                )
            }
            OrbIcon(Icons.Outlined.FastForward, "下一段", palette, onNextSentence)
            OrbIcon(
                icon = if (showMore) Icons.Outlined.Close else Icons.Outlined.Headphones,
                description = if (showMore) "收起更多" else "更多听书操作",
                palette = palette,
                onClick = onMore
            )
        }
    }
}

/** 球心：封面 + 播放时的呼吸光圈。单击开合胶囊，双击直接进沉浸播放页。 */
@Composable
private fun ListenOrbCore(
    coverPath: String?,
    playing: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "listen-orb-pulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (playing) 1f else 0f,
        animationSpec = infiniteRepeatable(tween(1_900), repeatMode = RepeatMode.Reverse),
        label = "listen-orb-pulse-value"
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(ORB_SIZE)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() }, onDoubleTap = { onDoubleClick() })
            }
    ) {
        if (playing) {
            Canvas(Modifier.size(ORB_SIZE)) {
                drawCircle(
                    color = palette.accent.copy(alpha = 0.10f + 0.16f * pulse),
                    radius = size.minDimension / 2f * (0.86f + 0.14f * pulse)
                )
            }
        }
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!coverPath.isNullOrBlank() && File(coverPath).isFile) {
                AsyncImage(
                    model = File(coverPath),
                    contentDescription = "听书控制",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(32.dp)
                )
            } else {
                Surface(shape = CircleShape, color = palette.accentContainer) {
                    Icon(
                        imageVector = Icons.Outlined.Headphones,
                        contentDescription = "听书控制",
                        tint = palette.accent,
                        modifier = Modifier.padding(7.dp).size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun OrbIcon(
    icon: ImageVector,
    description: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent,
        contentColor = palette.onBackground
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            modifier = Modifier.padding(7.dp).size(18.dp)
        )
    }
}
