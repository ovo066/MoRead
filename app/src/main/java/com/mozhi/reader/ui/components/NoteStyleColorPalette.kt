package com.mozhi.reader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.theme.onAccent
import java.util.Locale

/**
 * 绘图/笔记软件式连续取色器：二维区选饱和度与明度，色相条选色系。
 * 常用色只是快捷入口，不限制用户可选范围；十六进制值用于精确复用。
 */
@Composable
fun NoteStyleColorPalette(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val initial = remember { color.toHsv() }
    var hue by remember { mutableFloatStateOf(initial[0]) }
    var saturation by remember { mutableFloatStateOf(initial[1]) }
    var value by remember { mutableFloatStateOf(initial[2]) }
    var hex by remember { mutableStateOf(color.toHexRgb()) }
    var hexError by remember { mutableStateOf(false) }

    LaunchedEffect(color.toArgb()) {
        val hsv = color.toHsv()
        // 灰色没有有意义的 hue，保留用户当前色相，避免取色指示器跳回红色。
        if (hsv[1] > 0.001f) hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        val resolved = color.toHexRgb()
        if (!hex.equals(resolved, ignoreCase = true)) hex = resolved
        hexError = false
    }

    fun update(h: Float = hue, s: Float = saturation, v: Float = value) {
        hue = h.coerceIn(0f, 360f)
        saturation = s.coerceIn(0f, 1f)
        value = v.coerceIn(0f, 1f)
        onColorChange(hsvColor(hue, saturation, value))
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(color, RoundedCornerShape(14.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "当前颜色",
                style = MaterialTheme.typography.labelMedium,
                color = color.onAccent(),
                modifier = Modifier.weight(1f)
            )
            Text(color.toHexRgb(), style = MaterialTheme.typography.labelLarge, color = color.onAccent())
        }

        SaturationValueField(
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v -> update(s = s, v = v) }
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("色相", style = MaterialTheme.typography.labelMedium)
            HueField(hue = hue, onChange = { update(h = it) })
        }

        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("常用颜色", style = MaterialTheme.typography.labelMedium)
            QUICK_COLORS.chunked(QUICK_COLUMNS).forEach { colors ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colors.forEach { swatch ->
                        ColorSwatch(
                            color = swatch,
                            selected = swatch.toArgb() == color.toArgb(),
                            onClick = { onColorChange(swatch) }
                        )
                    }
                }
            }
        }

        OutlinedTextField(
            value = hex,
            onValueChange = { raw ->
                val normalized = raw.trim().removePrefix("#")
                    .filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                    .take(6)
                    .uppercase(Locale.ROOT)
                hex = "#$normalized"
                val parsed = normalized.takeIf { it.length == 6 }?.toLongOrNull(16)
                hexError = normalized.length == 6 && parsed == null
                if (parsed != null) onColorChange(Color((0xFF000000L or parsed).toInt()))
            },
            label = { Text("十六进制颜色") },
            supportingText = { Text(if (hexError) "请输入 6 位十六进制颜色" else "例如 #7A5AF8") },
            isError = hexError,
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Characters,
                keyboardType = KeyboardType.Ascii
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (saturation: Float, value: Float) -> Unit
) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnChange by rememberUpdatedState(onChange)
    val gesture = Modifier.pointerInput(fieldSize) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun select(position: Offset) {
                if (fieldSize.width <= 0 || fieldSize.height <= 0) return
                currentOnChange(
                    (position.x / fieldSize.width).coerceIn(0f, 1f),
                    (1f - position.y / fieldSize.height).coerceIn(0f, 1f)
                )
            }
            select(down.position)
            drag(down.id) { change ->
                select(change.position)
                change.consume()
            }
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.7f)
            .background(Color.Black, RoundedCornerShape(14.dp))
            .onSizeChanged { fieldSize = it }
            .then(gesture)
    ) {
        drawRoundRect(
            brush = Brush.horizontalGradient(listOf(Color.White, hsvColor(hue, 1f, 1f))),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
        )
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx())
        )
        val center = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.45f), 10.dp.toPx(), center)
        drawCircle(Color.White, 8.dp.toPx(), center, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun HueField(hue: Float, onChange: (Float) -> Unit) {
    var fieldSize by remember { mutableStateOf(IntSize.Zero) }
    val currentOnChange by rememberUpdatedState(onChange)
    val gesture = Modifier.pointerInput(fieldSize) {
        awaitEachGesture {
            val down = awaitFirstDown()
            fun select(x: Float) {
                if (fieldSize.width > 0) {
                    currentOnChange((x / fieldSize.width * 360f).coerceIn(0f, 360f))
                }
            }
            select(down.position.x)
            drag(down.id) { change ->
                select(change.position.x)
                change.consume()
            }
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { fieldSize = it }
            .then(gesture)
    ) {
        val y = size.height / 2f
        drawLine(
            brush = Brush.horizontalGradient(HUE_COLORS),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 10.dp.toPx(),
            cap = StrokeCap.Round
        )
        val x = (hue / 360f).coerceIn(0f, 1f) * size.width
        drawCircle(Color.Black.copy(alpha = 0.35f), 9.dp.toPx(), Offset(x, y))
        drawCircle(Color.White, 7.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(31.dp)
            .background(color, CircleShape)
            .border(
                if (selected) 2.5.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "已选择 ${color.toHexRgb()}",
                tint = color.onAccent(),
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

private fun Color.toHsv(): FloatArray = FloatArray(3).also {
    android.graphics.Color.colorToHSV(toArgb(), it)
}

private fun hsvColor(hue: Float, saturation: Float, value: Float): Color = Color(
    android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
)

private fun Color.toHexRgb(): String = String.format(
    Locale.ROOT,
    "#%06X",
    toArgb() and 0x00FFFFFF
)

private const val QUICK_COLUMNS = 7

private val HUE_COLORS = listOf(
    hsvColor(0f, 1f, 1f),
    hsvColor(60f, 1f, 1f),
    hsvColor(120f, 1f, 1f),
    hsvColor(180f, 1f, 1f),
    hsvColor(240f, 1f, 1f),
    hsvColor(300f, 1f, 1f),
    hsvColor(360f, 1f, 1f)
)

private val QUICK_COLORS = listOf(
    Color(0xFF161616), Color(0xFF737373), Color(0xFFD4D4D4), Color(0xFFFFFFFF),
    Color(0xFFDC2626), Color(0xFFF97316), Color(0xFFFACC15),
    Color(0xFF16A34A), Color(0xFF14B8A6), Color(0xFF0284C7), Color(0xFF2563EB),
    Color(0xFF7C3AED), Color(0xFFC026D3), Color(0xFFDB2777)
)
