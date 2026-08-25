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
internal fun CustomThemeSwatch(
    theme: CustomReaderTheme,
    imagePath: String?,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val background = Color(theme.backgroundArgb)
    val accent = Color(theme.accentArgb)
    Column(
        modifier = Modifier.width(50.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(background)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) palette.accent else palette.glassBorder,
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            if (imagePath != null) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = theme.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "编辑「${theme.name}」",
                    tint = Color(theme.textArgb),
                    modifier = Modifier.size(15.dp)
                        .background(background.copy(alpha = 0.72f), CircleShape)
                        .padding(2.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .align(Alignment.BottomEnd)
                    .background(accent, CircleShape)
                    .border(1.dp, background, CircleShape)
            )
        }
        Text(
            text = theme.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) palette.accent else palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
internal fun AddThemeSwatch(palette: ReaderPalette, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .border(1.dp, palette.glassBorder, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.Add,
            contentDescription = "新建自定义主题",
            tint = palette.muted,
            modifier = Modifier.size(17.dp)
        )
    }
}

/** 把当前排版与指定槽的配色打包成一套自定义主题的初值。 */
internal fun ReaderSettings.toCustomReaderTheme(
    id: Long,
    name: String,
    backgroundArgb: Int,
    textArgb: Int,
    accentArgb: Int,
    isDark: Boolean,
    slot: ReaderThemeSlot
): CustomReaderTheme {
    val selectedFont = fontLibrary.firstOrNull { it.id == selectedCustomFontId }
    val backgroundId = backgroundImageIdFor(slot)
    val selectedBackground = imageLibrary.firstOrNull { it.id == backgroundId }
    return CustomReaderTheme(
        id = id,
        name = name,
        backgroundArgb = backgroundArgb,
        textArgb = textArgb,
        accentArgb = accentArgb,
        isDark = isDark,
        font = font,
        customFontId = selectedCustomFontId,
        customFontPath = selectedFont?.filePath ?: customFontPath,
        customFontName = selectedFont?.displayName ?: customFontName,
        fontScale = fontScale,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        pageMarginLeft = pageMarginLeft,
        pageMarginRight = pageMarginRight,
        pageMarginTop = pageMarginTop,
        pageMarginBottom = pageMarginBottom,
        letterSpacingEm = letterSpacingEm,
        paragraphSpacingEm = paragraphSpacingEm,
        firstLineIndentEm = firstLineIndentEm,
        titleScale = titleScale,
        titleTopSpacing = titleTopSpacing,
        titleBottomSpacing = titleBottomSpacing,
        headerMarginTop = headerMarginTop,
        footerMarginBottom = footerMarginBottom,
        textJustification = textJustification,
        showHeader = showHeader,
        showFooter = showFooter,
        backgroundImageId = backgroundId,
        backgroundImagePath = selectedBackground?.filePath,
        backgroundImageOpacity = backgroundOpacityFor(slot)
    )
}

internal enum class ThemeColorTarget(val label: String) {
    BACKGROUND("底色"),
    TEXT("正文"),
    ACCENT("强调")
}

