package com.mozhi.reader.feature.bookshelf

import com.mozhi.reader.ui.components.MoReadBottomSheet
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import com.mozhi.reader.ui.theme.moReadMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.R
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.MoReadLayoutPolicy
import com.mozhi.reader.ui.MoReadWindowWidth
import com.mozhi.reader.ui.rememberMoReadWindowWidth
import com.mozhi.reader.ui.components.FrostedSurface
import kotlinx.coroutines.launch

private data class CollectionCreateRequest(
    val bookIds: Set<Long>,
    val shelfOrder: List<Long>? = null
)

@Composable
fun BookshelfScreen(
    contentPadding: PaddingValues,
    externalImportUri: Uri? = null,
    onExternalImportConsumed: () -> Unit = {},
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (bookId: Long, action: String?) -> Unit,
    onOpenImportPreview: (String) -> Unit,
    onOpenFolderPicker: (Uri) -> Unit = {},
    onOpenLanTransfer: () -> Unit = {},
    onOpenShelfGroups: () -> Unit = {},
    onOpenShelfTags: () -> Unit = {},
    onSelectionModeChanged: (Boolean) -> Unit = {},
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val tablet = rememberMoReadWindowWidth() != MoReadWindowWidth.COMPACT
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var duplicateImport by remember { mutableStateOf<com.mozhi.reader.core.importer.PreparedImport.Duplicate?>(null) }
    var longPressTarget by remember { mutableStateOf<BookLongPressTarget?>(null) }
    var showTagPicker by remember { mutableStateOf(false) }
    var showGroupPicker by remember { mutableStateOf(false) }
    var openCollectionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showCollectionPicker by remember { mutableStateOf(false) }
    var collectionNameRequest by remember { mutableStateOf<CollectionCreateRequest?>(null) }
    var renameCollection by remember { mutableStateOf<BookCollectionEntity?>(null) }
    var dissolveCollection by remember { mutableStateOf<BookCollectionEntity?>(null) }
    var deleteSelected by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val collectionDragState = remember { ShelfCollectionDragState() }
    val context = LocalContext.current
    LaunchedEffect(state.isSelectionMode) {
        onSelectionModeChanged(state.isSelectionMode)
    }
    DisposableEffect(Unit) {
        onDispose { onSelectionModeChanged(false) }
    }
    // 批量任务可能跨进程恢复，必须拿可持久化的文档授权，不能依赖 Activity 的临时授权。
    var importMenuVisible by remember { mutableStateOf(false) }
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        // 单本 TXT 预览分章，EPUB / Markdown / HTML / MOBI 直接入库；多本统一后台导入。
        when {
            uris.isEmpty() -> Unit
            uris.size == 1 -> viewModel.importDocument(uris.first())
            else -> scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    uris.forEach { uri ->
                        runCatching {
                            context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    viewModel.importBatch(uris)
                }
            }
        }
    }
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            onOpenFolderPicker(uri)
        }
    }
    LaunchedEffect(externalImportUri) {
        externalImportUri?.let { uri ->
            viewModel.importDocument(uri)
            onExternalImportConsumed()
        }
    }

    // 通知权限只为导入进度条服务，批准与否都要继续；先问一次再拉起选择器。
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        importMenuVisible = true
    }
    val requestImport: () -> Unit = {
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            importMenuVisible = true
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookshelfEvent.OpenImportPreview -> onOpenImportPreview(event.sessionId)
                is BookshelfEvent.OpenBook -> onOpenBook(event.bookId)
                is BookshelfEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
                is BookshelfEvent.ShowLocalizedMessage -> snackbarHostState.showSnackbar(event.message.resolve(context.resources))
                is BookshelfEvent.DuplicateFound -> duplicateImport = event.duplicate
            }
        }
    }
    duplicateImport?.let { duplicate ->
        DuplicateImportDialog(
            duplicate = duplicate,
            onOpenExisting = { duplicateImport = null; onOpenBook(duplicate.bookId) },
            onImportAnyway = { duplicateImport = null; viewModel.importDocument(duplicate.uri, allowDuplicate = true) },
            onDismiss = { duplicateImport = null }
        )
    }

    // 搜索是叠在筛选之上的第二层过滤；搜索词由根导航的搜索舱写入 ViewModel，过滤仍在 UI 层算。
    val filteredBooks = remember(
        state.books,
        state.collections,
        state.tagRefs,
        state.tags,
        searchQuery
    ) {
        val query = searchQuery.trim()
        val matchingCollections = state.collections
            .filter { it.name.contains(query, ignoreCase = true) }
            .mapTo(mutableSetOf(), BookCollectionEntity::id)
        if (query.isEmpty()) {
            state.books
        } else {
            state.books.filter { book ->
                book.collectionId in matchingCollections ||
                    book.title.contains(query, ignoreCase = true) ||
                    book.author.contains(query, ignoreCase = true) ||
                    state.tags.any { tag ->
                        tag.name.contains(query, ignoreCase = true) &&
                            tag.id in state.tagIdsFor(book.id)
                    }
            }
        }
    }
    val shelfEntries = remember(filteredBooks, state.allBooks, state.collections) {
        buildShelfEntries(filteredBooks, state.allBooks, state.collections)
    }
    val previewEntries = remember(
        shelfEntries,
        collectionDragState.sourceBook?.id,
        collectionDragState.activeDrop
    ) {
        previewShelfEntries(
            shelfEntries,
            collectionDragState.sourceBook?.id,
            collectionDragState.activeDrop
        )
    }
    val visibleBookIds = shelfEntries.flatMapTo(linkedSetOf()) { it.visibleBookIds }
    val onBookEntryClick: (ShelfEntry.Book) -> Unit = { entry ->
        if (state.isSelectionMode) viewModel.toggleSelection(entry.book.id)
        else onOpenBookDetail(entry.book.id, null)
    }
    val onCollectionEntryClick: (ShelfEntry.Collection) -> Unit = { entry ->
        if (state.isSelectionMode) viewModel.toggleSelection(entry.visibleBookIds)
        else openCollectionId = entry.collection.id
    }
    val onShelfDrop: (BookEntity, ShelfDrop) -> Unit = dropBook@{ source, drop ->
        if (drop.placement != ShelfDropPlacement.MERGE && shelfDropCrossesPinnedBoundary(
                shelfEntries, source.id, drop.target.entryKey,
                after = drop.placement == ShelfDropPlacement.AFTER
            )
        ) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.shelf_pinned_boundary))
            }
            return@dropBook
        }
        val reordered = reorderShelfEntries(
            shelfEntries,
            sourceBookId = source.id,
            targetKey = drop.target.entryKey,
            after = drop.placement != ShelfDropPlacement.BEFORE
        )
        val bookOrder = reordered.flatMap(ShelfEntry::bookIds)
        when (drop.placement) {
            ShelfDropPlacement.BEFORE, ShelfDropPlacement.AFTER ->
                viewModel.saveShelfOrder(bookOrder)
            ShelfDropPlacement.MERGE -> when {
                drop.target.collectionId != null -> {
                    viewModel.saveShelfOrder(bookOrder)
                    viewModel.addBooksToCollection(drop.target.collectionId, setOf(source.id))
                }
                drop.target.bookId != null -> collectionNameRequest = CollectionCreateRequest(
                    bookIds = setOf(source.id, drop.target.bookId),
                    shelfOrder = bookOrder
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .onGloballyPositioned { rootOrigin = it.boundsInRoot().topLeft }
    ) {
        // 长按浮层出现时内容整体虚化（API 31+；以下靠更重的压暗兜底）。
        val blurRadius by animateDpAsState(
            targetValue = if (longPressTarget != null) 18.dp else 0.dp,
            animationSpec = tween(durationMillis = 160),
            label = "shelf-blur"
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = if (tablet) 1600.dp else MoReadLayoutPolicy.LibraryMaxWidthDp.dp)
                .fillMaxSize()
                .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
                .padding(contentPadding)
        ) {
            run {
                val onLongPress: (BookEntity, Rect) -> Unit = { book, bounds ->
                    if (state.isSelectionMode) viewModel.toggleSelection(book.id)
                    else longPressTarget = BookLongPressTarget(book, bounds)
                }
                Crossfade(
                    targetState = state.layout,
                    animationSpec = tween(durationMillis = 260),
                    label = "bookshelf-layout"
                ) { layout ->
                    when (layout) {
                        ShelfLayout.GRID -> BookGrid(
                            entries = previewEntries,
                            bookCount = filteredBooks.size,
                            state = state,
                            searchQuery = searchQuery,
                            onSearchChange = viewModel::setSearchQuery,
                            onOpenBook = onOpenBook,
                            onBookEntryClick = onBookEntryClick,
                            onCollectionEntryClick = onCollectionEntryClick,
                            onLongPress = onLongPress,
                            collectionDragState = collectionDragState,
                            onShelfDrop = onShelfDrop,
                            onSetLayout = viewModel::setLayout,
                            onSetGridColumns = viewModel::setGridColumns,
                            onSetReadStateFilter = viewModel::setReadStateFilter,
                            onSelectGroup = viewModel::selectGroup,
                            onToggleTagFilter = viewModel::toggleTagFilter,
                            onSetTagMatchMode = viewModel::setTagMatchMode,
                            onSetReadingOrderAffectsShelf =
                                viewModel::setReadingOrderAffectsShelf,
                            onClearFilter = viewModel::clearFilter,
                            onStartSelection = { viewModel.enterSelection() },
                            onOpenShelfGroups = onOpenShelfGroups,
                            onOpenShelfTags = onOpenShelfTags,
                            onImport = requestImport
                        )
                        ShelfLayout.LIST -> BookList(
                            entries = previewEntries,
                            bookCount = filteredBooks.size,
                            state = state,
                            searchQuery = searchQuery,
                            onSearchChange = viewModel::setSearchQuery,
                            onOpenBook = onOpenBook,
                            onBookEntryClick = onBookEntryClick,
                            onCollectionEntryClick = onCollectionEntryClick,
                            onLongPress = onLongPress,
                            collectionDragState = collectionDragState,
                            onShelfDrop = onShelfDrop,
                            onSetLayout = viewModel::setLayout,
                            onSetGridColumns = viewModel::setGridColumns,
                            onSetReadStateFilter = viewModel::setReadStateFilter,
                            onSelectGroup = viewModel::selectGroup,
                            onToggleTagFilter = viewModel::toggleTagFilter,
                            onSetTagMatchMode = viewModel::setTagMatchMode,
                            onSetReadingOrderAffectsShelf =
                                viewModel::setReadingOrderAffectsShelf,
                            onClearFilter = viewModel::clearFilter,
                            onStartSelection = { viewModel.enterSelection() },
                            onOpenShelfGroups = onOpenShelfGroups,
                            onOpenShelfTags = onOpenShelfTags,
                            onImport = requestImport
                        )
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 82.dp)
            )
            AnimatedVisibility(
                visible = state.isImporting,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(180))
            ) {
                ImportProgressOverlay()
            }
        }

        CollectionDragOverlay(collectionDragState, origin = rootOrigin)

        longPressTarget?.let { target ->
            BackHandler { longPressTarget = null }
            BookLongPressOverlay(
                target = target.copy(bounds = shelfBoundsInContainer(target.bounds, rootOrigin)),
                readSpan = state.readSpans[target.book.id],
                rootSize = rootSize,
                contentPadding = contentPadding,
                onDismiss = { longPressTarget = null },
                onSetReadState = { viewModel.setReadState(target.book, it) },
                onEditDetails = { onOpenBookDetail(target.book.id, "edit") },
                onChangeCover = { onOpenBookDetail(target.book.id, "cover") },
                onTogglePinned = { viewModel.togglePinned(target.book) },
                onStartSelection = {
                    longPressTarget = null
                    viewModel.enterSelection(target.book.id)
                },
                onDelete = { deleteTarget = target.book }
            )
        }

        if (state.isSelectionMode) {
            BackHandler { viewModel.exitSelection() }
            ShelfSelectionBar(
                selectedCount = state.selectedCount,
                allVisibleSelected = visibleBookIds.isNotEmpty() &&
                    state.selectedBookIds.containsAll(visibleBookIds),
                onClose = viewModel::exitSelection,
                onSelectAll = { viewModel.selectAllVisible(visibleBookIds) },
                onGroup = { showGroupPicker = true },
                onTags = { showTagPicker = true },
                onDelete = { deleteSelected = true },
                onCollection = { showCollectionPicker = true },
                onSetReadState = viewModel::setSelectedReadState,
                onSetPinned = viewModel::setSelectedPinned,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }

    deleteTarget?.let { book ->
        com.mozhi.reader.ui.components.RemoveBookDialog(
            title = "移除《${book.title}》？",
            onDismiss = { deleteTarget = null },
            onConfirm = { deleteRecords ->
                deleteTarget = null
                viewModel.deleteBook(book, deleteRecords)
            }
        )
    }

    if (importMenuVisible) {
        ImportMethodSheet(
            onDismiss = { importMenuVisible = false },
            onPickFiles = {
                importMenuVisible = false
                documentLauncher.launch(com.mozhi.reader.core.importer.convert.BookImportFiles.pickerMimeTypes.toTypedArray())
            },
            onPickFolder = {
                importMenuVisible = false
                folderLauncher.launch(null)
            },
            onLanTransfer = {
                importMenuVisible = false
                onOpenLanTransfer()
            }
        )
    }

    if (showTagPicker) {
        TagPickerSheet(
            selectedBookIds = state.selectedBookIds,
            tags = state.tags,
            refs = state.tagRefs,
            onDismiss = { showTagPicker = false },
            onToggleTag = viewModel::applyTagToSelection,
            onCreateTag = viewModel::createTagForSelection
        )
    }

    if (showGroupPicker) {
        ShelfGroupPickerSheet(
            groups = state.groups,
            onDismiss = { showGroupPicker = false },
            onSelect = viewModel::moveSelectedToGroup
        )
    }

    if (showCollectionPicker) {
        CollectionPickerSheet(
            selectedCount = state.selectedCount,
            collections = state.collections,
            allBooks = state.allBooks,
            onCreate = {
                showCollectionPicker = false
                collectionNameRequest = CollectionCreateRequest(state.selectedBookIds)
            },
            onSelect = { collectionId ->
                showCollectionPicker = false
                viewModel.addBooksToCollection(collectionId, state.selectedBookIds)
            },
            onDismiss = { showCollectionPicker = false }
        )
    }

    openCollectionId?.let { openedId ->
        shelfEntries.filterIsInstance<ShelfEntry.Collection>()
            .firstOrNull { it.collection.id == openedId }
            ?.let { entry ->
                CollectionContentsSheet(
                    entry = entry,
                    gridColumns = state.gridColumns,
                    onOpenBook = { bookId -> onOpenBookDetail(bookId, null) },
                    onReorderBooks = { bookIds ->
                        viewModel.reorderCollectionBooks(entry.collection.id, bookIds)
                    },
                    onRemoveBook = { viewModel.removeBooksFromCollection(setOf(it)) },
                    onRename = { renameCollection = entry.collection },
                    onDissolve = { dissolveCollection = entry.collection },
                    onDismiss = { openCollectionId = null }
                )
            }
    }

    collectionNameRequest?.let { request ->
        CollectionNameDialog(
            title = "新建合集",
            initialName = "",
            onConfirm = { name ->
                collectionNameRequest = null
                request.shelfOrder?.let(viewModel::saveShelfOrder)
                viewModel.createCollection(name, request.bookIds)
            },
            onDismiss = { collectionNameRequest = null }
        )
    }

    renameCollection?.let { collection ->
        CollectionNameDialog(
            title = "重命名合集",
            initialName = collection.name,
            onConfirm = { name ->
                renameCollection = null
                viewModel.renameCollection(collection.id, name)
            },
            onDismiss = { renameCollection = null }
        )
    }

    dissolveCollection?.let { collection ->
        AlertDialog(
            onDismissRequest = { dissolveCollection = null },
            title = { Text("解散 ${collection.name}？") },
            text = { Text("解散后书籍回到书架，不会删除书籍或阅读数据。") },
            confirmButton = {
                TextButton(onClick = {
                    dissolveCollection = null
                    openCollectionId = null
                    viewModel.dissolveCollection(collection.id)
                }) { Text("解散") }
            },
            dismissButton = {
                TextButton(onClick = { dissolveCollection = null }) { Text("取消") }
            }
        )
    }

    if (deleteSelected) {
        com.mozhi.reader.ui.components.RemoveBookDialog(
            title = "移除 ${state.selectedCount} 本书？",
            onDismiss = { deleteSelected = false },
            onConfirm = { deleteRecords -> deleteSelected = false; viewModel.deleteSelected(deleteRecords) }
        )
    }
}

/**
 * 三条导入路径的选择弹层。以前「＋」直接拉起系统选择器，多了文件夹与局域网两条路后
 * 必须先问一句——否则只能把三个入口塞进工具栏，把书架顶部挤成按钮墙。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportMethodSheet(
    onDismiss: () -> Unit,
    onPickFiles: () -> Unit,
    onPickFolder: () -> Unit,
    onLanTransfer: () -> Unit
) {
    MoReadBottomSheet(
        onDismissRequest = onDismiss,
        sheetMaxWidth = MoReadLayoutPolicy.SheetMaxWidthDp.dp,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "导入书籍",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ImportMethodRow(
                icon = Icons.Outlined.LibraryAdd,
                title = "选择文件",
                summary = stringResource(R.string.import_multiple_formats),
                onClick = onPickFiles
            )
            ImportMethodRow(
                icon = Icons.Outlined.FolderOpen,
                title = "扫描文件夹",
                summary = "递归找出整个目录里的书，勾选后批量导入",
                onClick = onPickFolder
            )
            ImportMethodRow(
                icon = Icons.Outlined.Wifi,
                title = "局域网传书",
                summary = "电脑浏览器打开手机地址，拖拽上传",
                onClick = onLanTransfer
            )
        }
    }
}

@Composable
private fun ImportMethodRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(moReadMetrics().radiusFor(20)),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImportProgressOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        FrostedSurface(
            shape = RoundedCornerShape(moReadMetrics().radiusFor(26)),
            shadowElevation = 18.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 19.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(25.dp), strokeWidth = 3.dp)
                Column {
                    Text(stringResource(R.string.import_preparing), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.import_preparing_detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}
