package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 排版数值控件的取值规则。
 *
 * 规则全部留在纯函数里（项目纪律：规则不塞进 Composable），Composable 只负责把手势换算成
 * 比例、把比例交回这里量化。这样「按一下 ＋ 到底走多远、两端会不会越界」可以被单测钉死。
 */
internal fun typographyFraction(
    value: Float,
    range: ClosedFloatingPointRange<Float>
): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** 拖动落点（0..1）→ 对齐到步长网格并钳制在区间内的取值。 */
internal fun typographyValueAt(
    fraction: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Float {
    val span = range.endInclusive - range.start
    val raw = range.start + span * fraction.coerceIn(0f, 1f)
    return snapTypographyValue(raw, range, step)
}

/**
 * 点 `−` / `＋` 后的取值。[direction] 取 -1 或 +1。
 *
 * 先按方向把当前值落到网格上再走一格：当前值可能来自历史设置、并不在网格点上
 * （比如 0.13 而步长 0.05），直接四舍五入再加一步会一次跳两格。
 */
internal fun steppedTypographyValue(
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    direction: Int
): Float {
    if (step <= 0f || direction == 0) {
        return current.coerceIn(range.start, range.endInclusive)
    }
    val units = (current - range.start) / step
    val anchor = if (direction > 0) {
        floor(units + STEP_EPSILON)
    } else {
        ceil(units - STEP_EPSILON)
    }
    val target = range.start + (anchor + direction) * step
    return snapTypographyValue(target, range, step)
}

private fun snapTypographyValue(
    raw: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val units = ((raw - range.start) / step)
    val snapped = range.start + Math.round(units).toFloat() * step
    return snapped.coerceIn(range.start, range.endInclusive)
}

private const val STEP_EPSILON = 1e-4f

/**
 * 亮度滑条的取值规则。
 *
 * 存储里 -1 表示「跟随系统」，但滑条必须给它一个位置，否则拖柄会跳到最左端、
 * 看起来像「亮度已被调到 0」。约定：跟随系统时拖柄停在 [FOLLOW_SYSTEM_ANCHOR]，
 * 一旦用户拖动就变成显式值。
 */
internal fun readerBrightnessFraction(stored: Float): Float =
    if (stored < 0f) FOLLOW_SYSTEM_ANCHOR else stored.coerceIn(0f, 1f)

/** 滑条落点 → 落库值。步长 5%，与参考产品的手感一致（够细但不会滑不准）。 */
internal fun readerBrightnessValueAt(fraction: Float): Float =
    (Math.round(fraction.coerceIn(0f, 1f) / BRIGHTNESS_STEP).toFloat() * BRIGHTNESS_STEP)
        // 不允许调到 0：Android 的 screenBrightness=0 在不少机型上接近全黑，而面板本身也画在
        // 这块屏幕上——用户会看不见滑条，只能杀进程才能救回来。留一个能看清界面的下限。
        .coerceIn(MIN_BRIGHTNESS, 1f)

/** 右侧数值文案：跟随系统时不报百分比，报「自动」——报数字会让人以为已经手动定死了。 */
internal fun readerBrightnessLabel(stored: Float): String =
    if (stored < 0f) "自动" else "${Math.round(stored.coerceIn(0f, 1f) * 100)}%"

internal const val FOLLOW_SYSTEM_ANCHOR = 0.5f

/** 手动亮度的下限：再暗下去面板自己也看不见了。 */
internal const val MIN_BRIGHTNESS = 0.05f
private const val BRIGHTNESS_STEP = 0.05f

/**
 * 胶囊步进器：一条胶囊里左 `−`、右 `＋`、中间可拖的圆钮，右侧显示当前值。
 *
 * 取代原来的 Material `Slider`：滑条又高又只能拖，微调一格全靠手准；胶囊把「点一下走一格」
 * 和「拖着快调」放进同一个控件，行高也压到 36dp，一张悬浮卡片里能塞下一整类排版项。
 */
@Composable
internal fun TypographyStepper(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    palette: ReaderPalette,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = typographyFraction(value, range)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = palette.onBackground,
            maxLines = 1,
            modifier = Modifier.width(68.dp)
        )
        Surface(
            shape = CircleShape,
            color = palette.glass,
            contentColor = palette.onBackground,
            border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier
                .weight(1f)
                .height(36.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StepperNudge(
                    icon = Icons.Outlined.Remove,
                    description = "$label 减小",
                    palette = palette,
                    enabled = value > range.start
                ) { onValueChange(steppedTypographyValue(value, range, step, -1)) }

                StepperTrack(
                    fraction = fraction,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onFraction = { onValueChange(typographyValueAt(it, range, step)) }
                )

                StepperNudge(
                    icon = Icons.Outlined.Add,
                    description = "$label 增大",
                    palette = palette,
                    enabled = value < range.endInclusive
                ) { onValueChange(steppedTypographyValue(value, range, step, 1)) }
            }
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(58.dp)
        )
    }
}

@Composable
private fun StepperNudge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    palette: ReaderPalette,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) palette.onBackground else palette.muted.copy(alpha = 0.4f),
            modifier = Modifier.size(15.dp)
        )
    }
}

/** 轨道：细线 + 圆钮。圆钮直径占满行高的一半多一点，指腹按得住又不显笨重。 */
@Composable
private fun StepperTrack(
    fraction: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onFraction: (Float) -> Unit
) {
    val thumbDiameter = 18.dp
    Box(
        modifier = modifier
            .height(36.dp)
            .pointerInput(Unit) {
                val travel = (size.width - thumbDiameter.toPx()).coerceAtLeast(1f)
                val inset = thumbDiameter.toPx() / 2f
                detectTapGestures { offset ->
                    onFraction(((offset.x - inset) / travel).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                val travel = (size.width - thumbDiameter.toPx()).coerceAtLeast(1f)
                val inset = thumbDiameter.toPx() / 2f
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onFraction(((change.position.x - inset) / travel).coerceIn(0f, 1f))
                }
            }
    ) {
        val accent = palette.accent
        val rail = palette.muted.copy(alpha = 0.22f)
        val thumbBorder = palette.glassBorder
        Canvas(modifier = Modifier.padding(horizontal = 1.dp).height(36.dp).fillMaxWidth()) {
            val diameter = thumbDiameter.toPx()
            val radius = diameter / 2f
            val travel = (size.width - diameter).coerceAtLeast(1f)
            val centerY = size.height / 2f
            val centerX = radius + travel * fraction.coerceIn(0f, 1f)
            drawLine(
                color = rail,
                start = Offset(radius, centerY),
                end = Offset(size.width - radius, centerY),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
            if (centerX > radius) {
                drawLine(
                    color = accent,
                    start = Offset(radius, centerY),
                    end = Offset(centerX, centerY),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }
            drawCircle(color = accent, radius = radius, center = Offset(centerX, centerY))
            drawCircle(
                color = if (thumbBorder == Color.Transparent) accent else thumbBorder,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1.dp.toPx())
            )
        }
    }
}
