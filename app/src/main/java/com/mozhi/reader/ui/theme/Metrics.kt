package com.mozhi.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 随 [ShapeStyle] 变化的形状与密度刻度。[MoReadRadius] / [MoReadTokens] 里的常量是「标准」档，
 * 组件层一律改读 [moReadMetrics]，这样切到「舒展」时圆角、行高、控件高一起放大。
 */
data class MoReadMetrics(
    val radiusField: Dp,
    val radiusRow: Dp,
    val radiusCard: Dp,
    val radiusSheet: Dp,
    val rowMinHeight: Dp,
    val iconTile: Dp,
    val iconGlyph: Dp,
    val segmentedVerticalPadding: Dp,
    val sliderHeight: Dp,
    val topBarHeight: Dp,
    val touchTarget: Dp,
    val buttonHeight: Dp
) {
    val fieldShape: RoundedCornerShape get() = RoundedCornerShape(radiusField)
    val rowShape: RoundedCornerShape get() = RoundedCornerShape(radiusRow)
    val cardShape: RoundedCornerShape get() = RoundedCornerShape(radiusCard)
    val sheetShape: RoundedCornerShape get() = RoundedCornerShape(radiusSheet)

    /**
     * 标准档保留已有圆角；舒展档收敛到刻度：≤14 → field，15~18 → row，19~24 → card，其余 → sheet。
     */
    fun radiusFor(literalDp: Int): Dp = when {
        this == Standard -> literalDp.coerceAtLeast(0).dp
        literalDp <= 14 -> radiusField
        literalDp <= 18 -> radiusRow
        literalDp <= 24 -> radiusCard
        else -> radiusSheet
    }

    companion object {
        val Standard = MoReadMetrics(
            radiusField = MoReadRadius.field,
            radiusRow = MoReadRadius.row,
            radiusCard = MoReadRadius.card,
            radiusSheet = MoReadRadius.sheet,
            rowMinHeight = MoReadTokens.RowMinHeight,
            iconTile = MoReadTokens.IconTile,
            iconGlyph = MoReadTokens.IconGlyph,
            segmentedVerticalPadding = 9.dp,
            sliderHeight = 38.dp,
            topBarHeight = 52.dp,
            touchTarget = 44.dp,
            buttonHeight = 44.dp
        )

        val Expressive = MoReadMetrics(
            radiusField = 18.dp,
            radiusRow = 22.dp,
            radiusCard = 28.dp,
            radiusSheet = 36.dp,
            rowMinHeight = 60.dp,
            iconTile = 40.dp,
            iconGlyph = 20.dp,
            segmentedVerticalPadding = 12.dp,
            sliderHeight = 44.dp,
            topBarHeight = 56.dp,
            touchTarget = 48.dp,
            buttonHeight = 52.dp
        )

        fun of(style: ShapeStyle): MoReadMetrics = when (style) {
            ShapeStyle.STANDARD -> Standard
            ShapeStyle.EXPRESSIVE -> Expressive
        }
    }
}

val LocalMoReadMetrics = staticCompositionLocalOf { MoReadMetrics.Standard }

@Composable
@ReadOnlyComposable
fun moReadMetrics(): MoReadMetrics = LocalMoReadMetrics.current
