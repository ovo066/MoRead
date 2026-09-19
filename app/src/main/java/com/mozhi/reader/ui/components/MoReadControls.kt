package com.mozhi.reader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.ColorSchemePreset
import com.mozhi.reader.ui.theme.LocalMoReadColors
import com.mozhi.reader.ui.theme.MoReadSpacing
import com.mozhi.reader.ui.theme.fieldContainerColor
import com.mozhi.reader.ui.theme.isFlatSurface
import com.mozhi.reader.ui.theme.moReadMetrics
import com.mozhi.reader.ui.theme.onAccent
import com.mozhi.reader.ui.theme.sectionHairline

enum class MoReadButtonStyle { Filled, Tonal, Outlined }

/** 共用胶囊按钮。纯图标按钮仍以 text 提供完整的无障碍标签。 */
@Composable
fun MoReadButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: MoReadButtonStyle = MoReadButtonStyle.Filled,
    icon: ImageVector? = null,
    iconOnly: Boolean = false,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    iconSize: Dp? = null
) {
    val metrics = moReadMetrics()
    val buttonModifier = modifier.heightIn(min = metrics.buttonHeight).widthIn(min = metrics.buttonHeight)
    val padding = if (iconOnly) PaddingValues(0.dp) else PaddingValues(horizontal = MoReadSpacing.xl, vertical = MoReadSpacing.s)
    val content: @Composable RowScope.() -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = if (iconOnly) text else null,
                modifier = Modifier.size(iconSize ?: if (iconOnly) 28.dp else metrics.iconGlyph))
        }
        if (!iconOnly || icon == null) {
            Text(text, modifier = Modifier.padding(start = if (icon != null) MoReadSpacing.s else 0.dp))
        }
    }
    when (style) {
        MoReadButtonStyle.Filled -> Button(onClick, buttonModifier, enabled, shape = CircleShape,
            colors = colors ?: ButtonDefaults.buttonColors(), contentPadding = padding, content = content)
        MoReadButtonStyle.Tonal -> FilledTonalButton(onClick, buttonModifier, enabled, shape = CircleShape,
            colors = colors ?: ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer), contentPadding = padding, content = content)
        MoReadButtonStyle.Outlined -> OutlinedButton(onClick, buttonModifier, enabled, shape = CircleShape,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(), contentPadding = padding, content = content)
    }
}

/** 深色沉浸播放器：原版保留白色主键与透明侧键，彩色方案使用各自的实色/浅色按钮。 */
@Composable
fun immersivePlaybackColors(primary: Boolean): ButtonColors {
    val classic = LocalMoReadColors.current.colorScheme == ColorSchemePreset.NEUTRAL
    val c = MaterialTheme.colorScheme
    return ButtonDefaults.buttonColors(
        containerColor = if (classic) {
            if (primary) Color(0xFFF3F1EC) else Color.Transparent
        } else if (primary) c.primary else c.primaryContainer,
        contentColor = if (classic) {
            if (primary) Color(0xFF17171A) else Color(0xFFF3F1EC).copy(alpha = 0.86f)
        } else if (primary) c.onPrimary else c.onPrimaryContainer
    )
}

/**
 * 项目里唯一的分段选择器。
 *
 * 改造前同一件事有三种写法：一排 `FilterChip`（设置页外观、书架布局）、
 * `SingleChoiceSegmentedButtonRow`（TTS 引擎）、自绘 `SegChip`（阅读页排版面板）。
 * 三者的圆角、高度、选中态配色都不一样，切页时观感会「跳」。
 *
 * 这里统一为：一条填充底胶囊里等宽分段，选中段填强调色。
 */
