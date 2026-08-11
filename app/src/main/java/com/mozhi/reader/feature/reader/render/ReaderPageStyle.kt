package com.mozhi.reader.feature.reader.render

import android.graphics.Typeface
import android.os.Build
import java.io.File
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
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
    val typeface: Typeface,
    val customFontPath: String?,
    val customFontPaths: Map<String, String>,
    val showHeader: Boolean,
    val showFooter: Boolean,
    val backgroundImagePath: String?,
    val backgroundImageOpacity: Float,
    val syntaxHighlightRules: List<ReaderSyntaxRule>,
    val paragraphSpacingEm: Float,
    val firstLineIndentEm: Float,
    val textJustification: Boolean,
    val letterSpacingEm: Float
) {
    val contentWidth: Float = (viewWidth - paddingHorizontal * 2).coerceAtLeast(1f)
    val contentHeight: Float = (viewHeight - headerHeight - footerHeight).coerceAtLeast(1f)

    val spec: TypesetSpec = TypesetSpec(
        visibleWidth = contentWidth,
        visibleHeight = contentHeight,
        contentLineStep = lineStep,
        titleLineStep = titleSizePx * TITLE_LINE_HEIGHT,
        paragraphSpacing = contentSizePx * paragraphSpacingEm,
        blankLineSpacing = contentSizePx * paragraphSpacingEm * BLANK_LINE_FACTOR,
        titleTopSpacing = lineStep * TITLE_TOP_LINES,
        titleBottomSpacing = lineStep * TITLE_BOTTOM_LINES,
        syntaxHighlightRules = syntaxHighlightRules,
        indentCharCount = firstLineIndentEm,
        justifyContent = textJustification
    )

    val measure: AndroidTextMeasure = AndroidTextMeasure(
        contentSizePx = contentSizePx,
        titleSizePx = titleSizePx,
        typeface = typeface,
        letterSpacingEm = letterSpacingEm
    )

    companion object {
        const val BASE_CONTENT_SP = 17f
        const val TITLE_SCALE = 1.35f
        const val TITLE_LINE_HEIGHT = 1.4f
        const val PARAGRAPH_SPACING_EM = 0.55f
        /**
         * 源文空行相对普通段距的倍率。段距按「取较大者」结算，所以空行分段的 TXT
         * 每段之间拿到 2× 段距，而只在少数位置留空行的书，那几处场景分隔同样是 2×、
         * 明显宽于普通段距——两种意图都成立，且全程跟着「段落间距」滑杆走，
         * 不再有一整行正文高度的地板把滑杆的效果盖掉。
         */
        const val BLANK_LINE_FACTOR = 2f
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
            val syntaxRules = settings.syntaxHighlightRules.takeIf {
                settings.syntaxHighlightEnabled
            }.orEmpty()
            val baseTypeface = settings.resolveTypeface()
            return ReaderPageStyle(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                paddingHorizontal = horizontal,
                headerHeight = if (settings.showHeader) {
                    statusBarPx + headerPadding + tipSize * 1.6f
                } else {
                    statusBarPx + headerPadding * 0.4f
                },
                footerHeight = if (settings.showFooter) {
                    navigationBarPx.coerceAtLeast(footerPadding) + tipSize * 1.9f
                } else {
                    navigationBarPx + footerPadding * 0.35f
                },
                contentSizePx = contentSize,
                titleSizePx = contentSize * settings.titleScale,
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
                typeface = weightedTypeface(baseTypeface, settings.fontWeight),
                customFontPath = settings.customFontPath,
                customFontPaths = settings.fontLibrary.associate { it.id to it.filePath },
                showHeader = settings.showHeader,
                showFooter = settings.showFooter,
                backgroundImagePath = settings.backgroundImagePath,
                backgroundImageOpacity = settings.backgroundImageOpacity,
                syntaxHighlightRules = syntaxRules,
                paragraphSpacingEm = settings.paragraphSpacingEm,
                firstLineIndentEm = settings.firstLineIndentEm,
                textJustification = settings.textJustification,
                letterSpacingEm = settings.letterSpacingEm
            )
        }

        private fun ReaderSettings.resolveTypeface(): Typeface {
            if (font == ReaderFont.CUSTOM) {
                customFontPath?.let { path ->
                    runCatching { Typeface.createFromFile(File(path)) }.getOrNull()?.let { return it }
                }
            }
            return when (font) {
                ReaderFont.SYSTEM, ReaderFont.CUSTOM -> Typeface.DEFAULT
                ReaderFont.SERIF -> Typeface.SERIF
                ReaderFont.SANS_SERIF -> Typeface.SANS_SERIF
                ReaderFont.MONOSPACE -> Typeface.MONOSPACE
            }
        }

        private fun weightedTypeface(base: Typeface, weight: Int): Typeface =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, weight.coerceIn(100, 900), false)
            } else {
                Typeface.create(base, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            }
    }
}

private val Float.sp get() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
private val Float.dp get() = androidx.compose.ui.unit.Dp(this)
