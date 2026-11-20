package com.mozhi.reader.feature.bookshelf

import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import com.mozhi.reader.ui.theme.moReadMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mozhi.reader.ui.components.MoReadSearchCapsule
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LocalPinnableContainer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent
import com.mozhi.reader.ui.MoReadLayoutPolicy
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.NavStyle
import com.mozhi.reader.ui.theme.navStyle

@Composable
internal fun BookGrid(
    entries: List<ShelfEntry>,
    bookCount: Int,
    state: BookshelfUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onBookEntryClick: (ShelfEntry.Book) -> Unit,
    onCollectionEntryClick: (ShelfEntry.Collection) -> Unit,
    onLongPress: (BookEntity, Rect) -> Unit,
    collectionDragState: ShelfCollectionDragState,
    onShelfDrop: (BookEntity, ShelfDrop) -> Unit,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSelectGroup: (Long?, Boolean) -> Unit,
    onToggleTagFilter: (Long) -> Unit,
    onSetTagMatchMode: (TagMatchMode) -> Unit,
    onSetReadingOrderAffectsShelf: (Boolean) -> Unit,
    onClearFilter: () -> Unit,
    onStartSelection: () -> Unit,
    onOpenShelfGroups: () -> Unit,
    onOpenShelfTags: () -> Unit,
    onImport: () -> Unit
) {
    val gridState = rememberLazyGridState()
    val windowWidth = rememberMoReadWindowWidth()
    val tablet = windowWidth != MoReadWindowWidth.COMPACT
    val horizontalPadding = shelfGridHorizontalPaddingDp(windowWidth).dp
    val dockSearch = !tablet && navStyle() != NavStyle.BAR
    val searchFloating by remember(tablet, searchQuery.isBlank()) {
        derivedStateOf { gridState.firstVisibleItemIndex >= if (tablet && searchQuery.isBlank()) 2 else 1 }
    }
    ShelfAutoScrollEffect(collectionDragState, gridState) {
        gridState.requestScrollToItem(
            gridState.firstVisibleItemIndex,
            gridState.firstVisibleItemScrollOffset
        )
    }
    LazyVerticalGrid(
        columns = if (tablet) TabletShelfGridCells(state.gridColumns) else ShelfGridCells(state.gridColumns),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .background(if (tablet) MaterialTheme.colorScheme.background else Color.Transparent)
            .onGloballyPositioned { collectionDragState.setViewport(it.boundsInRoot()) }.testTag("shelf-scroll"),
        contentPadding = PaddingValues(
            start = horizontalPadding, top = if (tablet) 30.dp else 18.dp, end = horizontalPadding,
            bottom = MoReadLayoutPolicy.rootBottomPaddingDp(windowWidth).dp
        ),
        horizontalArrangement = Arrangement.spacedBy(if (tablet) 24.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (tablet) 26.dp else 20.dp)
    ) {
        item(key = "shelf-top", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (tablet) TabletLibraryTitle(state) else GreetingHeader() }
                ShelfHeaderActions(state, onSetLayout, onSetGridColumns, onSetReadStateFilter, onSetTagMatchMode,
                    onSetReadingOrderAffectsShelf, onClearFilter, onStartSelection, onOpenShelfTags, onImport)
            }
        }
        if (tablet && searchQuery.isBlank()) {
            item(key = "shelf-continue", span = { GridItemSpan(maxLineSpan) }) {
                TabletContinueReading(state, onOpenBook)
            }
        }
        // 手机悬浮舱下搜索在右下角的搜索舱里（MoReadApp）；平板与通栏导航仍吸顶一枚搜索胶囊。
        if (!dockSearch) stickyHeader(key = "shelf-search-header") {
            // Only the capsule is painted: books scroll behind its rounded edges and shadow.
            if (tablet) TabletLibraryToolbar(state, bookCount, searchQuery, onSearchChange, onClearFilter, floating = searchFloating)
            else Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { MoReadSearchCapsule(searchQuery, onSearchChange, searchFloating, testTagPrefix = "shelf") }
        }
        if (!tablet) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BookshelfHeader(
                recentBook = state.recentBook,
                recentChapterTitle = state.recentChapterTitle,
                recentReadSpan = state.recentBook?.let { state.readSpans[it.id] },
                searchQuery = searchQuery,
                onOpenBook = onOpenBook
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryToolbar(
                bookCount = bookCount,
                searching = searchQuery.isNotBlank(),
                state = state,
                onSelectGroup = onSelectGroup,
                onToggleTagFilter = onToggleTagFilter,
                onOpenShelfGroups = onOpenShelfGroups,
            )
        }
        }
        if (entries.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (state.totalBooks == 0) EmptyBookshelf(onImport) else NoShelfResults(query = searchQuery, filter = state.filter)
            }
        }
        gridItems(entries, key = ShelfEntry::key) { entry ->
            when (entry) {
                is ShelfEntry.Book -> GridBookItem(
                    entry = entry,
                    readSpan = state.readSpans[entry.book.id],
                    selected = entry.book.id in state.selectedBookIds,
                    selectionMode = state.isSelectionMode,
                    onOpen = { onBookEntryClick(entry) },
                    onLongPress = { bounds -> onLongPress(entry.book, bounds) },
                    collectionDragState = collectionDragState,
                    onShelfDrop = onShelfDrop,
                    reorderActions = shelfReorderActions(entries, entry.book, onShelfDrop)
                )
                is ShelfEntry.Collection -> GridCollectionItem(
                    entry = entry,
                    selected = entry.visibleBookIds.all(state.selectedBookIds::contains),
                    selectionMode = state.isSelectionMode,
                    onOpen = { onCollectionEntryClick(entry) },
                    collectionDragState = collectionDragState
                )
            }
        }
    }
}