@Composable
fun <T> MoReadSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: (T) -> String
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup()
            .clip(moReadMetrics().fieldShape)
            .background(fieldContainerColor())
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEach { option ->
            MoReadSegment(
                text = label(option),
                selected = option == selected,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun MoReadSegment(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    // 扁平质感下选中段用淡底 + 深字（MD3 tonal），玻璃质感下维持实色强调段。
    val flat = isFlatSurface()
    val selectedContainer = if (flat) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.primary
    }
    val selectedContent = if (flat) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.primary.onAccent()
    }
    val container by animateColorAsState(
        targetValue = if (selected) selectedContainer else Color.Transparent,
        animationSpec = tween(160),
        label = "segment-container"
    )
    val content by animateColorAsState(
        targetValue = if (selected) selectedContent else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(160),
        label = "segment-content"
    )
    val metrics = moReadMetrics()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(metrics.radiusField - 3.dp))
            .background(container)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = metrics.touchTarget - 6.dp)
            .padding(vertical = metrics.segmentedVerticalPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = if (enabled) content else content.copy(alpha = 0.38f),
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 设置页的数值滑条：一条胶囊里左 `−`、右 `＋`、中间可拖的圆钮，右侧显示当前值。
 *
 * 与阅读页 `TypographyStepper` 是同一形态的两套配色（那边吃 `ReaderPalette`，因为纸色
 * 独立于应用明暗）。取值规则共用 `feature/reader` 里那三个纯函数的同款算法，见
 * [quantizeSliderValue] / [sliderFraction] / [steppedSliderValue]。
 */
@Composable
fun MoReadSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val fraction = sliderFraction(value, range)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.width(72.dp)
        )
        Row(
            modifier = Modifier
                .weight(1f)
                .height(moReadMetrics().sliderHeight)
                .clip(CircleShape)
                .background(fieldContainerColor())
                .border(0.5.dp, sectionHairline(), CircleShape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SliderNudge(
                icon = Icons.Outlined.Remove,
                description = "$label 减小",
                enabled = value > range.start
            ) { onValueChange(steppedSliderValue(value, range, step, -1)) }
            SliderTrack(
                fraction = fraction,
                modifier = Modifier.weight(1f),
                onFraction = { onValueChange(quantizeSliderValue(it, range, step)) }
            )
            SliderNudge(
                icon = Icons.Outlined.Add,
                description = "$label 增大",
                enabled = value < range.endInclusive
            ) { onValueChange(steppedSliderValue(value, range, step, 1)) }
        }
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(58.dp)
        )
    }
}

@Composable
private fun SliderNudge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = moReadMetrics().sliderHeight)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun SliderTrack(
    fraction: Float,
    modifier: Modifier = Modifier,
    onFraction: (Float) -> Unit
) {
    val thumbDiameter = 18.dp
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val thumbRing = sectionHairline()
    Box(
        modifier = modifier
            .height(moReadMetrics().sliderHeight)
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
        Canvas(modifier = Modifier.height(moReadMetrics().sliderHeight).fillMaxWidth()) {
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
                color = thumbRing,
                radius = radius,
                center = Offset(centerX, centerY),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
        }
    }
}

/** 胶囊标签：只读的状态标记（「伴读中」「已连接」），不可点。 */
@Composable
fun MoReadPill(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = modifier
            .clip(CircleShape)
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** 尾部动作按钮：设置行右侧的「清理」「导入」这类次要动作，比 OutlinedButton 安静。 */
@Composable
fun RowScope.MoReadRowAction(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = tint,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

// ---- 取值规则：纯函数，与阅读页排版滑条同一套算法，可单测 ----

/** 当前值 → 0..1 的轨道比例。 */
internal fun sliderFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    return ((value - range.start) / span).coerceIn(0f, 1f)
}

/** 拖动落点（0..1）→ 对齐步长网格并钳制在区间内。 */
internal fun quantizeSliderValue(
    fraction: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float
): Float {
    val span = range.endInclusive - range.start
    return snap(range.start + span * fraction.coerceIn(0f, 1f), range, step)
}

/** 点 −/＋ 后的取值：先按方向落到网格再走一格，避免历史值不在网格上时一次跳两格。 */
internal fun steppedSliderValue(
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    direction: Int
): Float {
    if (step <= 0f || direction == 0) return current.coerceIn(range.start, range.endInclusive)
    val units = (current - range.start) / step
    val anchor = if (direction > 0) {
        kotlin.math.floor(units + STEP_EPSILON)
    } else {
        kotlin.math.ceil(units - STEP_EPSILON)
    }
    return snap(range.start + (anchor + direction) * step, range, step)
}

private fun snap(raw: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    if (step <= 0f) return raw.coerceIn(range.start, range.endInclusive)
    val units = (raw - range.start) / step
    return (range.start + Math.round(units).toFloat() * step)
        .coerceIn(range.start, range.endInclusive)
}

private const val STEP_EPSILON = 1e-4f
