package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.activeCustomTheme
import com.mozhi.reader.ui.components.MoReadDropdownMenu
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.theme.onAccent

data class ReaderPalette(
    val background: Color,
    val onBackground: Color,
    val muted: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassBorder: Color,
    val accent: Color,
    val accentContainer: Color,
    val onAccent: Color,
    val scrim: Color,
    val isDark: Boolean
)

/**
 * 七套阅读底色。背景与正文色沿用原有取值（纸黄、青简的淡青底是护眼需求，保留），
 * 但 accent 一律来自用户在「设置 › 外观」选的强调色 —— 阅读页不再自带绿色。
 * 纸色明暗独立于应用明暗，强调色变体必须按纸色自身取（应用夜间 + 纸白底时
 * 用夜间近白强调色会压在浅纸上看不见，反之亦然）。
 */
fun ReaderTheme.resolvesDark(systemDark: Boolean): Boolean = when (this) {
    ReaderTheme.AMOLED, ReaderTheme.DARK -> true
    ReaderTheme.SYSTEM -> systemDark
    else -> false
}

@Composable
fun readerPalette(theme: ReaderTheme, systemDark: Boolean): ReaderPalette = readerPalette(
    theme = theme,
    systemDark = systemDark,
    accent = com.mozhi.reader.ui.theme.accentColorFor(theme.resolvesDark(systemDark))
)

/** 阅读页调色板统一入口：自定义主题生效时用三色派生，否则走内置七套。 */
@Composable
fun readerPalette(settings: ReaderSettings, systemDark: Boolean): ReaderPalette {
    val custom = settings.activeCustomTheme()
    return if (custom != null) customReaderPalette(custom) else readerPalette(settings.theme, systemDark)
}

/**
 * 由用户三色（背景/正文/强调）派生完整调色板：明暗按背景亮度判定，
 * 玻璃层向白提亮、弱化色由正文与背景混合，保证任意配色下层次仍在。
 */
fun customReaderPalette(theme: CustomReaderTheme): ReaderPalette {
    val background = Color(theme.backgroundArgb)
    val text = Color(theme.textArgb)
    val accent = Color(theme.accentArgb)
    val dark = background.luminance() < 0.5f
    val glassBase = lerp(background, Color.White, if (dark) 0.07f else 0.42f)
    val glassStrongBase = lerp(background, Color.White, if (dark) 0.10f else 0.55f)
    return ReaderPalette(
        background = background,
        onBackground = text,
        muted = text.copy(alpha = 0.55f).compositeOver(background),
        glass = glassBase.copy(alpha = if (dark) 0.88f else 0.90f),
        glassStrong = glassStrongBase.copy(alpha = if (dark) 0.95f else 0.97f),
        glassBorder = text.copy(alpha = 0.13f),
        accent = accent,
        accentContainer = accent.copy(alpha = if (dark) 0.26f else 0.16f).compositeOver(background),
        onAccent = accent.onAccentColor(),
        scrim = Color.Black.copy(alpha = if (dark) 0.6f else 0.42f),
        isDark = dark
    )
}

fun readerPalette(theme: ReaderTheme, systemDark: Boolean, accent: Color): ReaderPalette {
    val resolvedDark = theme == ReaderTheme.DARK || (theme == ReaderTheme.SYSTEM && systemDark)
    return when {
        theme == ReaderTheme.AMOLED -> ReaderPalette(
            background = Color(0xFF000000),
            onBackground = Color(0xFFB8B8B8),
            muted = Color(0xFF6E6E6E),
            glass = Color(0xE6181818),
            glassStrong = Color(0xF2181818),
            glassBorder = Color(0x1FFFFFFF),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.24f).compositeOver(Color(0xFF1A1A1A)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x99000000),
            isDark = true
        )
        resolvedDark -> ReaderPalette(
            background = Color(0xFF141414),
            // 夜间正文从纯白降为浅灰，长阅读对比度更舒适。
            onBackground = Color(0xFFCCCCCC),
            muted = Color(0xFF8B8B8B),
            glass = Color(0xDB252525),
            glassStrong = Color(0xF2262626),
            glassBorder = Color(0x24FFFFFF),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.26f).compositeOver(Color(0xFF262626)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x99090909),
            isDark = true
        )
        theme == ReaderTheme.EYE_CARE -> ReaderPalette(
            background = Color(0xFFF1F6E9),
            onBackground = Color(0xFF2C312A),
            muted = Color(0xFF71766D),
            glass = Color(0xD9F4F8EF),
            glassStrong = Color(0xFFF1F6EC),
            glassBorder = Color(0x22343831),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.16f).compositeOver(Color(0xFFF1F6E9)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x66272A25),
            isDark = false
        )
        theme == ReaderTheme.PAPER -> ReaderPalette(
            background = Color(0xFFFAF3E6),
            onBackground = Color(0xFF32302A),
            muted = Color(0xFF85806F),
            glass = Color(0xE6F9F4E9),
            glassStrong = Color(0xFFF8F1E5),
            glassBorder = Color(0x1F4B4135),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.16f).compositeOver(Color(0xFFFAF3E6)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x6640382E),
            isDark = false
        )
        theme == ReaderTheme.MIST -> ReaderPalette(
            background = Color(0xFFEAF0EC),
            onBackground = Color(0xFF2A302E),
            muted = Color(0xFF6B7370),
            glass = Color(0xE6ECF2EE),
            glassStrong = Color(0xFFECF2EE),
            glassBorder = Color(0x1F323937),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.16f).compositeOver(Color(0xFFEAF0EC)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x662A302E),
            isDark = false
        )
        else -> ReaderPalette(
            background = Color(0xFFFCFCFA),
            onBackground = Color(0xFF2A2A2A),
            muted = Color(0xFF787878),
            glass = Color(0xE5FBFBF9),
            glassStrong = Color(0xFFF8F8F6),
            glassBorder = Color(0x1F343434),
            accent = accent,
            accentContainer = accent.copy(alpha = 0.14f).compositeOver(Color(0xFFFCFCFA)),
            onAccent = accent.onAccentColor(),
            scrim = Color(0x5C252525),
            isDark = false
        )
    }
}

