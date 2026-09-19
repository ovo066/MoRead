package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush as ShaderBrush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.ReaderThemeSlot
import com.mozhi.reader.core.datastore.backgroundImageIdFor
import com.mozhi.reader.core.datastore.backgroundOpacityFor
import com.mozhi.reader.core.datastore.customThemeIdFor
import com.mozhi.reader.core.datastore.themeFor
import com.mozhi.reader.ui.theme.onAccent
import java.io.File
import kotlin.math.roundToInt

/** 内置色卡 + 我的主题 + 新建，横滑一排；一级页与主题页共用。 */
@Composable
internal fun ThemeSwatchStrip(
    settings: ReaderSettings,
    slot: ReaderThemeSlot,
    palette: ReaderPalette,
    actions: ReaderThemeActions,
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
 * 主题页：日夜两套方案在这里配。开了自动切换后，白天也能提前把夜间那套调好——
 * 否则只能等天黑了再摸黑调。
 */
@Composable
internal fun ThemePage(
    settings: ReaderSettings,
    activeSlot: ReaderThemeSlot,
    bookThemeEnabled: Boolean,
    palette: ReaderPalette,
    actions: ReaderThemeActions,
    onEditCustomTheme: (CustomReaderTheme, ReaderThemeSlot) -> Unit,
    onCreateCustomTheme: (ReaderThemeSlot) -> Unit
) {
    var editingSlot by remember { mutableStateOf(activeSlot) }
    val slot = if (settings.dayNightThemeAuto || bookThemeEnabled) editingSlot else ReaderThemeSlot.DAY

    TypographySwitchRow(
        "本书主题",
        "开启后，本书固定使用下面的日间/夜间方案，不影响其他书",
        bookThemeEnabled,
        palette,
        actions.onBookThemeEnabledChange
    )
    HorizontalDivider(color = palette.glassBorder)
    TypographySwitchRow(
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
            items(settings.imageLibrary.filter { it.purpose != com.mozhi.reader.core.datastore.ReaderImagePurpose.COVER || it.id == backgroundId }
                .sortedBy { if (it.purpose == com.mozhi.reader.core.datastore.ReaderImagePurpose.BACKGROUND) 0 else 1 }, key = { it.id }) { image ->
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

@Composable
internal fun ThemeSwatch(
    theme: ReaderTheme,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val swatchBrush = when (theme) {
        // 跟随系统：日/夜两个底色的硬分割。取值直接从调色板来，不再另写字面量。
        ReaderTheme.SYSTEM -> {
            val light = readerPalette(ReaderTheme.LIGHT, systemDark = false, palette.accent)
                .background
            val dark = readerPalette(ReaderTheme.DARK, systemDark = true, palette.accent)
                .background
            ShaderBrush.linearGradient(
                0f to light,
                0.5f to light,
                0.5f to dark,
                1f to dark
            )
        }
        else -> {
            val color = readerPalette(theme, systemDark = false, palette.accent).background
            ShaderBrush.linearGradient(listOf(color, color))
        }
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(swatchBrush, CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) palette.accent else palette.glassBorder,
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = theme.swatchLabel(),
                tint = palette.accent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

internal val LINE_HEIGHT_PRESETS = listOf("紧凑" to 1.4f, "标准" to 1.7f, "宽松" to 2.0f)

internal fun ReaderTheme.swatchLabel(): String = when (this) {
    ReaderTheme.SYSTEM -> "跟随系统"
    ReaderTheme.LIGHT -> "日间"
    ReaderTheme.DARK -> "夜间"
    ReaderTheme.PAPER -> "纸张"
    ReaderTheme.EYE_CARE -> "护眼"
    ReaderTheme.AMOLED -> "深空"
    ReaderTheme.MIST -> "青简"
}

/** 自定义主题卡片优先展示背景图片；再次点击当前主题进入编辑。 */
