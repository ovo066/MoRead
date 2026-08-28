package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as ShaderBrush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
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
import com.mozhi.reader.core.datastore.ReaderFontLibraryCodec
import com.mozhi.reader.core.datastore.ReaderImageLibraryCodec
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderSyntaxFont
import com.mozhi.reader.core.datastore.ReaderSyntaxMatchMode
import com.mozhi.reader.core.datastore.ReaderSyntaxRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.backgroundImageIdFor
import com.mozhi.reader.core.datastore.backgroundOpacityFor
import com.mozhi.reader.core.datastore.customThemeIdFor
import com.mozhi.reader.core.datastore.resolveThemeSlot
import com.mozhi.reader.core.datastore.themeFor
import com.mozhi.reader.feature.reader.render.ReaderPageStyle
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.onAccent
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

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
    Text("排版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)

    SheetRow(label = "字号", palette = palette) {
        val sizeSp = (ReaderPageStyle.BASE_CONTENT_SP * settings.fontScale).roundToInt()
        StepButton(text = "A−", palette = palette, modifier = Modifier.weight(1f)) {
            actions.onFontScaleChange(
                ((sizeSp - 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f)
            )
        }
        Text(
            text = sizeSp.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(44.dp)
        )
        StepButton(text = "A＋", palette = palette, modifier = Modifier.weight(1f)) {
            actions.onFontScaleChange(
                ((sizeSp + 1) / ReaderPageStyle.BASE_CONTENT_SP).coerceIn(0.75f, 2f)
            )
        }
    }

    SheetRow(label = "行距", palette = palette) {
        LINE_HEIGHT_PRESETS.forEach { (label, value) ->
            SegChip(
                text = label,
                selected = abs(settings.lineHeight - value) < 0.049f,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) { actions.onLineHeightChange(value) }
        }
    }

    SheetRow(label = "主题", palette = palette) {
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
        // 日夜各一套时，标出此刻在改哪一套；点进去可以配另一套。
        if (settings.dayNightThemeAuto) {
            SlotPill(slot = slot, palette = palette) { onOpenPage(TypographySecondaryPage.THEME) }
        }
    }

    SheetRow(label = "翻页", palette = palette) {
        val paginated = settings.pageMode == PageMode.PAGINATED
        PageTurnAnimation.entries.forEach { animation ->
            SegChip(
                text = animation.shortLabel(),
                selected = paginated && settings.pageTurnAnimation == animation,
                palette = palette,
                modifier = Modifier.weight(1f)
            ) {
                actions.onPageModeChange(PageMode.PAGINATED)
                actions.onAnimationChange(animation)
            }
        }
        SegChip(
            text = "上下",
            selected = settings.pageMode == PageMode.SCROLL,
            palette = palette,
            modifier = Modifier.weight(1f)
        ) { actions.onPageModeChange(PageMode.SCROLL) }
    }

    HorizontalDivider(color = palette.glassBorder)

    SecondaryPageChips(
        palette = palette,
        onOpenTypographyCard = onOpenTypographyCard,
        onOpenPage = onOpenPage
    )
}

/**
 * 二级入口：一排胶囊，两行放下五个，不占用一级页的纵向预算。
 *
 * 「排版」不推弹层内的二级页，而是收起弹层、浮出居中卡片——那一类设置每改一格都要看重排，
 * 半屏弹层压着下半屏正文时看不出所以然。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun SecondaryPageChips(
    palette: ReaderPalette,
    onOpenTypographyCard: () -> Unit,
    onOpenPage: (TypographySecondaryPage) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        EntryChip(
            icon = Icons.Outlined.Tune,
            text = "排版",
            palette = palette,
            onClick = onOpenTypographyCard
        )
        TypographySecondaryPage.entries.forEach { page ->
            EntryChip(
                icon = page.icon,
                text = page.chipLabel,
                palette = palette
            ) { onOpenPage(page) }
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
