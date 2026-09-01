package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookCollectionEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun CollectionArtwork(
    books: List<BookEntity>,
    modifier: Modifier = Modifier
) {
    val covers = books.take(4)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 8.dp
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            userScrollEnabled = false,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(covers, key = BookEntity::id) { book ->
                Box(
                    modifier = Modifier
                        .aspectRatio(0.69f)
                        .background(coverColor(book.title))
                ) {
                    AsyncImage(
                        model = book.coverPath?.let(::File)?.takeIf(File::isFile),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
internal fun GridCollectionItem(
    entry: ShelfEntry.Collection,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    collectionDragState: ShelfCollectionDragState,
    modifier: Modifier = Modifier
) {
    val target = remember(entry.key) { entry.dropTarget() }
    val registrationOwner = remember { Any() }
    DisposableEffect(entry.key) {
        onDispose { collectionDragState.unregister(entry.key, registrationOwner) }
    }
    Column(
        modifier = modifier
            .graphicsLayer { alpha = if (selectionMode && !selected) 0.55f else 1f }
            .onGloballyPositioned {
                collectionDragState.register(target, it.boundsInRoot(), registrationOwner)
            }
            .then(
                if (collectionDragState.activeTarget == target) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(12.dp)
                ) else Modifier
            )
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
    ) {
        Box {
            CollectionArtwork(
                books = entry.books,
                modifier = Modifier.fillMaxWidth().aspectRatio(0.69f)
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
            text = entry.collection.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 9.dp)
        )
        Text(
            text = "${entry.books.size} 本",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
internal fun ListCollectionItem(
    entry: ShelfEntry.Collection,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    collectionDragState: ShelfCollectionDragState,
    modifier: Modifier = Modifier
) {
    val target = remember(entry.key) { entry.dropTarget() }
    val registrationOwner = remember { Any() }
    DisposableEffect(entry.key) {
        onDispose { collectionDragState.unregister(entry.key, registrationOwner) }
    }
    FrostedSurface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (selectionMode && !selected) 0.55f else 1f }
            .onGloballyPositioned {
                collectionDragState.register(target, it.boundsInRoot(), registrationOwner)
            }
            .then(
                if (collectionDragState.activeTarget == target) Modifier.border(
                    2.dp,
                    MaterialTheme.colorScheme.primary,
                    RoundedCornerShape(24.dp)
                ) else Modifier
            )
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CollectionArtwork(
                books = entry.books,
                modifier = Modifier.size(width = 68.dp, height = 96.dp)
            )
            if (selectionMode) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = if (selected) "已选择" else "未选择",
                    tint = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(start = 12.dp).size(24.dp)
                )
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    text = entry.collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${entry.books.size} 本书",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
internal fun CollectionDragOverlay(state: ShelfCollectionDragState) {
    val book = state.sourceBook ?: return
    CompactBookArtwork(
        book = book,
        modifier = Modifier
            .offset {
                IntOffset(
                    state.pointer.x.roundToInt() - 42.dp.roundToPx(),
                    state.pointer.y.roundToInt() - 58.dp.roundToPx()
                )
            }
            .size(width = 84.dp, height = 116.dp)
            .graphicsLayer {
                alpha = 0.88f
                scaleX = 1.04f
                scaleY = 1.04f
            }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionPickerSheet(
    selectedCount: Int,
    collections: List<BookCollectionEntity>,
    allBooks: List<BookEntity>,
    onCreate: () -> Unit,
    onSelect: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetGesturesEnabled = false,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Text("将 $selectedCount 本书加入合集", style = MaterialTheme.typography.titleLarge)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().height(400.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item(key = "create") {
                    Surface(
                        onClick = onCreate,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.fillMaxWidth().aspectRatio(0.69f)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxSize().padding(8.dp)
                        ) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(30.dp)
                            )
                            Text(
                                "新建合集",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
                items(collections, key = BookCollectionEntity::id) { collection ->
                    val books = allBooks.filter { it.collectionId == collection.id }
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { onSelect(collection.id) }
                    ) {
                        CollectionArtwork(
                            books = books,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.69f)
                        )
                        Text(
                            collection.name,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CollectionContentsSheet(
    entry: ShelfEntry.Collection,
    onOpenBook: (Long) -> Unit,
    onRemoveBook: (Long) -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    onDismiss: () -> Unit
) {
    var moreExpanded by remember(entry.collection.id) { mutableStateOf(false) }
    var memberMenuBookId by remember(entry.collection.id) { mutableStateOf<Long?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxHeight(),
        sheetGesturesEnabled = false,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(entry.collection.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${entry.books.size} 本书",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Box {
                    IconButton(onClick = { moreExpanded = true }) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "合集操作")
                    }
                    MoReadStableDropdownMenu(
                        expanded = moreExpanded,
                        onDismissRequest = { moreExpanded = false },
                        width = 200.dp
                    ) {
                        MoReadMenuItem(
                            text = "重命名",
                            icon = Icons.Outlined.Edit,
                            onClick = { moreExpanded = false; onRename() }
                        )
                        MoReadMenuItem(
                            text = "解散合集",
                            icon = Icons.Outlined.Delete,
                            destructive = true,
                            onClick = { moreExpanded = false; onDissolve() }
                        )
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                items(entry.books, key = BookEntity::id) { book ->
                    Column {
                        Box {
                            CompactBookArtwork(
                                book = book,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.69f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onOpenBook(book.id) }
                            )
                            Box(Modifier.align(Alignment.TopEnd)) {
                                IconButton(onClick = { memberMenuBookId = book.id }) {
                                    Icon(
                                        Icons.Outlined.MoreVert,
                                        contentDescription = "${book.title} 操作"
                                    )
                                }
                                MoReadStableDropdownMenu(
                                    expanded = memberMenuBookId == book.id,
                                    onDismissRequest = { memberMenuBookId = null },
                                    width = 180.dp
                                ) {
                                    MoReadMenuItem(
                                        text = "移出合集",
                                        icon = Icons.Outlined.Delete,
                                        destructive = true,
                                        onClick = {
                                            memberMenuBookId = null
                                            onRemoveBook(book.id)
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            book.title,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CollectionNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    val trimmed = name.trim()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("合集名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty(),
                onClick = { onConfirm(trimmed) }
            ) { Text("确定", fontWeight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
