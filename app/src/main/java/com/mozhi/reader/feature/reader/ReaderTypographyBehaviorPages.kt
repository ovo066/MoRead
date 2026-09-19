package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.chineseConversionModeFor

@Composable
internal fun BehaviorPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderBehaviorActions
) {
    TypographySwitchRow(
        "阅读时保持亮屏",
        "适合长时间连读",
        settings.keepScreenOn,
        palette,
        actions.onKeepScreenOnChange
    )
    TypographySwitchRow(
        "完全沉浸",
        "阅读时隐藏系统状态栏",
        settings.immersiveReading,
        palette,
        actions.onImmersiveReadingChange
    )
    TypographySwitchRow(
        "音量键翻页",
        "音量加上一页，音量减下一页",
        settings.volumeKeysPageTurn,
        palette,
        actions.onVolumeKeysPageTurnChange
    )
}

/**
 * 翻页页：一级页那张大卡的落点。
 *
 * 翻页动画与「上下滚动」是互斥的同一件事（滚动模式下动画不生效），因此摆在同一组里
 * 五选一，而不是像改造前那样「四颗动画 chip + 一颗上下 chip」挤在一行、看不出互斥关系。
 */
@Composable
internal fun PageTurnPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderBehaviorActions
) {
    WidePageLayoutControl(settings, palette, actions)
    val paginated = settings.pageMode == PageMode.PAGINATED
    Text("翻页方式", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    PageTurnAnimation.entries.forEach { animation ->
        TypographyChoiceRow(
            title = animation.shortLabel() + "翻页",
            summary = animation.turnDescription(),
            selected = paginated && settings.pageTurnAnimation == animation,
            palette = palette
        ) {
            actions.onPageModeChange(PageMode.PAGINATED)
            actions.onAnimationChange(animation)
        }
    }
    TypographyChoiceRow(
        title = "上下滚动",
        summary = "像网页一样连续滚动，不分页",
        selected = !paginated,
        palette = palette
    ) { actions.onPageModeChange(PageMode.SCROLL) }

    HorizontalDivider(color = palette.glassBorder)
    TypographySwitchRow(
        "音量键翻页",
        "音量加上一页，音量减下一页",
        settings.volumeKeysPageTurn,
        palette,
        actions.onVolumeKeysPageTurnChange
    )
}

@Composable
internal fun WidePageLayoutControl(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderBehaviorActions
) {
    if (com.mozhi.reader.ui.rememberMoReadWindowWidth() != com.mozhi.reader.ui.MoReadWindowWidth.EXPANDED) return
    val paginated = settings.pageMode == PageMode.PAGINATED
    Text("宽屏排版", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.mozhi.reader.core.datastore.WidePageLayout.entries.forEach { layout ->
            TypographyToggleButton(
                text = if (layout == com.mozhi.reader.core.datastore.WidePageLayout.SINGLE) "单页" else "双页",
                selected = settings.widePageLayout == layout,
                palette = palette,
                enabled = paginated
            ) { actions.onWidePageLayoutChange(layout) }
        }
    }
    Text(
        text = when {
            !paginated -> "滚动始终单栏"
            settings.widePageLayout == com.mozhi.reader.core.datastore.WidePageLayout.DUAL && !actions.spreadActive ->
                "当前宽度不足，暂按单页显示"
            actions.spreadActive -> "双页 · 当前生效，仿真以书脊为轴翻动单张书页"
            else -> "阅读区至少 720dp 时可用双页；宽度变化会保留阅读位置"
        },
        style = MaterialTheme.typography.labelSmall,
        color = palette.muted
    )
}

/**
 * 更多设置页：设一次就不再动的项。
 *
 * 它是一级页那两张大卡里的兜底出口 —— 一级页只留高频控件，低频项集中到这里，
 * 而不是把每一类都提升成一级页底部的一颗胶囊。
 */
@Composable
internal fun MoreSettingsPage(
    settings: ReaderSettings,
    bookId: Long,
    palette: ReaderPalette,
    actions: ReaderLayoutActions,
    onOpenPage: (TypographySecondaryPage) -> Unit
) {
    Text("显示", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    TypographySwitchRow(
        "显示页眉",
        "页面顶部显示书名与章节",
        settings.showHeader,
        palette,
        actions.onShowHeaderChange
    )
    TypographySwitchRow(
        "显示页脚",
        "页面底部显示进度、页码与时间",
        settings.showFooter,
        palette,
        actions.onShowFooterChange
    )
    TypographySwitchRow(
        "两端对齐",
        "正文左右两端拉齐，行末不参差",
        settings.textJustification,
        palette,
        actions.onTextJustificationChange
    )

    HorizontalDivider(color = palette.glassBorder)
    Text("其他", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    TypographyNavRow(
        title = "繁简转换",
        summary = when (settings.chineseConversionModeFor(bookId)) {
            ChineseConversionMode.OFF -> "已关闭"
            ChineseConversionMode.TW2SP -> "简体 · 大陆用语"
            ChineseConversionMode.S2TWP -> "繁体 · 台湾用语"
        },
        palette = palette
    ) { onOpenPage(TypographySecondaryPage.CHINESE_CONVERSION) }
    TypographyNavRow(
        title = "语法高亮",
        summary = if (settings.syntaxHighlightEnabled) {
            "已开启 · ${settings.syntaxHighlightRules.count { it.enabled }} 条规则生效"
        } else {
            "已关闭"
        },
        palette = palette
    ) { onOpenPage(TypographySecondaryPage.SYNTAX) }
    TypographyNavRow(
        title = "阅读交互",
        summary = listOfNotNull(
            "保持亮屏".takeIf { settings.keepScreenOn },
            "完全沉浸".takeIf { settings.immersiveReading },
            "音量键翻页".takeIf { settings.volumeKeysPageTurn }
        ).joinToString(" · ").ifBlank { "全部关闭" },
        palette = palette
    ) { onOpenPage(TypographySecondaryPage.BEHAVIOR) }
}

@Composable
internal fun ChineseConversionPage(
    mode: ChineseConversionMode,
    palette: ReaderPalette,
    onChange: (ChineseConversionMode) -> Unit
) {
    listOf(
        Triple(ChineseConversionMode.OFF, "关闭", "显示书籍原文"),
        Triple(ChineseConversionMode.TW2SP, "简体（大陆用语）", "繁体转简体并改写地区词"),
        Triple(ChineseConversionMode.S2TWP, "繁体（台湾用语）", "简体转台湾正体并改写地区词")
    ).forEach { (value, title, summary) ->
        TypographyChoiceRow(
            title = title,
            summary = summary,
            selected = value == mode,
            palette = palette,
            onClick = { onChange(value) }
        )
    }
    Text(
        "仅改变阅读显示并记住本书选择，不生成新文件。开启后请关闭转换再编辑原文。",
        style = MaterialTheme.typography.bodySmall,
        color = palette.muted,
        modifier = Modifier.padding(top = 12.dp)
    )
}

private fun PageTurnAnimation.turnDescription(): String = when (this) {
    PageTurnAnimation.SIMULATION -> "带折页与投影的翻书效果"
    PageTurnAnimation.COVER -> "新页从边缘覆盖上来"
    PageTurnAnimation.SLIDE -> "两页一起横向平移"
    PageTurnAnimation.NONE -> "直接切换，最省电"
}

internal fun PageTurnAnimation.shortLabel(): String = when (this) {
    PageTurnAnimation.SIMULATION -> "仿真"
    PageTurnAnimation.COVER -> "覆盖"
    PageTurnAnimation.SLIDE -> "平移"
    PageTurnAnimation.NONE -> "无"
}
