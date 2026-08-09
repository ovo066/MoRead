package com.mozhi.reader.feature.bookshelf

import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.FrostedSurface
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
    onOpenBookDetail: (Long) -> Unit,
    onOpenImportPreview: (String) -> Unit,
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteTarget by remember { mutableStateOf<BookEntity?>(null) }
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
            .padding(contentPadding)
    ) {
        if (state.books.isEmpty()) {
            EmptyBookshelfFeed(onImport = requestImport)
        } else {
            Crossfade(
                targetState = state.layout,
                animationSpec = tween(durationMillis = 260),
                label = "bookshelf-layout"
            ) { layout ->
                when (layout) {
                    ShelfLayout.GRID -> BookGrid(
                        books = filteredBooks,
                        allBooks = state.books,
                        recentChapterTitle = state.recentChapterTitle,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onOpenBook = onOpenBook,
                        onOpenBookDetail = onOpenBookDetail,
                        onDeleteBook = { deleteTarget = it },
                        onToggleLayout = viewModel::toggleLayout,
                        onImport = requestImport
                    )
                    ShelfLayout.LIST -> BookList(
                        books = filteredBooks,
                        allBooks = state.books,
                        recentChapterTitle = state.recentChapterTitle,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        onOpenBook = onOpenBook,
                        onOpenBookDetail = onOpenBookDetail,
                        onDeleteBook = { deleteTarget = it },
                        onToggleLayout = viewModel::toggleLayout,
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
    allBooks: List<BookEntity>,
    recentChapterTitle: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onDeleteBook: (BookEntity) -> Unit,
    onToggleLayout: () -> Unit,
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
                books = allBooks,
                recentChapterTitle = recentChapterTitle,
                searchQuery = searchQuery,
                onSearchChange = onSearchChange,
                onOpenBook = onOpenBook
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LibraryToolbar(
                bookCount = books.size,
                searching = searchQuery.isNotBlank(),
                layout = ShelfLayout.GRID,
                onToggleLayout = onToggleLayout,
                onImport = onImport
            )
        }
        if (books.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                NoSearchResults(query = searchQuery)
            }
        }
        gridItems(books, key = BookEntity::id) { book ->
            GridBookItem(
                book = book,
                onOpen = { onOpenBookDetail(book.id) },
                onOpenDetail = { onOpenBookDetail(book.id) },
                onDelete = { onDeleteBook(book) }
            )
        }
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    allBooks: List<BookEntity>,
    recentChapterTitle: String,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenBookDetail: (Long) -> Unit,
    onDeleteBook: (BookEntity) -> Unit,
    onToggleLayout: () -> Unit,
    onImport: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            BookshelfHeader(
                books = allBooks,
                recentChapterTitle = recentChapterTitle,
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
                layout = ShelfLayout.LIST,
                onToggleLayout = onToggleLayout,
                onImport = onImport
            )
        }
        if (books.isEmpty()) {
            item { NoSearchResults(query = searchQuery) }
        }
        listItems(books, key = BookEntity::id) { book ->
            ListBookItem(
                book = book,
                onOpen = { onOpenBookDetail(book.id) },
                onOpenDetail = { onOpenBookDetail(book.id) },
                onDelete = { onDeleteBook(book) }
            )
        }
    }
}

/** 问候语抬头 + 搜索胶囊 + 正在阅读卡（design/ui-adaptation-plan.md §2.1–2.3）。 */
@Composable
private fun BookshelfHeader(
    books: List<BookEntity>,
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
                books = books,
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
    books: List<BookEntity>,
    recentChapterTitle: String,
    onOpenBook: (Long) -> Unit
) {
    val recentBook = remember(books) {
        books.asSequence()
            .filter { it.lastReadAt > 0L }
            .maxByOrNull(BookEntity::lastReadAt)
    }
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
    layout: ShelfLayout,
    onToggleLayout: () -> Unit,
    onImport: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("书架", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (searching) "找到 $bookCount 本" else "$bookCount 本 · 按最近阅读",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrostedSurface(shape = CircleShape, shadowElevation = 3.dp) {
                IconButton(onClick = onToggleLayout, modifier = Modifier.size(44.dp)) {
                    Icon(
                        imageVector = if (layout == ShelfLayout.GRID) {
                            Icons.AutoMirrored.Outlined.List
                        } else {
                            Icons.Outlined.GridView
                        },
                        contentDescription = "切换书架布局"
                    )
                }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridBookItem(
    book: BookEntity,
    onOpen: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Column {
        Box {
            BookCover(
                book = book,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.69f)
                    .combinedClickable(
                        onClick = onOpen,
                        onLongClick = { menuExpanded = true }
                    )
            )
            BookItemMenu(
                expanded = menuExpanded,
                onDismiss = { menuExpanded = false },
                onOpenDetail = onOpenDetail,
                onDelete = onDelete
            )
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
private fun BookItemMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("书籍详情") },
            leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            onClick = {
                onDismiss()
                onOpenDetail()
            }
        )
        DropdownMenuItem(
            text = { Text("删除") },
            leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            onClick = {
                onDismiss()
                onDelete()
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListBookItem(
    book: BookEntity,
    onOpen: () -> Unit,
    onOpenDetail: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val progress = readProgress(book)
    Box {
        FrostedSurface(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuExpanded = true }
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
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
        BookItemMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onOpenDetail = onOpenDetail,
            onDelete = onDelete
        )
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
                    text = if (book.lastReadAt == 0L) "未开始" else "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CompactBookArtwork(book: BookEntity, modifier: Modifier) {
    val coverFile = remember(book.coverPath) {
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

/** 搜索无结果：书架非空但过滤后为零，给个明确说法而不是一片空白。 */
@Composable
private fun NoSearchResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 44.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp)
        )
        Text(
            text = "没有匹配「${query.trim()}」的书",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
        Text(
            text = "试试书名、作者或标签的其他关键词",
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

private fun readProgress(book: BookEntity): Float = when {
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
