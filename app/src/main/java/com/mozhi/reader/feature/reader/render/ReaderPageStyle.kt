package com.mozhi.reader.feature.reader.render

import android.graphics.Typeface
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.feature.reader.ReaderPalette
import com.mozhi.reader.feature.reader.engine.AndroidTextMeasure
import com.mozhi.reader.feature.reader.engine.TypesetSpec

/**
 * All pixel-resolved values one page render needs. Built once per (settings, palette, viewport)
 * combination; the typesetting spec and the paint style always come from the same instance so the
 * engine and the painter can never disagree about geometry.
 */
class ReaderPageStyle(
    val viewWidth: Int,
    val viewHeight: Int,
    val paddingHorizontal: Float,
    val headerHeight: Float,
    val footerHeight: Float,
    val contentSizePx: Float,
    val titleSizePx: Float,
    val tipSizePx: Float,
    val lineStep: Float,
    val backgroundColor: Int,
    val textColor: Int,
    val mutedColor: Int,
    val accentColor: Int,
    val isDark: Boolean,
    /** Subtle paper grain, only for the 纸张 theme per the approved visual proposal. */
    val grain: Boolean,
    val typeface: Typeface
) {
    val contentWidth: Float = (viewWidth - paddingHorizontal * 2).coerceAtLeast(1f)
    val contentHeight: Float = (viewHeight - headerHeight - footerHeight).coerceAtLeast(1f)

    val spec: TypesetSpec = TypesetSpec(
        visibleWidth = contentWidth,
        visibleHeight = contentHeight,
        contentLineStep = lineStep,
        titleLineStep = titleSizePx * TITLE_LINE_HEIGHT,
        paragraphSpacing = contentSizePx * PARAGRAPH_SPACING_EM,
        titleTopSpacing = lineStep * TITLE_TOP_LINES,
        titleBottomSpacing = lineStep * TITLE_BOTTOM_LINES
    )

    val measure: AndroidTextMeasure = AndroidTextMeasure(
        contentSizePx = contentSizePx,
        titleSizePx = titleSizePx,
        typeface = typeface
    )

    companion object {
        const val BASE_CONTENT_SP = 17f
        const val TITLE_SCALE = 1.35f
        const val TITLE_LINE_HEIGHT = 1.4f
        const val PARAGRAPH_SPACING_EM = 0.55f
        const val TITLE_TOP_LINES = 0.4f
        const val TITLE_BOTTOM_LINES = 1f
        const val TIP_SP = 11f
        const val HEADER_PADDING_DP = 10f
        const val FOOTER_PADDING_DP = 12f
        /** pageMargin setting 0..2 maps to 14..34dp of horizontal padding. */
        const val MARGIN_BASE_DP = 14f
        const val MARGIN_RANGE_DP = 10f

        fun resolve(
            settings: ReaderSettings,
            palette: ReaderPalette,
            density: Density,
            viewWidth: Int,
            viewHeight: Int,
            statusBarPx: Float,
            navigationBarPx: Float
        ): ReaderPageStyle {
            val contentSize = with(density) { (BASE_CONTENT_SP * settings.fontScale).sp.toPx() }
            val tipSize = with(density) { TIP_SP.sp.toPx() }
            val headerPadding = with(density) { HEADER_PADDING_DP.dp.toPx() }
            val footerPadding = with(density) { FOOTER_PADDING_DP.dp.toPx() }
            val horizontal = with(density) {
                (MARGIN_BASE_DP + MARGIN_RANGE_DP * settings.pageMargin).dp.toPx()
            }
            return ReaderPageStyle(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                paddingHorizontal = horizontal,
                headerHeight = statusBarPx + headerPadding + tipSize * 1.6f,
                footerHeight = navigationBarPx.coerceAtLeast(footerPadding) + tipSize * 1.9f,
                contentSizePx = contentSize,
                titleSizePx = contentSize * TITLE_SCALE,
                tipSizePx = tipSize,
                lineStep = contentSize * settings.lineHeight,
                backgroundColor = palette.background.toArgb(),
                textColor = palette.onBackground.toArgb(),
                mutedColor = palette.muted.toArgb(),
                accentColor = palette.accent.toArgb(),
                isDark = palette.isDark,
                // 纸纹只属于内置「纸张」主题；自定义配色下不叠加。
                grain = settings.activeCustomThemeId == null &&
                    settings.theme == com.mozhi.reader.core.datastore.ReaderTheme.PAPER,
                typeface = settings.font.toTypeface()
            )
        }

        private fun ReaderFont.toTypeface(): Typeface = when (this) {
            ReaderFont.SYSTEM -> Typeface.DEFAULT
            ReaderFont.SERIF -> Typeface.SERIF
            ReaderFont.SANS_SERIF -> Typeface.SANS_SERIF
            ReaderFont.MONOSPACE -> Typeface.MONOSPACE
        }
    }
}

private val Float.sp get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
private val Float.dp get() = androidx.compose.ui.unit.Dp(this)
