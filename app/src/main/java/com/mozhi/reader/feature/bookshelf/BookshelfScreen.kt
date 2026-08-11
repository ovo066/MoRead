package com.mozhi.reader.feature.bookshelf

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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterAltOff
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.isPinned
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuExpandableItem
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.navSelectedColor
import com.mozhi.reader.ui.theme.onNavSelectedColor
import com.mozhi.reader.ui.theme.sealColor
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BookshelfScreen(
    contentPadding: PaddingValues,
    externalImportUri: Uri? = null,
    onExternalImportConsumed: () -> Unit = {},
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (bookId: Long, action: String?) -> Unit,
    onOpenImportPreview: (String) -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
    var longPressTarget by remember { mutableStateOf<BookLongPressTarget?>(null) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    // ACTION_GET_CONTENT 而不是 OPEN_DOCUMENT：后者只能用系统 DocumentsUI（简陋），
    // 前者允许 OEM 文件管理（vivo 等，带搜索）接管。授权是一次性的，导入在选择后
    // 立即把内容读进会话/私有目录，够用；类型校验在 prepare() 里做。
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let(viewModel::importDocument)
    }
    LaunchedEffect(externalImportUri) {
        externalImportUri?.let { uri ->
            viewModel.importDocument(uri)
            onExternalImportConsumed()
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        documentLauncher.launch("*/*")
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
            documentLauncher.launch("*/*")
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BookshelfEvent.OpenImportPreview -> onOpenImportPreview(event.sessionId)
                is BookshelfEvent.OpenBook -> onOpenBook(event.bookId)
                is BookshelfEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    // 搜索是叠在筛选之上的第二层过滤，留在 UI 层：输入是本地状态，没必要绕一圈 VM。
    val filteredBooks = remember(state.books, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            state.books
        } else {
            state.books.filter { book ->
                book.title.contains(query, ignoreCase = true) ||
                    book.author.contains(query, ignoreCase = true) ||
                    book.tags.contains(query, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
    ) {
        // 长按浮层出现时内容整体虚化（API 31+；以下靠更重的压暗兜底）。
        val blurRadius by animateDpAsState(
            targetValue = if (longPressTarget != null) 18.dp else 0.dp,
            animationSpec = tween(durationMillis = 160),
            label = "shelf-blur"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(blurRadius, BlurredEdgeTreatment.Unbounded)
                .padding(contentPadding)
        ) {
            if (state.totalBooks == 0) {
                EmptyBookshelfFeed(onImport = requestImport)
            } else {
                val onLongPress: (BookEntity, Rect) -> Unit = { book, bounds ->
                    longPressTarget = BookLongPressTarget(book, bounds)
                }
                Crossfade(
                    targetState = state.layout,
                    animationSpec = tween(durationMillis = 260),
                    label = "bookshelf-layout"
                ) { layout ->
                    when (layout) {
                        ShelfLayout.GRID -> BookGrid(
                            books = filteredBooks,
                            state = state,
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            onOpenBook = onOpenBook,
                            onOpenBookDetail = { onOpenBookDetail(it, null) },
                            onLongPress = onLongPress,
                            onSetLayout = viewModel::setLayout,
                            onSetReadStateFilter = viewModel::setReadStateFilter,
                            onSetTagFilter = viewModel::setTagFilter,
                            onImport = requestImport
                        )
                        ShelfLayout.LIST -> BookList(
                            books = filteredBooks,
                            state = state,
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            onOpenBook = onOpenBook,
                            onOpenBookDetail = { onOpenBookDetail(it, null) },
                            onLongPress = onLongPress,
                            onSetLayout = viewModel::setLayout,
                            onSetReadStateFilter = viewModel::setReadStateFilter,
                            onSetTagFilter = viewModel::setTagFilter,
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

        longPressTarget?.let { target ->
            BackHandler { longPressTarget = null }
            BookLongPressOverlay(
                target = target,
                rootSize = rootSize,
                onDismiss = { longPressTarget = null },
                onSetReadState = { viewModel.setReadState(target.book, it) },
                onEditDetails = { onOpenBookDetail(target.book.id, "edit") },
                onChangeCover = { onOpenBookDetail(target.book.id, "cover") },
                onTogglePinned = { viewModel.togglePinned(target.book) },
                onDelete = { deleteTarget = target.book }
            )
        }
    }

    deleteTarget?.let { book ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("删除 ${book.title}？") },
            text = { Text("书籍文件、阅读进度、目录与书签都会从本机删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        viewModel.deleteBook(book)
                    }
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun EmptyBookshelfFeed(onImport: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item { GreetingHeader() }
        item { EmptyBookshelf(onImport = onImport) }
    }
}

@Composable
private fun BookGrid(
    books: List<BookEntity>,
    state: BookshelfUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onLongPress: (BookEntity, Rect) -> Unit,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onImport: () -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BookshelfHeader(
                recentBook = state.recentBook,
                recentChapterTitle = state.recentChapterTitle,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                onOpenBook = onOpenBook
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryToolbar(
                bookCount = books.size,
                searching = searchQuery.isNotBlank(),
                state = state,
                onSetLayout = onSetLayout,
                onSetReadStateFilter = onSetReadStateFilter,
                onSetTagFilter = onSetTagFilter,
                onImport = onImport
            )
        }
        if (books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NoShelfResults(query = searchQuery, filter = state.filter)
            }
        }
        gridItems(books, key = BookEntity::id) { book ->
            GridBookItem(
                book = book,
                onOpen = { onOpenBookDetail(book.id) },
                onLongPress = { bounds -> onLongPress(book, bounds) }
            )
        }
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    state: BookshelfUiState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onLongPress: (BookEntity, Rect) -> Unit,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onImport: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            BookshelfHeader(
                recentBook = state.recentBook,
                recentChapterTitle = state.recentChapterTitle,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                onOpenBook = onOpenBook
            )
        }
        item {
            Spacer(Modifier.height(8.dp))
            LibraryToolbar(
                bookCount = books.size,
                searching = searchQuery.isNotBlank(),
                state = state,
                onSetLayout = onSetLayout,
                onSetReadStateFilter = onSetReadStateFilter,
                onSetTagFilter = onSetTagFilter,
                onImport = onImport
            )
        }
        if (books.isEmpty()) {
            item { NoShelfResults(query = searchQuery, filter = state.filter) }
        }
        listItems(books, key = BookEntity::id) { book ->
            ListBookItem(
                book = book,
                onOpen = { onOpenBookDetail(book.id) },
                onLongPress = { bounds -> onLongPress(book, bounds) }
            )
        }
    }
}

/** 问候语抬头 + 搜索胶囊 + 正在阅读卡（design/ui-adaptation-plan.md §2.1–2.3）。 */
@Composable
private fun BookshelfHeader(
    recentBook: BookEntity?,
    recentChapterTitle: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        GreetingHeader()
        SearchCapsule(query = searchQuery, onQueryChange = onSearchChange)
        // 搜索时让位给结果：此刻用户找的是别的书，不是手头这本。
        if (searchQuery.isBlank()) {
            ReadingNowCard(
                recentBook = recentBook,
                recentChapterTitle = recentChapterTitle,
                onOpenBook = onOpenBook
            )
        }
    }
}

@Composable
private fun GreetingHeader() {
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

@Composable
private fun SearchCapsule(query: String, onQueryChange: (String) -> Unit) {
    val textStyle = MaterialTheme.typography.bodyMedium
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MoReadTokens.CapsuleShape,
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .height(46.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = "搜索书名、作者或想法",
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = textStyle.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(
                        MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/**
 * 正在阅读播放卡（demo `.now-card`）：封面 76×106 + 朱砂眉标 + serif 书名 +
 * 「作者 · 第 n 章 章名」+ 细进度条/百分比 + 右侧 52dp 墨色圆形播放钮。
 * 右上角带一抹 moss 径向墨韵。
 */
@Composable
private fun ReadingNowCard(
    recentBook: BookEntity?,
    recentChapterTitle: String,
    onOpenBook: (Long) -> Unit
) {
    val seal = sealColor()
    val darkTheme = isDarkTheme()
    val accent = accentColor()

    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp
    ) {
        Box {
            // 墨韵：右上角一抹极淡的强调色径向渐变（demo .now-card::after）。
            Canvas(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(28.dp))
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
                        shape = RoundedCornerShape(20.dp),
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
                            "导入 TXT 或 EPUB，墨知会整理章节并保存在本机。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 5.dp)
                        )
                    }
                }
            } else {
                val progress = readProgress(recentBook)
                Row(
                    modifier = Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactBookArtwork(
                        book = recentBook,
                        modifier = Modifier.size(width = 76.dp, height = 106.dp)
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
                                text = "${(progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                    // go-read：墨色（夜间 moss）实心圆形播放钮。
                    Surface(
                        onClick = { onOpenBook(recentBook.id) },
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
private fun LibraryToolbar(
    bookCount: Int,
    searching: Boolean,
    state: BookshelfUiState,
    onSetLayout: (ShelfLayout) -> Unit,
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagFilter: (String?) -> Unit,
    onImport: () -> Unit
) {
    var viewMenuExpanded by remember { mutableStateOf(false) }
    var availableMenuHeight by remember { mutableStateOf(SHELF_MENU_MAX_HEIGHT) }
    val density = LocalDensity.current
    val localView = LocalView.current
    val filter = state.filter
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("书架", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = buildString {
                    if (searching) append("找到 $bookCount 本") else append("$bookCount 本")
                    filter.readState?.let { append(" · ").append(it.label()) }
                    filter.tag?.let { append(" · #").append(it) }
                    if (!searching && !filter.isActive) append(" · 按最近阅读")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
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
                    onSetReadStateFilter = onSetReadStateFilter,
                    onSetTagFilter = onSetTagFilter
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
    onSetReadStateFilter: (BookReadState?) -> Unit,
    onSetTagFilter: (String?) -> Unit
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

        if (state.tags.isNotEmpty()) {
            MoReadMenuExpandableItem(
                text = "标签",
                valueText = state.filter.tag ?: "全部",
                icon = Icons.Outlined.Sell,
                expanded = section == ShelfMenuSection.TAG,
                onToggle = { toggle(ShelfMenuSection.TAG) }
            ) {
                MoReadMenuItem(
                    text = "全部",
                    indent = MENU_INDENT,
                    selected = state.filter.tag == null,
                    onClick = { onSetTagFilter(null) }
                )
                state.tags.forEach { tag ->
                    MoReadMenuItem(
                        text = tag,
                        indent = MENU_INDENT,
                        selected = state.filter.tag == tag,
                        onClick = { onSetTagFilter(tag) }
                    )
                }
            }
        }

        if (state.filter.isActive) {
            MoReadMenuDivider()
            MoReadMenuItem(
                text = "清除筛选",
                icon = Icons.Outlined.FilterAltOff,
                onClick = {
                    onSetReadStateFilter(null)
                    onSetTagFilter(null)
                    onDismiss()
                }
            )
        }
    }
}

private enum class ShelfMenuSection { LAYOUT, READ_STATE, TAG }

private val MENU_INDENT = 22.dp
private val SHELF_MENU_WIDTH = 244.dp
private val SHELF_MENU_MIN_HEIGHT = 120.dp
private val SHELF_MENU_MAX_HEIGHT = 390.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBookItem(
    book: BookEntity,
    onOpen: () -> Unit,
    onLongPress: (Rect) -> Unit
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Column {
        BookCover(
            book = book,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.69f)
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { onLongPress(bounds) }
                )
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBookItem(
    book: BookEntity,
    onOpen: () -> Unit,
    onLongPress: (Rect) -> Unit
) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val progress = readProgress(book)
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { onLongPress(bounds) }
            ),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactBookArtwork(
                book = book,
                modifier = Modifier.size(width = 68.dp, height = 96.dp)
            )
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
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    progressText(book),
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
    }
}

@Composable
private fun BookCover(
    book: BookEntity,
    modifier: Modifier
) {
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    val progress = readProgress(book)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 420),
        label = "book-progress"
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = coverColor(book.title),
        shadowElevation = 10.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FallbackCover(book = book, modifier = Modifier.fillMaxSize())
            }

            // 左下角玻璃小胶囊进度徽章。
            Surface(
                shape = MoReadTokens.CapsuleShape,
                color = Color.Black.copy(alpha = 0.34f),
                contentColor = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = when (val state = book.readState()) {
                        BookReadState.READING -> "${(animatedProgress * 100).toInt()}%"
                        else -> state.label()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            if (book.isPinned) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.38f),
                    contentColor = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CompactBookArtwork(book: BookEntity, modifier: Modifier) {    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = coverColor(book.title),
        shadowElevation = 7.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            if (coverFile != null) {
                AsyncImage(
                    model = coverFile,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FallbackCover(
                    book = book,
                    modifier = Modifier.fillMaxSize(),
                    compact = true
                )
            }
        }
    }
}

/** 封面缺图 fallback（§1）：左侧书脊线 + 竖排书名 + 底部竖排作者。 */
@Composable
private fun FallbackCover(
    book: BookEntity,
    modifier: Modifier,
    compact: Boolean = false
) {
    val base = coverColor(book.title)
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    base.copy(alpha = 0.94f),
                    base,
                    Color.Black.copy(alpha = 0.52f)
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (compact) 3.dp else 5.dp)
                .padding(start = if (compact) 1.dp else 2.dp)
                .background(Color.White.copy(alpha = 0.30f))
        )
        VerticalBookTitle(
            text = book.title,
            compact = compact,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(
                    top = if (compact) 8.dp else 14.dp,
                    end = if (compact) 8.dp else 14.dp
                )
        )
        if (book.author.isNotBlank()) {
            VerticalBookTitle(
                text = book.author,
                compact = true,
                small = true,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = if (compact) 8.dp else 12.dp, bottom = 8.dp)
            )
        }
    }
}

/** 竖排文字：逐字换行模拟直排；超出单列时按传统直排从右往左折成多列。 */
@Composable
private fun VerticalBookTitle(
    text: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    small: Boolean = false
) {
    val perColumn = if (compact) 6 else 8
    val maxColumns = if (small) 1 else if (compact) 2 else 3
    val capacity = perColumn * maxColumns
    val display = if (text.length > capacity) text.take(capacity - 1) + "…" else text
    val columns = display.chunked(perColumn)
    val style = when {
        small -> MaterialTheme.typography.labelSmall
        compact -> MaterialTheme.typography.titleSmall
        else -> MaterialTheme.typography.titleMedium
    }
    val lineHeight = when {
        small -> MaterialTheme.typography.labelSmall.fontSize * 1.25f
        compact -> MaterialTheme.typography.titleSmall.fontSize * 1.3f
        else -> MaterialTheme.typography.titleMedium.fontSize * 1.3f
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 3.dp)
    ) {
        // 直排阅读顺序是从右往左：第一列排在最右边。
        columns.asReversed().forEach { column ->
            Text(
                text = column.toCharArray().joinToString("\n"),
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = if (small) 0.72f else 0.95f),
                textAlign = TextAlign.Center,
                lineHeight = lineHeight
            )
        }
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
                filter.tag != null -> "没有标着「${filter.tag}」的书"
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
        shape = RoundedCornerShape(32.dp),
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
                    shape = RoundedCornerShape(14.dp),
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
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shadowElevation = 7.dp
                ) {}
                Surface(
                    modifier = Modifier
                        .size(width = 82.dp, height = 120.dp)
                        .align(Alignment.Center),
                    shape = RoundedCornerShape(16.dp),
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
                text = "支持 TXT 智能分章与标准 EPUB。阅读数据、书签和角色对话上下文优先保存在本机。",
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

@Composable
private fun ImportProgressOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        FrostedSurface(
            shape = RoundedCornerShape(26.dp),
            shadowElevation = 18.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 19.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(25.dp), strokeWidth = 3.dp)
                Column {
                    Text("正在整理书籍", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "识别格式与章节，请稍候…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

internal fun readProgress(book: BookEntity): Float = when {
    book.totalChapters <= 0 || book.lastReadAt == 0L -> 0f
    else -> ((book.lastReadChapterIndex + 1f) / book.totalChapters).coerceIn(0f, 1f)
}

private fun progressText(book: BookEntity): String = if (book.lastReadAt == 0L) {
    "未开始 · ${book.totalChapters} 章"
} else {
    val percent = (readProgress(book) * 100).toInt()
    "$percent% · 第 ${book.lastReadChapterIndex + 1}/${book.totalChapters} 章"
}

/** 无封面时的占位底色：中性深灰阶，按书名 hash 取一档，黑白主题下不跳色。 */
internal fun coverColor(title: String): Color {
    val palette = listOf(
        Color(0xFF2B2B2B),
        Color(0xFF3A3A3A),
        Color(0xFF484848),
        Color(0xFF565656),
        Color(0xFF333333),
        Color(0xFF414141),
        Color(0xFF4F4F4F)
    )
    return palette[(title.hashCode() and Int.MAX_VALUE) % palette.size]
}
