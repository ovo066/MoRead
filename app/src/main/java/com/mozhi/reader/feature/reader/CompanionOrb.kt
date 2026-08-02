package com.mozhi.reader.feature.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 伴读悬浮球（demo `.orb`）：当前伴读角色的头像（自定义图或渐变单字），无玻璃底、无描边。
 * 贴边半隐：收起时球心越过屏幕边缘（露出约 40%、透明度 0.62）；点击弹出到
 * 距边 18dp 的完整位置，再点打开对话；4s 无交互或翻页自动缩回。
 * 拖拽自由移动，松手 spring 吸到最近的屏幕边缘；位置经 [rememberSaveable] 持久化。
 */
@Composable
fun DraggableCompanionOrb(
    persona: PersonaEntity?,
    palette: ReaderPalette,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSignal: Int = 0
) {
    if (!visible) return
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val orbSizePx = with(density) { ORB_SIZE.toPx() }
        val popInsetPx = with(density) { POP_INSET.toPx() }
        val topLimitPx = with(density) { TOP_LIMIT.toPx() }
        val bottomLimitPx = with(density) { BOTTOM_LIMIT.toPx() }
        // 吸边目标就是屏幕边缘本身，半隐/弹出靠 collapseShift 偏移。
        val maxXPx = (constraints.maxWidth - orbSizePx).coerceAtLeast(0f)
        val maxYPx = constraints.maxHeight - orbSizePx - bottomLimitPx

        var savedX by rememberSaveable { mutableStateOf(-1f) }
        var savedY by rememberSaveable { mutableStateOf(-1f) }
        val offsetX = remember { Animatable(if (savedX >= 0f) savedX else maxXPx) }
        val offsetY = remember {
            Animatable(if (savedY >= 0f) savedY else maxYPx - orbSizePx * 1.6f)
        }
        var dragging by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }
        var expandToken by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(maxXPx, maxYPx) {
            offsetX.snapTo(offsetX.value.coerceIn(0f, maxXPx))
            offsetY.snapTo(offsetY.value.coerceIn(topLimitPx, maxYPx.coerceAtLeast(topLimitPx)))
        }

        // 翻页等外部交互 → 立即缩回。
        LaunchedEffect(interactionSignal) {
            if (interactionSignal > 0) expanded = false
        }

        // 弹出后一段时间无交互自动缩回。
        LaunchedEffect(expanded, expandToken) {
            if (expanded) {
                delay(AUTO_COLLAPSE_MS)
                expanded = false
            }
        }

        val onLeftEdge = offsetX.value + orbSizePx / 2 < constraints.maxWidth / 2f
        val collapsed = !expanded && !dragging
        val collapseShift by animateFloatAsState(
            targetValue = when {
                dragging -> 0f
                collapsed -> {
                    // 收起：球体 60% 越出屏幕。
                    val out = orbSizePx * COLLAPSE_FRACTION
                    if (onLeftEdge) -out else out
                }
                else -> {
                    // 弹出：完整露出，距边 18dp。
                    if (onLeftEdge) popInsetPx else -popInsetPx
                }
            },
            animationSpec = if (collapsed) tween(300) else spring(dampingRatio = 0.6f),
            label = "orb-collapse-shift"
        )
        val orbAlpha by animateFloatAsState(
            targetValue = if (collapsed) COLLAPSED_ALPHA else 1f,
            animationSpec = tween(220),
            label = "orb-alpha"
        )

        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (offsetX.value + collapseShift).roundToInt(),
                        offsetY.value.roundToInt()
                    )
                }
                .size(ORB_SIZE)
                .graphicsLayer { alpha = orbAlpha }
                .clip(CircleShape)
                .pointerInput(maxXPx, maxYPx) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                offsetX.snapTo((offsetX.value + dragAmount.x).coerceIn(0f, maxXPx))
                                offsetY.snapTo(
                                    (offsetY.value + dragAmount.y)
                                        .coerceIn(topLimitPx, maxYPx.coerceAtLeast(topLimitPx))
                                )
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                val targetX = if (offsetX.value + orbSizePx / 2 <
                                    constraints.maxWidth / 2f
                                ) {
                                    0f
                                } else {
                                    maxXPx
                                }
                                offsetX.animateTo(targetX, spring(dampingRatio = 0.78f))
                                savedX = offsetX.value
                                savedY = offsetY.value
                                dragging = false
                                expanded = false
                            }
                        },
                        onDragCancel = {
                            dragging = false
                            expanded = false
                        }
                    )
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    if (expanded) {
                        onClick()
                    } else {
                        expanded = true
                        expandToken++
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            PersonaAvatarImage(
                name = persona?.name ?: "伴",
                avatarPath = persona?.avatarPath,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val ORB_SIZE = 46.dp
private val POP_INSET = 18.dp
private val TOP_LIMIT = 72.dp
private val BOTTOM_LIMIT = 48.dp
private const val COLLAPSE_FRACTION = 0.6f
private const val COLLAPSED_ALPHA = 0.62f
private const val AUTO_COLLAPSE_MS = 4_000L