@Composable
internal fun BookList(
    entries: List<ShelfEntry>,
    bookCount: Int,
    state: BookshelfUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onBookEntryClick: (ShelfEntry.Book) -> Unit,
    onCollectionEntryClick: (ShelfEntry.Collection) -> Unit,
    onLongPress: (BookEntity, Rect) -> Unit,
    collectionDragState: ShelfCollectionDragState,
    onShelfDrop: (BookEntity, ShelfDrop) -> Unit,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetGridColumns: (Int) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSelectGroup: (Long?, Boolean) -> Unit,
    onToggleTagFilter: (Long) -> Unit,
    onSetTagMatchMode: (TagMatchMode) -> Unit,
    onSetReadingOrderAffectsShelf: (Boolean) -> Unit,
    onClearFilter: () -> Unit,
    onStartSelection: () -> Unit,
    onOpenShelfGroups: () -> Unit,
    onOpenShelfTags: () -> Unit,
    onImport: () -> Unit
) {
    val listState = rememberLazyListState()
    val tablet = rememberMoReadWindowWidth() != MoReadWindowWidth.COMPACT
    val dockSearch = !tablet && navStyle() != NavStyle.BAR
    val searchFloating by remember(tablet, searchQuery.isBlank()) {
        derivedStateOf { listState.firstVisibleItemIndex >= if (tablet && searchQuery.isBlank()) 2 else 1 }
    }
    ShelfAutoScrollEffect(collectionDragState, listState) {
        listState.requestScrollToItem(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .background(if (tablet) MaterialTheme.colorScheme.background else Color.Transparent)
            .onGloballyPositioned { collectionDragState.setViewport(it.boundsInRoot()) }.testTag("shelf-scroll"),
        contentPadding = PaddingValues(
            start = if (tablet) 48.dp else 20.dp, top = if (tablet) 30.dp else 18.dp, end = if (tablet) 48.dp else 20.dp,
            bottom = MoReadLayoutPolicy.rootBottomPaddingDp(rememberMoReadWindowWidth()).dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "shelf-top") {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { if (tablet) TabletLibraryTitle(state) else GreetingHeader() }
                ShelfHeaderActions(state, onSetLayout, onSetGridColumns, onSetReadStateFilter, onSetTagMatchMode,
                    onSetReadingOrderAffectsShelf, onClearFilter, onStartSelection, onOpenShelfTags, onImport)
            }
        }
        if (tablet && searchQuery.isBlank()) {
            item(key = "shelf-continue") { TabletContinueReading(state, onOpenBook) }
        }
        if (!dockSearch) stickyHeader(key = "shelf-search-header") {
            if (tablet) TabletLibraryToolbar(state, bookCount, searchQuery, onSearchChange, onClearFilter, floating = searchFloating)
            else Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { MoReadSearchCapsule(searchQuery, onSearchChange, searchFloating, testTagPrefix = "shelf") }
        }
        if (!tablet) {
        item {
            BookshelfHeader(
                recentBook = state.recentBook,
                recentChapterTitle = state.recentChapterTitle,
                recentReadSpan = state.recentBook?.let { state.readSpans[it.id] },
                searchQuery = searchQuery,
                onOpenBook = onOpenBook
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            LibraryToolbar(
                bookCount = bookCount,
                searching = searchQuery.isNotBlank(),
                state = state,
                onSelectGroup = onSelectGroup,
                onToggleTagFilter = onToggleTagFilter,
                onOpenShelfGroups = onOpenShelfGroups,
            )
        }
        }
        if (entries.isEmpty()) {
            item { if (state.totalBooks == 0) EmptyBookshelf(onImport) else NoShelfResults(query = searchQuery, filter = state.filter) }
        }
        listItems(entries, key = ShelfEntry::key) { entry ->
            when (entry) {
                is ShelfEntry.Book -> ListBookItem(
                    entry = entry,
                    readSpan = state.readSpans[entry.book.id],
                    selected = entry.book.id in state.selectedBookIds,
                    selectionMode = state.isSelectionMode,
                    onOpen = { onBookEntryClick(entry) },
                    onLongPress = { bounds -> onLongPress(entry.book, bounds) },
                    collectionDragState = collectionDragState,
                    onShelfDrop = onShelfDrop,
                    reorderActions = shelfReorderActions(entries, entry.book, onShelfDrop)
                )
                is ShelfEntry.Collection -> ListCollectionItem(
                    entry = entry,
                    selected = entry.visibleBookIds.all(state.selectedBookIds::contains),
                    selectionMode = state.isSelectionMode,
                    onOpen = { onCollectionEntryClick(entry) },
                    collectionDragState = collectionDragState
                )
            }
        }
    }
}

@Composable
private fun GridBookItem(
    entry: ShelfEntry.Book,
    readSpan: BookReadSpan?,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: (Rect) -> Unit,
    collectionDragState: ShelfCollectionDragState,
    onShelfDrop: (BookEntity, ShelfDrop) -> Unit,
    reorderActions: List<CustomAccessibilityAction>
) {
    val book = entry.book
    val pinnableContainer = LocalPinnableContainer.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var coverBounds by remember { mutableStateOf(Rect.Zero) }
    val target = remember(entry.key) { entry.dropTarget() }
    val registrationOwner = remember { Any() }
    DisposableEffect(entry.key) {
        onDispose { collectionDragState.unregister(entry.key, registrationOwner) }
    }
    Column(
        modifier = Modifier
            .graphicsLayer {
                alpha = if (collectionDragState.sourceBook?.id == book.id) {
                    0f
                } else if (selectionMode && !selected) {
                    0.55f
                } else {
                    1f
                }
            }
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                collectionDragState.register(target, bounds, registrationOwner)
            }
            .then(
                if (collectionDragState.activeDrop?.target == target) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(moReadMetrics().radiusFor(12))
                ) else Modifier
            )
            .clickable(onClick = onOpen)
            .collectionDragSource(
                book = book,
                bounds = { bounds },
                coverBounds = { coverBounds },
                horizontal = true,
                allowMerge = true,
                enabled = !selectionMode,
                pinnableContainer = pinnableContainer,
                state = collectionDragState,
                onDrop = onShelfDrop,
                onLongPressOnly = onLongPress,
                reorderActions = reorderActions
            )
    ) {
        Box {
            BookCover(
                book = book,
                readSpan = readSpan,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.69f)
                    .onGloballyPositioned {
                        coverBounds = it.boundsInRoot()
                    }
            )
            if (selectionMode) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = if (selected) "已选择" else "未选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp).size(24.dp)
                )
            }
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp)
        )
        Text(
            text = book.author.ifBlank { "未知作者" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun ListBookItem(
    entry: ShelfEntry.Book,
    readSpan: BookReadSpan?,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onLongPress: (Rect) -> Unit,
    collectionDragState: ShelfCollectionDragState,
    onShelfDrop: (BookEntity, ShelfDrop) -> Unit,
    reorderActions: List<CustomAccessibilityAction>
) {
    val book = entry.book
    val tablet = rememberMoReadWindowWidth() != MoReadWindowWidth.COMPACT
    val pinnableContainer = LocalPinnableContainer.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    var coverBounds by remember { mutableStateOf(Rect.Zero) }
    val progress = readFraction(book, readSpan)
    val target = remember(entry.key) { entry.dropTarget() }
    val registrationOwner = remember { Any() }
    DisposableEffect(entry.key) {
        onDispose { collectionDragState.unregister(entry.key, registrationOwner) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = if (collectionDragState.sourceBook?.id == book.id) {
                    0f
                } else if (selectionMode && !selected) {
                    0.55f
                } else {
                    1f
                }
            }
            .onGloballyPositioned {
                bounds = it.boundsInRoot()
                collectionDragState.register(target, bounds, registrationOwner)
            }
            .then(
                if (collectionDragState.activeDrop?.target == target) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(4.dp)
                ) else Modifier
            )
            .clickable(onClick = onOpen)
            .collectionDragSource(
                book = book,
                bounds = { bounds },
                coverBounds = { coverBounds },
                horizontal = false,
                allowMerge = true,
                enabled = !selectionMode,
                pinnableContainer = pinnableContainer,
                state = collectionDragState,
                onDrop = onShelfDrop,
                onLongPressOnly = onLongPress,
                reorderActions = reorderActions
            )
    ) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactBookArtwork(
                book = book,
                modifier = Modifier
                    .size(width = 68.dp, height = 96.dp)
                    .onGloballyPositioned {
                        coverBounds = it.boundsInRoot()
                    }
            )
            if (selectionMode) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = if (selected) "已选择" else "未选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (book.isPinned) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(13.dp)
                                .padding(end = 1.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    book.author.ifBlank { "未知作者" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (!tablet) {
                Text(
                    progressText(book, readSpan),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 9.dp)
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 9.dp)
                        .height(4.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    gapSize = 0.dp,
                    drawStopIndicator = {}
                )
                }
            }
            if (tablet) {
                Column(Modifier.width(180.dp).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(progressText(book, readSpan), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(start = 82.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
    }
}

/** 书架非空但过滤后为零：搜索与筛选都可能造成，得说清是哪一种。 */
@Composable
private fun NoShelfResults(query: String, filter: ShelfFilter) {
    val searching = query.isNotBlank()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (searching) Icons.Outlined.Search else Icons.Outlined.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp)
        )
        Text(
            text = when {
                searching -> "没有匹配「${query.trim()}」的书"
                filter.tagIds.isNotEmpty() -> "没有同时符合当前标签条件的书"
                filter.readState != null -> "没有${filter.readState.label()}的书"
                else -> "书架是空的"
            },
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = if (searching) {
                "试试书名、作者或标签的其他关键词"
            } else {
                "在右上角的视图菜单里可以改回「全部」"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun EmptyBookshelf(onImport: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(32)),
        shadowElevation = 10.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 26.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(width = 166.dp, height = 144.dp)) {
                Surface(
                    modifier = Modifier
                        .size(width = 72.dp, height = 106.dp)
                        .align(Alignment.Center)
                        .graphicsLayer {
                            rotationZ = -13f
                            translationX = -34f
                            translationY = 8f
                        },
                    shape = RoundedCornerShape(moReadMetrics().radiusFor(14)),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shadowElevation = 7.dp
                ) {}
                Surface(
                    modifier = Modifier
                        .size(width = 74.dp, height = 110.dp)
                        .align(Alignment.Center)
                        .graphicsLayer {
                            rotationZ = 12f
                            translationX = 34f
                            translationY = 10f
                        },
                    shape = RoundedCornerShape(moReadMetrics().radiusFor(14)),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shadowElevation = 7.dp
                ) {}
                Surface(
                    modifier = Modifier
                        .size(width = 82.dp, height = 120.dp)
                        .align(Alignment.Center),
                    shape = RoundedCornerShape(moReadMetrics().radiusFor(16)),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 12.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.AutoStories,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }
            }
            Text(
                text = "你的书架正在等第一本书",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                text = stringResource(R.string.shelf_empty_formats_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(top = 10.dp)
            )
            Button(
                onClick = onImport,
                shape = MoReadTokens.CapsuleShape,
                modifier = Modifier.padding(top = 22.dp)
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("导入第一本书", modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun progressText(book: BookEntity, span: BookReadSpan?): String = if (book.lastReadAt == 0L) {
    "未开始 · ${book.totalChapters} 章"
} else {
    val percent = readPercent(readFraction(book, span))
    "$percent% · 第 ${book.lastReadChapterIndex + 1}/${book.totalChapters} 章"
}