/** 强调色上的文字：按亮度取黑或白，保证任意自定义色都可读。 */
private fun Color.onAccentColor(): Color = onAccent()

@Composable
fun ReaderChrome(
    visible: Boolean,
    bookTitle: String,
    chapterTitle: String,
    chapterProgress: Float,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddBookmark: () -> Unit,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekChapter: (Float) -> Unit,
    onContents: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onCompanion: () -> Unit,
    onSearch: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { -it / 2 },
            exit = fadeOut() + slideOutVertically { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                palette = palette,
                onBack = onBack,
                onOpenDetails = onOpenDetails,
                onAddBookmark = onAddBookmark,
                onSearch = onSearch
            )
        }

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            ReaderBottomBar(
                chapterProgress = chapterProgress,
                palette = palette,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onSeekChapter = onSeekChapter,
                onContents = onContents,
                onBookmarks = onBookmarks,
                onSettings = onSettings,
                onTts = onTts,
                onCompanion = onCompanion
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ReaderTopBar(
    bookTitle: String,
    chapterTitle: String,
    palette: ReaderPalette,
    onBack: () -> Unit,
    onOpenDetails: () -> Unit,
    onAddBookmark: () -> Unit,
    onSearch: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBarsIgnoringVisibility)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassIconButton(
            icon = Icons.AutoMirrored.Outlined.ArrowBack,
            contentDescription = "返回书架",
            palette = palette,
            onClick = onBack
        )
        Surface(
            modifier = Modifier
                .weight(1f)
                .shadow(12.dp, RoundedCornerShape(17.dp), clip = false)
                .clickable(onClick = onOpenDetails),
            shape = RoundedCornerShape(17.dp),
            color = palette.glass,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = bookTitle,
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = chapterTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = palette.muted,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        // 右上角三点菜单：收纳添加书签/书内搜索等低频操作，给未来入口留位
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            GlassIconButton(
                icon = Icons.Outlined.MoreVert,
                contentDescription = "更多操作",
                palette = palette,
                onClick = { menuExpanded = true }
            )
            MoReadDropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                containerColor = palette.glass,
                contentColor = palette.onBackground,
                borderColor = palette.glassBorder,
                offset = androidx.compose.ui.unit.DpOffset(0.dp, 11.dp),
                minWidth = 160.dp,
                maxWidth = 160.dp
            ) {
                MoReadMenuItem(
                    text = "添加书签",
                    icon = Icons.Outlined.BookmarkAdd,
                    onClick = {
                        menuExpanded = false
                        onAddBookmark()
                    }
                )
                MoReadMenuItem(
                    text = "书内搜索",
                    icon = Icons.Outlined.Search,
                    onClick = {
                        menuExpanded = false
                        onSearch()
                    }
                )
            }
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .shadow(10.dp, RoundedCornerShape(15.dp), clip = false),
        shape = RoundedCornerShape(15.dp),
        color = palette.glass,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = contentDescription, tint = palette.onBackground)
        }
    }
}

