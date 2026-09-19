package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.FOLLOW_SYSTEM_BRIGHTNESS
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import kotlin.math.roundToInt

/**
 * 排版面板一级页。
 *
 * 版式对齐成熟阅读器的做法：**二级入口分散挂在它所属的控件旁边**，而不是全部堆到面板最底部。
 * 改造前底部是一个 FlowRow 塞五颗胶囊（排版/字体/主题/高亮/交互），既看不出哪个通向哪儿，
 * 也让面板下沿变成一排同权重的按钮。
 *
 *   [ 暗 --O-- 亮  自动 ]                  <- 屏幕亮度
 *   [ 小 --O-- 大   22 ]   字体 >
 *   [ 紧 --O-- 松  1.7 ]   排版 >          <- 排版 = 悬浮细调卡（正文/标题/页面/页眉页脚）
 *   阅读主题 · 当前为日间方案      本书主题 >
 *   ( 色卡横排 )                    更多 >
 *   [  仿真翻页  > ] [  更多设置  > ]
 *
 * 硬约束不变：一级页必须在半高 sheet 里一屏放得下，不出现滚动。
 */
@Composable
internal fun TypographyMainPanel(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    bookThemeEnabled: Boolean,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onOpenPage: (TypographySecondaryPage) -> Unit,
    onOpenTypographyCard: () -> Unit,
    onEditCustomTheme: (CustomReaderTheme) -> Unit,
    onCreateCustomTheme: () -> Unit
) {
    WidePageLayoutControl(settings, palette, actions.behavior)
    TypographySliderRow(
        startLabel = "暗",
        endLabel = "亮",
        valueText = readerBrightnessLabel(settings.screenBrightness),
        fraction = readerBrightnessFraction(settings.screenBrightness),
        palette = palette,
        onFraction = { actions.behavior.onScreenBrightnessChange(readerBrightnessValueAt(it)) },
        trailing = {
            // 「自动」既是状态指示也是回到跟随系统的开关；跟随系统时点亮。
            TypographyToggleButton(
                text = "自动",
                selected = settings.screenBrightness < 0f,
                palette = palette
            ) { actions.behavior.onScreenBrightnessChange(FOLLOW_SYSTEM_BRIGHTNESS) }
        }
    )

    val sizeSp = (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt()
    TypographySliderRow(
        startLabel = "小",
        endLabel = "大",
        valueText = sizeSp.toString(),
        fraction = typographyFraction(settings.fontScale, FONT_SCALE_RANGE),
        palette = palette,
        onFraction = {
            actions.layout.onFontScaleChange(typographyValueAt(it, FONT_SCALE_RANGE, FONT_SCALE_STEP))
        },
        trailing = {
            TypographyEntryButton(text = "字体", palette = palette) {
                onOpenPage(TypographySecondaryPage.FONT)
            }
        }
    )

    TypographySliderRow(
        startLabel = "紧",
        endLabel = "松",
        valueText = String.format(java.util.Locale.ROOT, "%.2f", settings.lineHeight),
        fraction = typographyFraction(settings.lineHeight, LINE_HEIGHT_RANGE),
        palette = palette,
        onFraction = {
            actions.layout.onLineHeightChange(typographyValueAt(it, LINE_HEIGHT_RANGE, LINE_HEIGHT_STEP))
        },
        trailing = {
            // 「排版」推的是悬浮细调卡，不是弹层内二级页：字间距/段距/缩进这类每改一格
            // 都要看重排结果，半屏弹层压着下半屏正文时看不出所以然。
            TypographyEntryButton(text = "排版", palette = palette, onClick = onOpenTypographyCard)
        }
    )

    ThemeSectionHeader(
        slot = slot,
        dayNightAuto = settings.dayNightThemeAuto,
        bookThemeEnabled = bookThemeEnabled,
        palette = palette,
        onOpenThemePage = { onOpenPage(TypographySecondaryPage.THEME) }
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeSwatchStrip(
            settings = settings,
            slot = slot,
            palette = palette,
            actions = actions.theme,
            useBookTheme = bookThemeEnabled,
            onEditCustomTheme = onEditCustomTheme,
            onCreateCustomTheme = onCreateCustomTheme,
            modifier = Modifier.weight(1f)
        )
        // 色卡横排末尾的出口：主题页里还有日夜两套方案、背景图与背景强度。
        TypographyEntryButton(text = "更多", palette = palette) {
            onOpenPage(TypographySecondaryPage.THEME)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TypographyBigCard(
            title = if (settings.pageMode == PageMode.SCROLL) {
                "上下滚动"
            } else {
                settings.pageTurnAnimation.shortLabel() + "翻页"
            },
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { onOpenPage(TypographySecondaryPage.PAGE_TURN) }
        TypographyBigCard(
            title = "更多设置",
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { onOpenPage(TypographySecondaryPage.MORE) }
    }
}

internal val FONT_SCALE_RANGE = 0.75f..2f
internal const val FONT_SCALE_STEP = 0.05f
internal val LINE_HEIGHT_RANGE = 1f..2.2f
internal const val LINE_HEIGHT_STEP = 0.05f

/** 主题小节抬头：左边说明此刻在改哪一套，右边通向「本书主题」。 */
@Composable
private fun ThemeSectionHeader(
    slot: ReaderThemeSlot,
    dayNightAuto: Boolean,
    bookThemeEnabled: Boolean,
    palette: ReaderPalette,
    onOpenThemePage: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "阅读主题",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.onBackground
                )
                if (dayNightAuto) {
                    Icon(
                        imageVector = if (slot == ReaderThemeSlot.NIGHT) {
                            Icons.Outlined.DarkMode
                        } else {
                            Icons.Outlined.LightMode
                        },
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.padding(start = 6.dp).size(15.dp)
                    )
                }
            }
            Text(
                text = when {
                    bookThemeEnabled -> "本书使用独立配色"
                    dayNightAuto && slot == ReaderThemeSlot.NIGHT -> "当前为夜间方案"
                    dayNightAuto -> "当前为日间方案"
                    else -> "日夜共用一套配色"
                },
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
        TypographyEntryButton(text = "本书主题", palette = palette, onClick = onOpenThemePage)
    }
}