/** 完整主题编辑器：字体、背景图片与配色在同一处选择，保存后立即应用。 */
@Composable
internal fun CustomThemeEditorDialog(
    initial: CustomReaderTheme,
    settings: ReaderSettings,
    palette: ReaderPalette,
    onImportFont: () -> Unit,
    onImportBackground: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (CustomReaderTheme) -> Unit,
    onDelete: (() -> Unit)?
) {
    var name by remember { mutableStateOf(initial.name) }
    var background by remember { mutableStateOf(Color(initial.backgroundArgb)) }
    var text by remember { mutableStateOf(Color(initial.textArgb)) }
    var accent by remember { mutableStateOf(Color(initial.accentArgb)) }
    var isDark by remember { mutableStateOf(initial.isDark ?: (Color(initial.backgroundArgb).luminance() < 0.5f)) }
    var selectedFont by remember { mutableStateOf(initial.font) }
    val initialCustomFontId = initial.customFontId
        ?: initial.customFontPath?.let(ReaderFontLibraryCodec::legacyId)
    val initialBackgroundImageId = initial.backgroundImageId
        ?: initial.backgroundImagePath?.let(ReaderImageLibraryCodec::legacyId)
    var selectedCustomFontId by remember { mutableStateOf(initialCustomFontId) }
    var selectedBackgroundImageId by remember { mutableStateOf(initialBackgroundImageId) }
    var backgroundImageOpacity by remember { mutableStateOf(initial.backgroundImageOpacity) }
    var target by remember { mutableStateOf(ThemeColorTarget.BACKGROUND) }

    val current = when (target) {
        ThemeColorTarget.BACKGROUND -> background
        ThemeColorTarget.TEXT -> text
        ThemeColorTarget.ACCENT -> accent
    }
    val selectedBackground = settings.imageLibrary.firstOrNull { it.id == selectedBackgroundImageId }
    val selectedBackgroundPath = selectedBackground?.filePath
        ?: initial.backgroundImagePath.takeIf { selectedBackgroundImageId == initialBackgroundImageId }
    val selectedFontAsset = settings.fontLibrary.firstOrNull { it.id == selectedCustomFontId }
    fun update(color: Color) = when (target) {
        ThemeColorTarget.BACKGROUND -> background = color
        ThemeColorTarget.TEXT -> text = color
        ThemeColorTarget.ACCENT -> accent = color
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.id == 0L) "新建阅读主题" else "编辑阅读主题") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(12) },
                    label = { Text("主题名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 只决定界面元素按浅底还是深底渲染；日夜自动切换在「主题与背景」里设。
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("明暗基调", style = MaterialTheme.typography.labelMedium)
                    FilterChip(
                        selected = !isDark,
                        onClick = { isDark = false },
                        label = { Text("浅色") }
                    )
                    FilterChip(
                        selected = isDark,
                        onClick = { isDark = true },
                        label = { Text("深色") }
                    )
                }

                Text("字体", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    ReaderFont.entries.filter { it != ReaderFont.CUSTOM }.forEach { font ->
                        SegChip(
                            text = font.shortLabel(),
                            selected = selectedFont == font,
                            palette = palette
                        ) { selectedFont = font }
                    }
                    settings.fontLibrary.forEach { font ->
                        SegChip(
                            text = font.displayName,
                            selected = selectedFont == ReaderFont.CUSTOM && selectedCustomFontId == font.id,
                            palette = palette
                        ) {
                            selectedFont = ReaderFont.CUSTOM
                            selectedCustomFontId = font.id
                        }
                    }
                }
                TextButton(onClick = onImportFont) {
                    Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("导入字体", modifier = Modifier.padding(start = 5.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("背景图片", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onImportBackground) { Text("导入图片") }
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth().height(98.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        BackgroundChoice(
                            name = "无背景图",
                            imagePath = null,
                            selected = selectedBackgroundImageId == null,
                            palette = palette,
                            onClick = { selectedBackgroundImageId = null }
                        )
                    }
                    items(settings.imageLibrary, key = { it.id }) { image ->
                        BackgroundChoice(
                            name = image.displayName,
                            imagePath = image.filePath,
                            selected = selectedBackgroundImageId == image.id,
                            palette = palette,
                            onClick = { selectedBackgroundImageId = image.id }
                        )
                    }
                }
                if (selectedBackgroundImageId != null) {
                    TypographyValueSlider(
                        label = "背景强度",
                        valueText = "" + (backgroundImageOpacity * 100).roundToInt() + "%",
                        value = backgroundImageOpacity,
                        range = 0.05f..1f,
                        steps = 18,
                        palette = palette,
                        onValueChange = { backgroundImageOpacity = it }
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(background)
                        .border(1.dp, text.copy(alpha = 0.18f), RoundedCornerShape(14.dp))
                ) {
                    if (selectedBackgroundPath != null) {
                        AsyncImage(
                            model = File(selectedBackgroundPath),
                            contentDescription = "主题背景预览",
                            contentScale = ContentScale.Crop,
                            alpha = backgroundImageOpacity,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "春江潮水连海平，海上明月共潮生。",
                            color = text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Surface(color = accent, shape = RoundedCornerShape(9.dp)) {
                            Text(
                                text = "强调色",
                                color = accent.onAccent(),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
                Text("配色", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeColorTarget.entries.forEach { candidate ->
                        val dotColor = when (candidate) {
                            ThemeColorTarget.BACKGROUND -> background
                            ThemeColorTarget.TEXT -> text
                            ThemeColorTarget.ACCENT -> accent
                        }
                        FilterChip(
                            selected = target == candidate,
                            onClick = { target = candidate },
                            label = { Text(candidate.label) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(13.dp)
                                        .background(dotColor, CircleShape)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                )
                            }
                        )
                    }
                }
                NoteStyleColorPalette(color = current, onColorChange = ::update)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim().ifBlank { "自定义主题" },
                            backgroundArgb = background.toArgb(),
                            textArgb = text.toArgb(),
                            accentArgb = accent.toArgb(),
                            isDark = isDark,
                            font = selectedFont,
                            customFontId = selectedCustomFontId.takeIf { selectedFont == ReaderFont.CUSTOM },
                            customFontPath = if (selectedFont == ReaderFont.CUSTOM) {
                                selectedFontAsset?.filePath
                                    ?: initial.customFontPath.takeIf { selectedCustomFontId == initialCustomFontId }
                            } else null,
                            customFontName = if (selectedFont == ReaderFont.CUSTOM) {
                                selectedFontAsset?.displayName
                                    ?: initial.customFontName.takeIf { selectedCustomFontId == initialCustomFontId }
                            } else null,
                            backgroundImageId = selectedBackgroundImageId,
                            backgroundImagePath = selectedBackgroundPath,
                            backgroundImageOpacity = backgroundImageOpacity
                        )
                    )
                }
            ) { Text("保存并应用") }
        },
        dismissButton = {
            Row {
                onDelete?.let { delete ->
                    TextButton(onClick = delete) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
