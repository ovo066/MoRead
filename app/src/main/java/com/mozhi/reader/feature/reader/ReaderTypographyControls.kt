package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.mozhi.reader.core.datastore.FOLLOW_SYSTEM_BRIGHTNESS
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.PageTurnAnimation
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.backgroundImageIdFor
import com.mozhi.reader.core.datastore.backgroundOpacityFor
import com.mozhi.reader.core.datastore.customThemeIdFor
import com.mozhi.reader.core.datastore.themeFor
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.theme.onAccent
import coil3.compose.AsyncImage
import java.io.File
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
    TypographySliderRow(
        startLabel = "暗",
        endLabel = "亮",
        valueText = readerBrightnessLabel(settings.screenBrightness),
        fraction = readerBrightnessFraction(settings.screenBrightness),
        palette = palette,
        onFraction = { actions.onScreenBrightnessChange(readerBrightnessValueAt(it)) },
        trailing = {
            // 「自动」既是状态指示也是回到跟随系统的开关；跟随系统时点亮。
            TypographyToggleButton(
                text = "自动",
                selected = settings.screenBrightness < 0f,
                palette = palette
            ) { actions.onScreenBrightnessChange(FOLLOW_SYSTEM_BRIGHTNESS) }
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
            actions.onFontScaleChange(typographyValueAt(it, FONT_SCALE_RANGE, FONT_SCALE_STEP))
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
            actions.onLineHeightChange(typographyValueAt(it, LINE_HEIGHT_RANGE, LINE_HEIGHT_STEP))
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
            actions = actions,
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

/**
 * 一行滑条 + 右侧入口。
 *
 * 与参考产品一致：起止标签在胶囊**内部**两端（而不是行首另起一列文字标签），数值贴右端，
 * 右侧留一个入口槽。这样一行里「现在多大 / 怎么调 / 更多在哪」三件事一次说完。
 */
@Composable
internal fun TypographySliderRow(
    startLabel: String,
    endLabel: String,
    valueText: String,
    fraction: Float,
    palette: ReaderPalette,
    onFraction: (Float) -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.weight(1f).height(44.dp),
            shape = CircleShape,
            color = palette.glass,
            contentColor = palette.onBackground,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = startLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    maxLines = 1
                )
                TypographySliderTrack(
                    fraction = fraction,
                    palette = palette,
                    modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    onFraction = onFraction
                )
                Text(
                    text = endLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    maxLines = 1
                )
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp).width(38.dp)
                )
            }
        }
        trailing?.invoke()
    }
}

/** 3dp 轨道 + 强调色填充 + 圆钮；点按定位、拖动连续调。 */
@Composable
private fun TypographySliderTrack(
    fraction: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onFraction: (Float) -> Unit
) {
    val thumbDiameter = 16.dp
    Box(
        modifier = modifier
            .height(44.dp)
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
        val ring = palette.glassStrong
        Canvas(modifier = Modifier.fillMaxWidth().height(44.dp)) {
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
            drawCircle(color = ring, radius = radius, center = Offset(centerX, centerY))
            drawCircle(
                color = accent,
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2.dp.toPx())
            )
        }
    }
}

