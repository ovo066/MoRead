package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.ui.LocalBookOpener
import com.mozhi.reader.ui.bookCoverCapture
import com.mozhi.reader.ui.openBook
import com.mozhi.reader.ui.rememberAnimationsEnabled
import com.mozhi.reader.ui.rememberBookCoverCapture
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.mozhi.reader.ui.theme.moReadMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuExpandableItem
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.navSelectedColor
import com.mozhi.reader.ui.theme.onNavSelectedColor
import com.mozhi.reader.ui.theme.sealColor
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 问候语抬头 + 搜索胶囊 + 正在阅读卡（design/ui-adaptation-plan.md §2.1–2.3）。 */
@Composable
internal fun BookshelfHeader(
    recentBook: BookEntity?,
    recentChapterTitle: String,
    recentReadSpan: BookReadSpan?,
    searchQuery: String,
    onOpenBook: (Long) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        // 搜索时让位给结果：此刻用户找的是别的书，不是手头这本。
        if (searchQuery.isBlank()) {
            ReadingNowCard(
                recentBook = recentBook,
                recentChapterTitle = recentChapterTitle,
                recentReadSpan = recentReadSpan,
                onOpenBook = onOpenBook
            )
        }
    }
}

@Composable
internal fun GreetingHeader() {
    val greeting = remember {
        when (LocalTime.now().hour) {
            in 5..11 -> "早上好"
            in 12..17 -> "下午好"
            in 18..22 -> "晚上好"
            else -> "夜深了"
        }
    }
    val dateLine = remember {
        LocalDate.now().format(
            DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.SIMPLIFIED_CHINESE)
        ) + " · 宜读书"
    }
    Column {
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineLarge
        )
        Text(
            text = dateLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 正在阅读播放卡（demo `.now-card`）：封面 76×106 + 朱砂眉标 + serif 书名 +
 * 「作者 · 第 n 章 章名」+ 细进度条/百分比 + 右侧 52dp 墨色圆形继续钮。
 * 右上角带一抹 moss 径向墨韵。
 *
 * 整卡与圆钮都是「继续读正文」——听书统一在阅读页开启，首页不做听书入口。
 */
@Composable
private fun ReadingNowCard(
    recentBook: BookEntity?,
    recentChapterTitle: String,
    recentReadSpan: BookReadSpan?,
    onOpenBook: (Long) -> Unit
) {
    val seal = sealColor()
    val darkTheme = isDarkTheme()
    val accent = accentColor()

    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(28)),
        shadowElevation = 6.dp
    ) {
        Box {
            // 墨韵：右上角一抹极淡的强调色径向渐变（demo .now-card::after）。
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(moReadMetrics().radiusFor(28)))
            ) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accent.copy(alpha = if (darkTheme) 0.20f else 0.14f),
                            Color.Transparent
                        ),
                        center = Offset(size.width + 40.dp.toPx(), (-60).dp.toPx()),
                        radius = 190.dp.toPx()
                    ),
                    center = Offset(size.width + 40.dp.toPx(), (-60).dp.toPx()),
                    radius = 190.dp.toPx()
                )
            }
            if (recentBook == null) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(moReadMetrics().radiusFor(20)),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.padding(17.dp).size(30.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(start = 16.dp)) {
                        Text("从一本书开始", style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.shelf_import_formats_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }
            } else {
                val progress = readFraction(recentBook, recentReadSpan)
                // 进入阅读页时这本书从卡片里长成整页、封面翻开（翻书转场）。
                val coverCapture = rememberBookCoverCapture()
                val opener = LocalBookOpener.current
                val openScope = rememberCoroutineScope()
                val animationsEnabled = rememberAnimationsEnabled()
                val openRecent = {
                    opener.openBook(coverCapture, openScope, animationsEnabled) { onOpenBook(recentBook.id) }
                }
                Row(
                    modifier = Modifier
                        .clickable { openRecent() }
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactBookArtwork(
                        book = recentBook,
                        modifier = Modifier
                            .size(width = 76.dp, height = 106.dp)
                            .bookCoverCapture(coverCapture)
                    )
                    // now-meta：垂直居中的一列。
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 16.dp, end = 12.dp)
                    ) {
                        Text(
                            text = "正在阅读",
                            style = MaterialTheme.typography.labelSmall,
                            color = seal,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp
                        )
                        Text(
                            text = recentBook.title,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 7.dp)
                        )
                        Text(
                            text = buildString {
                                append(recentBook.author.ifBlank { "未知作者" })
                                append(" · 第 ${recentBook.lastReadChapterIndex + 1} 章")
                                if (recentChapterTitle.isNotBlank()) {
                                    append(" ")
                                    append(recentChapterTitle)
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp)
                        )
                        Row(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                gapSize = 0.dp,
                                drawStopIndicator = {}
                            )
                            Text(
                                text = "${readPercent(progress)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                    // go-read：墨色（夜间 moss）实心圆形「继续读」钮。
                    Surface(
                        onClick = { openRecent() },
                        shape = CircleShape,
                        color = if (darkTheme) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            navSelectedColor()
                        },
                        contentColor = if (darkTheme) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            onNavSelectedColor()
                        },
                        shadowElevation = 6.dp,
                        modifier = Modifier.size(52.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "继续阅读",
                                modifier = Modifier.size(24.dp).offset(x = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShelfHeaderActions(
    state: BookshelfUiState,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagMatchMode: (TagMatchMode) -> Unit,
    onSetReadingOrderAffectsShelf: (Boolean) -> Unit,
    onClearFilter: () -> Unit,
    onStartSelection: () -> Unit,
    onOpenShelfTags: () -> Unit,
    onImport: () -> Unit
) {
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var availableMenuHeight by remember { mutableStateOf(SHELF_MENU_MAX_HEIGHT) }
    val density = LocalDensity.current
    val localView = LocalView.current
    val filter = state.filter
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val remainingPx = localView.height - coordinates.boundsInWindow().bottom
                    availableMenuHeight = with(density) {
                        remainingPx.coerceAtLeast(0f).toDp() - 14.dp
                    }.coerceIn(SHELF_MENU_MIN_HEIGHT, SHELF_MENU_MAX_HEIGHT)
                }
            ) {
                FrostedSurface(shape = CircleShape, shadowElevation = 3.dp) {
                    Box {
                        IconButton(
                            onClick = { viewMenuExpanded = true },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Tune,
                                contentDescription = "视图与筛选",
                                tint = if (filter.isActive) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    LocalContentColor.current
                                }
                            )
                        }
                        // 筛选生效时给个小圆点，免得用户忘了自己开着筛选还以为书丢了。
                        if (filter.isActive) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(top = 11.dp, end = 11.dp)
                                    .size(7.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
                    }
                }
                ShelfViewMenu(
                    expanded = viewMenuExpanded,
                    onDismiss = { viewMenuExpanded = false },
                    state = state,
                    maxHeight = availableMenuHeight,
                    onSetLayout = onSetLayout,
                    onSetGridColumns = onSetGridColumns,
                    onSetReadStateFilter = onSetReadStateFilter,
                    onSetTagMatchMode = onSetTagMatchMode,
                    onSetReadingOrderAffectsShelf = onSetReadingOrderAffectsShelf,
                    onClearFilter = onClearFilter,
                    onStartSelection = onStartSelection,
                    onOpenShelfTags = onOpenShelfTags
                )
            }
            Surface(
                onClick = onImport,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.primary,
                shadowElevation = 3.dp,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "导入书籍",
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
}

@Composable
internal fun LibraryToolbar(
    bookCount: Int,
    searching: Boolean,
    state: BookshelfUiState,
    onSelectGroup: (Long?, Boolean) -> Unit,
    onToggleTagFilter: (Long) -> Unit,
    onOpenShelfGroups: () -> Unit,
) {
    var groupMenuExpanded by remember { mutableStateOf(false) }
    val filter = state.filter
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Column(modifier = Modifier.clickable { groupMenuExpanded = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.selectedGroupName, style = MaterialTheme.typography.headlineSmall)
                        Icon(Icons.Outlined.ExpandMore, contentDescription = "选择分组")
                    }
                    Text(
                        text = buildString {
                            if (searching) append("找到 $bookCount 本") else append("$bookCount 本")
                            filter.readState?.let { append(" · ").append(it.label()) }
                            if (!searching && !filter.isActive) {
                                append(
                                    if (state.readingOrderAffectsShelf) " · 阅读后前移"
                                    else " · 手动排序"
                                )
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                ShelfGroupDropdown(
                    expanded = groupMenuExpanded,
                    onDismiss = { groupMenuExpanded = false },
                    groups = state.groups,
                    groupCounts = state.groupCounts,
                    selectedGroupId = filter.groupId,
                    ungroupedOnly = filter.ungroupedOnly,
                    onSelect = onSelectGroup,
                    onCreate = { groupMenuExpanded = false; onOpenShelfGroups() },
                    onManage = { groupMenuExpanded = false; onOpenShelfGroups() }
                )
            }
        }
        ShelfTagIndex(
            tags = state.tags,
            selectedTagIds = filter.tagIds,
            onToggle = onToggleTagFilter,
            onClear = { filter.tagIds.forEach(onToggleTagFilter) },
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/**
 * 视图菜单：布局 / 阅读状态 / 标签三组各占一行，右侧显示当前取值，点开才就地展开。
 * 全摊开会长到半屏，和触发它的 44dp 圆按钮完全不成比例。
 */
@Composable
private fun ShelfViewMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    state: BookshelfUiState,
    maxHeight: androidx.compose.ui.unit.Dp,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagMatchMode: (TagMatchMode) -> Unit,
    onSetReadingOrderAffectsShelf: (Boolean) -> Unit,
    onClearFilter: () -> Unit,
    onStartSelection: () -> Unit,
    onOpenShelfTags: () -> Unit
) {
    // 手风琴：同时只展开一组，菜单高度始终可控。菜单关掉后回到全收起。
    var section by remember(expanded) { mutableStateOf<ShelfMenuSection?>(null) }
    fun toggle(target: ShelfMenuSection) {
        section = if (section == target) null else target
    }

    MoReadStableDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        width = SHELF_MENU_WIDTH,
        maxHeight = maxHeight
    ) {
        MoReadMenuExpandableItem(
            text = "布局",
            valueText = if (state.layout == ShelfLayout.GRID) "网格" else "列表",
            icon = Icons.Outlined.GridView,
            expanded = section == ShelfMenuSection.LAYOUT,
            onToggle = { toggle(ShelfMenuSection.LAYOUT) }
        ) {
            MoReadMenuItem(
                text = "网格",
                indent = MENU_INDENT,
                selected = state.layout == ShelfLayout.GRID,
                onClick = { onSetLayout(ShelfLayout.GRID) }
            )
            MoReadMenuItem(
                text = "列表",
                indent = MENU_INDENT,
                selected = state.layout == ShelfLayout.LIST,
                onClick = { onSetLayout(ShelfLayout.LIST) }
            )
        }

        // 只对网格有意义；墨水屏、平板用户常想要比自动更密或更疏的封面墙（issue #16）。
        if (state.layout == ShelfLayout.GRID) {
            MoReadMenuExpandableItem(
                text = stringResource(R.string.shelf_grid_columns),
                valueText = if (state.gridColumns == 0) {
                    stringResource(R.string.shelf_grid_columns_auto)
                } else {
                    stringResource(R.string.shelf_grid_columns_value, state.gridColumns)
                },
                icon = Icons.Outlined.ViewColumn,
                expanded = section == ShelfMenuSection.COLUMNS,
                onToggle = { toggle(ShelfMenuSection.COLUMNS) }
            ) {
                MoReadMenuItem(
                    text = stringResource(R.string.shelf_grid_columns_auto_option),
                    indent = MENU_INDENT,
                    selected = state.gridColumns == 0,
                    onClick = { onSetGridColumns(0) }
                )
                SHELF_GRID_COLUMN_CHOICES.forEach { columns ->
                    MoReadMenuItem(
                        text = stringResource(R.string.shelf_grid_columns_value, columns),
                        indent = MENU_INDENT,
                        selected = state.gridColumns == columns,
                        onClick = { onSetGridColumns(columns) }
                    )
                }
            }
        }

        MoReadMenuExpandableItem(
            text = "阅读状态",
            valueText = state.filter.readState?.label() ?: "全部",
            icon = Icons.Outlined.TaskAlt,
            expanded = section == ShelfMenuSection.READ_STATE,
            onToggle = { toggle(ShelfMenuSection.READ_STATE) }
        ) {
            MoReadMenuItem(
                text = "全部",
                indent = MENU_INDENT,
                selected = state.filter.readState == null,
                onClick = { onSetReadStateFilter(null) }
            )
            BookReadState.entries.forEach { readState ->
                MoReadMenuItem(
                    text = readState.label(),
                    indent = MENU_INDENT,
                    selected = state.filter.readState == readState,
                    onClick = { onSetReadStateFilter(readState) }
                )
            }
        }

        MoReadMenuItem(
            text = "阅读后自动前移",
            icon = Icons.Outlined.AutoStories,
            selected = state.readingOrderAffectsShelf,
            onClick = {
                onSetReadingOrderAffectsShelf(!state.readingOrderAffectsShelf)
            }
        )

        if (state.filter.tagIds.isNotEmpty()) {
            MoReadMenuExpandableItem(
                text = "标签匹配",
                valueText = if (state.filter.matchMode == TagMatchMode.ANY) "任一" else "全部",
                icon = Icons.Outlined.Sell,
                expanded = section == ShelfMenuSection.TAG,
                onToggle = { toggle(ShelfMenuSection.TAG) }
            ) {
                MoReadMenuItem(
                    text = "任一标签",
                    indent = MENU_INDENT,
                    selected = state.filter.matchMode == TagMatchMode.ANY,
                    onClick = { onSetTagMatchMode(TagMatchMode.ANY) }
                )
                MoReadMenuItem(
                    text = "全部标签",
                    indent = MENU_INDENT,
                    selected = state.filter.matchMode == TagMatchMode.ALL,
                    onClick = { onSetTagMatchMode(TagMatchMode.ALL) }
                )
            }
        }

        MoReadMenuDivider()
        MoReadMenuItem(
            text = "多选书籍",
            icon = Icons.Outlined.Checklist,
            onClick = { onDismiss(); onStartSelection() }
        )
        MoReadMenuItem(
            text = "管理标签",
            icon = Icons.Outlined.Sell,
            onClick = { onDismiss(); onOpenShelfTags() }
        )

        if (state.filter.isActive) {
            MoReadMenuDivider()
            MoReadMenuItem(
                text = "清除筛选",
                icon = Icons.Outlined.FilterAltOff,
                onClick = {
                    onClearFilter()
                    onDismiss()
                }
            )
        }
    }
}

private enum class ShelfMenuSection { LAYOUT, COLUMNS, READ_STATE, TAG }

private val SHELF_GRID_COLUMN_CHOICES = 3..6

private val MENU_INDENT = 22.dp

private val SHELF_MENU_WIDTH = 244.dp

private val SHELF_MENU_MIN_HEIGHT = 120.dp

private val SHELF_MENU_MAX_HEIGHT = 390.dp
