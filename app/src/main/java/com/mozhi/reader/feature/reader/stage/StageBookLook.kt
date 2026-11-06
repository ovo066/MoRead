package com.mozhi.reader.feature.reader.stage

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.stage.StageBox
import com.mozhi.reader.core.stage.StageImage
import com.mozhi.reader.core.stage.StageInk
import com.mozhi.reader.feature.reader.engine.EpubThemeColors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 书内嵌字体：按文件路径缓存，翻章不重复加载。 */
internal object StageTypefaces {
    private val cache = HashMap<String, FontFamily?>()

    fun load(path: String): FontFamily? = synchronized(cache) {
        cache.getOrPut(path) { runCatching { FontFamily(Typeface.createFromFile(File(path))) }.getOrNull() }
    }
}

/** 一章用到的书内嵌字体（族名小写 → 字体）；在后台线程加载。 */
@Composable
internal fun rememberBookFonts(paths: Map<String, String>): Map<String, FontFamily> {
    val fonts by produceState(emptyMap<String, FontFamily>(), paths) {
        value = withContext(Dispatchers.IO) {
            buildMap { for ((family, path) in paths) StageTypefaces.load(path)?.let { put(family.lowercase(), it) } }
        }
    }
    return fonts
}

/** 书里定的字色换到当前底色上：保留色相，对比度不够时提亮或压暗。 */
internal fun bookInk(argb: Int, background: Color, palette: StagePalette): Color =
    Color(EpubThemeColors.foreground(argb or (0xFF shl 24), background.copy(alpha = 1f).toArgb(), palette.ink.toArgb()))

/** 书里定的底色：暗色舞台把浅底压暗、保留色相。 */
internal fun bookFill(argb: Int, palette: StagePalette): Color = Color(EpubThemeColors.background(argb, palette.isDark) ?: argb)

/** 在原有样式上叠书里定的字体、颜色、粗斜与字号（字号只在 0.85–1.3 倍之间跟）。 */
internal fun TextStyle.withInk(ink: StageInk?, fonts: StageFonts, palette: StagePalette, background: Color = palette.background): TextStyle {
    if (ink == null) return this
    var s = this
    ink.fontFamily?.let { fonts.book[it] }?.let { s = s.copy(fontFamily = it) }
    ink.colorArgb?.let { s = s.copy(color = bookInk(it, background, palette)) }
    if (ink.bold) s = s.copy(fontWeight = FontWeight.Bold)
    if (ink.italic) s = s.copy(fontStyle = FontStyle.Italic)
    val size = ink.sizeEm
    if (size != null && s.fontSize.isSp) s = s.copy(fontSize = (s.fontSize.value * size.coerceIn(0.85f, 1.3f)).sp)
    return s
}

/** 书里画的框：底色、边框（虚线照旧）、圆角与平铺的底纹图。 */
@Composable
internal fun Modifier.stageBookBox(box: StageBox, palette: StagePalette): Modifier {
    val texture = box.backgroundImage?.let { rememberStageImage(StageImage(it), 512) }
    val fill = box.backgroundArgb?.let { bookFill(it, palette) }
    val edge = box.borderArgb?.let { bookInk(it, palette.background, palette).copy(alpha = 0.85f) }
    val radius = box.radiusPx.coerceIn(0f, 22f).dp
    val shape = RoundedCornerShape(radius)
    val width = box.borderWidthPx.coerceIn(0.6f, 3f).dp
    return this
        .clip(shape)
        .then(if (fill != null) Modifier.background(fill, shape) else Modifier)
        .then(
            if (texture != null) Modifier.drawBehind {
                drawRect(ShaderBrush(ImageShader(texture, TileMode.Repeated, TileMode.Repeated)), alpha = if (palette.isDark) 0.35f else 0.6f)
            } else Modifier
        )
        .then(
            when {
                edge == null || box.borderWidthPx <= 0f -> Modifier
                box.dashed -> Modifier.drawBehind {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    drawOutline(outline, edge, style = Stroke(width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
                }
                else -> Modifier.border(width, edge, shape)
            }
        )
        .padding(horizontal = 14.dp, vertical = 11.dp)
}