@Composable
private fun ReaderBottomBar(
    chapterProgress: Float,
    palette: ReaderPalette,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekChapter: (Float) -> Unit,
    onContents: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
    onTts: () -> Unit,
    onCompanion: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ChapterHelm(
            chapterProgress = chapterProgress,
            palette = palette,
            onPrevChapter = onPrevChapter,
            onNextChapter = onNextChapter,
            onSeekChapter = onSeekChapter
        )

        Surface(
            shape = RoundedCornerShape(22.dp),
            color = palette.glass,
            border = BorderStroke(1.dp, palette.glassBorder),
            shadowElevation = 14.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ReaderDockButton(Icons.AutoMirrored.Outlined.MenuBook, "目录", palette, onContents)
                ReaderDockButton(Icons.Outlined.Bookmarks, "书签", palette, onBookmarks)
                ReaderDockButton(Icons.Outlined.FormatSize, "排版", palette, onSettings)
                ReaderDockButton(Icons.Outlined.Headphones, "听书", palette, onTts)
                ReaderDockButton(Icons.Outlined.AutoAwesome, "伴读", palette, onCompanion)
            }
        }
    }
}

/**
 * 章节导航条（demo `.helm`）：左右 36dp 玻璃圆球切章 + 中间本章进度细滑条胶囊。
 * 滑条语义为「本章进度」，拖动 = 章内跳页；全书百分比只保留在页脚。
 */
@Composable
private fun ChapterHelm(
    chapterProgress: Float,
    palette: ReaderPalette,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onSeekChapter: (Float) -> Unit
) {
    // 拖动时以本地值为准；松手提交后回落到外部状态。
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(chapterProgress) }
    val shown = if (dragging) dragValue else chapterProgress.coerceIn(0f, 1f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HelmOrbButton(
            icon = Icons.Outlined.ChevronLeft,
            contentDescription = "上一章",
            palette = palette,
            onClick = onPrevChapter
        )
        Surface(
            modifier = Modifier
                .weight(1f)
                .shadow(10.dp, RoundedCornerShape(15.dp), clip = false),
            shape = RoundedCornerShape(15.dp),
            color = palette.glass,
            border = BorderStroke(1.dp, palette.glassBorder)
        ) {
            Row(
                modifier = Modifier
                    .height(30.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChapterSliderTrack(
                    value = shown,
                    palette = palette,
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp),
                    onDrag = { fraction ->
                        dragging = true
                        dragValue = fraction
                    },
                    onCommit = { fraction ->
                        dragging = false
                        onSeekChapter(fraction)
                    }
                )
                Text(
                    text = "${(shown * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
        HelmOrbButton(
            icon = Icons.Outlined.ChevronRight,
            contentDescription = "下一章",
            palette = palette,
            onClick = onNextChapter
        )
    }
}

@Composable
private fun HelmOrbButton(
    icon: ImageVector,
    contentDescription: String,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        label = "helm-orb-scale"
    )
    Surface(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(10.dp, CircleShape, clip = false)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = CircleShape,
        color = palette.glassStrong,
        border = BorderStroke(1.dp, palette.glassBorder)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = palette.onBackground,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 3dp 轨道 + moss 填充 + 12dp 描边圆点 thumb 的自绘细滑条。 */
@Composable
private fun ChapterSliderTrack(
    value: Float,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit
) {
    var lastFraction by remember { mutableFloatStateOf(value) }
    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onCommit(fraction)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        lastFraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onDrag(lastFraction)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        lastFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onDrag(lastFraction)
                    },
                    onDragEnd = { onCommit(lastFraction) },
                    onDragCancel = { onCommit(lastFraction) }
                )
            }
    ) {
        val trackHeight = 3.dp.toPx()
        val centerY = size.height / 2
        val radius = trackHeight / 2
        drawRoundRect(
            color = palette.muted.copy(alpha = 0.22f),
            topLeft = Offset(0f, centerY - radius),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(radius)
        )
        val filled = size.width * value.coerceIn(0f, 1f)
        if (filled > 0f) {
            drawRoundRect(
                color = palette.accent,
                topLeft = Offset(0f, centerY - radius),
                size = Size(filled, trackHeight),
                cornerRadius = CornerRadius(radius)
            )
        }
        val thumbRadius = 6.dp.toPx()
        val thumbX = filled.coerceIn(thumbRadius, (size.width - thumbRadius).coerceAtLeast(thumbRadius))
        drawCircle(
            color = palette.glassStrong,
            radius = thumbRadius,
            center = Offset(thumbX, centerY)
        )
        drawCircle(
            color = palette.accent,
            radius = thumbRadius,
            center = Offset(thumbX, centerY),
            style = Stroke(2.dp.toPx())
        )
    }
}

@Composable
private fun ReaderDockButton(
    icon: ImageVector,
    label: String,
    palette: ReaderPalette,
    onClick: () -> Unit,
    selected: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier
            .width(62.dp)
            .background(
                color = if (selected) palette.accentContainer.copy(alpha = 0.72f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) palette.accent else palette.onBackground,
            modifier = Modifier.size(19.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) palette.accent else palette.muted
        )
    }
}