/** 滑条右侧的「文字 >」入口。 */
@Composable
internal fun TypographyEntryButton(
    text: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.height(44.dp).padding(start = 14.dp, end = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 与入口按钮同高的开关型按钮（亮度行的「自动」）。 */
@Composable
internal fun TypographyToggleButton(
    text: String,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (selected) palette.accentContainer else Color.Transparent,
        contentColor = if (selected) palette.accent else palette.muted,
        border = BorderStroke(
            1.dp,
            if (selected) palette.accent.copy(alpha = 0.35f) else palette.glassBorder
        )
    ) {
        Box(
            modifier = Modifier.height(44.dp).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}

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

/** 面板下沿的两张大卡：当前值当标题，点进去是完整的一页。 */
@Composable
internal fun TypographyBigCard(
    title: String,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = palette.glass,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.height(50.dp).padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
internal fun EntryChip(
    icon: ImageVector,
    text: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = palette.muted, modifier = Modifier.size(15.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** 日/夜标记：一级页用它说明「现在改的是哪一套」，点击进主题页配另一套。 */
@Composable
internal fun SlotPill(slot: ReaderThemeSlot, palette: ReaderPalette, onClick: () -> Unit) {
    val night = slot == ReaderThemeSlot.NIGHT
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = palette.accentContainer,
        contentColor = palette.accent,
        border = BorderStroke(1.dp, palette.accent.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (night) Icons.Outlined.DarkMode else Icons.Outlined.LightMode,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = if (night) "夜间" else "日间",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/** 内置色卡 + 我的主题 + 新建，横滑一排；一级页与主题页共用。 */
@Composable
internal fun ThemeSwatchStrip(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    useBookTheme: Boolean = false,
    onEditCustomTheme: (CustomReaderTheme) -> Unit,
    onCreateCustomTheme: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeCustomId = settings.customThemeIdFor(slot)
    val activeTheme = settings.themeFor(slot)
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReaderTheme.entries
            // 日夜自动切换开着时，「跟随系统」已经由日夜两套方案接管，留着只会让人迷惑。
            .filterNot { settings.dayNightThemeAuto && it == ReaderTheme.SYSTEM }
            .forEach { theme ->
                ThemeSwatch(
                    theme = theme,
                    selected = activeCustomId == null && activeTheme == theme,
                    palette = palette
                ) {
                    if (useBookTheme) actions.onBookThemeChange(theme, slot)
                    else actions.onThemeChange(theme, slot)
                }
            }
        settings.customThemes.forEach { custom ->
            val selected = activeCustomId == custom.id
            val imagePath = settings.imageLibrary.firstOrNull {
                it.id == custom.backgroundImageId
            }?.filePath ?: custom.backgroundImagePath
            CustomThemeSwatch(
                theme = custom,
                imagePath = imagePath,
                selected = selected,
                palette = palette
            ) {
                if (selected) {
                    onEditCustomTheme(custom)
                } else if (useBookTheme) {
                    actions.onBookCustomThemeSelect(custom.id, slot)
                } else {
                    actions.onCustomThemeSelect(custom.id, slot)
                }
            }
        }
        AddThemeSwatch(palette = palette, onClick = onCreateCustomTheme)
    }
}

/**
 * 字体页只管「用哪套字」。字号、行距、字重这些数值项都在悬浮排版卡片里——
 * 它们要边调边看重排，二级页在半屏弹层里看不见正文。
 */
@Composable
internal fun FontPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    Text("内置字体", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
            SegChip(
                text = font.shortLabel(),
                selected = settings.font == font,
                palette = palette,
                modifier = Modifier.width(76.dp)
            ) { actions.onFontChange(font) }
        }
    }
    if (settings.fontLibrary.isNotEmpty()) {
        Text("已导入", style = MaterialTheme.typography.labelMedium, color = palette.muted)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            settings.fontLibrary.forEach { font ->
                SegChip(
                    text = font.displayName,
                    selected = settings.font == ReaderFont.CUSTOM &&
                        settings.selectedCustomFontId == font.id,
                    palette = palette
                ) { actions.onCustomFontSelect(font.id) }
            }
        }
    }
    Surface(
        onClick = actions.onImportFont,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(11.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.UploadFile,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Text("导入 TTF/OTF/TTC", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * 主题页：日夜两套方案在这里配。开了自动切换后，白天也能提前把夜间那套调好——
 * 否则只能等天黑了再摸黑调。
 */
@Composable
internal fun ThemePage(
    settings: ReaderSettings,
    activeSlot: ReaderThemeSlot,
    bookThemeEnabled: Boolean,
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onEditCustomTheme: (CustomReaderTheme, ReaderThemeSlot) -> Unit,
    onCreateCustomTheme: (ReaderThemeSlot) -> Unit
) {
    var editingSlot by remember { mutableStateOf(activeSlot) }
    val slot = if (settings.dayNightThemeAuto || bookThemeEnabled) editingSlot else ReaderThemeSlot.DAY

    AdvancedSwitchRow(
        "本书主题",
        "开启后，本书固定使用下面的日间/夜间方案，不影响其他书",
        bookThemeEnabled,
        palette,
        actions.onBookThemeEnabledChange
    )
    HorizontalDivider(color = palette.glassBorder)
    AdvancedSwitchRow(
        "日夜自动切换",
        "白天和夜里各用一套配色，跟随应用日夜模式换",
        settings.dayNightThemeAuto,
        palette,
        actions.onDayNightAutoChange
    )
    if (settings.dayNightThemeAuto || bookThemeEnabled) {
        SheetRow(label = "方案", palette = palette) {
            SegChip(
                text = "日间",
                selected = slot == ReaderThemeSlot.DAY,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { editingSlot = ReaderThemeSlot.DAY }
            SegChip(
                text = "夜间",
                selected = slot == ReaderThemeSlot.NIGHT,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { editingSlot = ReaderThemeSlot.NIGHT }
        }
    }

    SheetRow(label = "纸色", palette = palette) {
        ThemeSwatchStrip(
            settings = settings,
            slot = slot,
            palette = palette,
            actions = actions,
            useBookTheme = bookThemeEnabled,
            onEditCustomTheme = { theme -> onEditCustomTheme(theme, slot) },
            onCreateCustomTheme = { onCreateCustomTheme(slot) },
            modifier = Modifier.weight(1f)
        )
    }

    HorizontalDivider(color = palette.glassBorder)
    if (bookThemeEnabled) {
        Text(
            "本书的字体、排版和背景随所选主题预设；点已选中的自定义主题可编辑整套方案。",
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted
        )
    } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val backgroundId = settings.backgroundImageIdFor(slot)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Wallpaper,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text("背景图片", style = MaterialTheme.typography.bodyMedium)
                Text(
                    settings.imageLibrary.firstOrNull { it.id == backgroundId }?.displayName
                        ?: "未设置",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            TextButton(onClick = { actions.onImportBackground(slot) }) { Text("导入") }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().height(98.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                BackgroundChoice(
                    name = "无背景图",
                    imagePath = null,
                    selected = backgroundId == null,
                    palette = palette,
                    onClick = { actions.onClearBackground(slot) }
                )
            }
            items(settings.imageLibrary, key = { it.id }) { image ->
                BackgroundChoice(
                    name = image.displayName,
                    imagePath = image.filePath,
                    selected = backgroundId == image.id,
                    palette = palette,
                    onClick = { actions.onBackgroundImageSelect(image.id, slot) }
                )
            }
        }
        if (backgroundId != null) {
            val opacity = settings.backgroundOpacityFor(slot)
            TypographyStepper(
                label = "背景强度",
                valueText = "" + (opacity * 100).roundToInt() + "%",
                value = opacity,
                range = 0.05f..1f,
                step = 0.05f,
                palette = palette,
                onValueChange = { actions.onBackgroundOpacityChange(it, slot) }
            )
        }
    }
}

@Composable
internal fun BehaviorPage(
    settings: ReaderSettings,
    palette: ReaderPalette,
    actions: ReaderTypographyActions
) {
    AdvancedSwitchRow(
        "阅读时保持亮屏",
        "适合长时间连读",
        settings.keepScreenOn,
        palette,
        actions.onKeepScreenOnChange
    )
    AdvancedSwitchRow(
        "完全沉浸",
        "阅读时隐藏系统状态栏",
        settings.immersiveReading,
        palette,
        actions.onImmersiveReadingChange
    )
    AdvancedSwitchRow(
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
    actions: ReaderTypographyActions
) {
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
    AdvancedSwitchRow(
        "音量键翻页",
        "音量加上一页，音量减下一页",
        settings.volumeKeysPageTurn,
        palette,
        actions.onVolumeKeysPageTurnChange
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
    palette: ReaderPalette,
    actions: ReaderTypographyActions,
    onOpenPage: (TypographySecondaryPage) -> Unit
) {
    Text("显示", style = MaterialTheme.typography.labelMedium, color = palette.muted)
    AdvancedSwitchRow(
        "显示页眉",
        "页面顶部显示书名与章节",
        settings.showHeader,
        palette,
        actions.onShowHeaderChange
    )
    AdvancedSwitchRow(
        "显示页脚",
        "页面底部显示进度、页码与时间",
        settings.showFooter,
        palette,
        actions.onShowFooterChange
    )
    AdvancedSwitchRow(
        "两端对齐",
        "正文左右两端拉齐，行末不参差",
        settings.textJustification,
        palette,
        actions.onTextJustificationChange
    )

    HorizontalDivider(color = palette.glassBorder)
    Text("其他", style = MaterialTheme.typography.labelMedium, color = palette.muted)
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

/** 二级页里的单选行：左侧选中点 + 标题与说明，比一排等宽 chip 更容易读懂互斥关系。 */
@Composable
internal fun TypographyChoiceRow(
    title: String,
    summary: String,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.accentContainer else Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(
            1.dp,
            if (selected) palette.accent.copy(alpha = 0.32f) else palette.glassBorder
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) palette.accent else palette.onBackground
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (selected) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "已选择",
                    tint = palette.accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 二级页里通往更深一层的行。 */
@Composable
internal fun TypographyNavRow(
    title: String,
    summary: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = palette.muted,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

private fun PageTurnAnimation.turnDescription(): String = when (this) {
    PageTurnAnimation.SIMULATION -> "带折页与投影的翻书效果"
    PageTurnAnimation.COVER -> "新页从边缘覆盖上来"
    PageTurnAnimation.SLIDE -> "两页一起横向平移"
    PageTurnAnimation.NONE -> "直接切换，最省电"
}

@Composable
internal fun BackgroundChoice(
    name: String,
    imagePath: String?,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.width(82.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) palette.accentContainer else palette.glass,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) palette.accent else palette.muted.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(58.dp)
                    .background(palette.background, RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (imagePath == null) {
                    Icon(
                        Icons.Outlined.Wallpaper,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    AsyncImage(
                        model = File(imagePath),
                        contentDescription = name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(58.dp)
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "已选择",
                        tint = palette.onAccent,
                        modifier = Modifier.align(Alignment.TopEnd).size(18.dp)
                            .background(palette.accent, CircleShape).padding(2.dp)
                    )
                }
            }
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                color = palette.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 分类二级页。[chipLabel] 上胶囊，[title] 上二级页标题栏。 */
