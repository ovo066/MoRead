package com.mozhi.reader.feature.reader

import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.compose.AsyncImage
import com.mozhi.reader.R
import com.mozhi.reader.MainActivity
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.BookTocEntryEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.datastore.ReaderFont
import com.mozhi.reader.core.datastore.PendingReaderFont
import com.mozhi.reader.core.datastore.ReaderSettings
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.ReaderTheme
import com.mozhi.reader.core.datastore.activeThemeSlot
import com.mozhi.reader.core.datastore.resolveThemeSlot
import com.mozhi.reader.feature.reader.engine.ReaderAnnotationMark
import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import com.mozhi.reader.feature.reader.engine.InlineMarkerKind
import com.mozhi.reader.feature.reader.engine.InlineMarkerReservation
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** 即划即改浮条：刚落的划线 id + 浮条位置（沿用选区工具栏的锚点位）。 */
@Composable
internal fun ContentsSheet(
    chapters: List<ChapterEntity>,
    tocEntries: List<BookTocEntryEntity>,
    currentChapterIndex: Int,
    palette: ReaderPalette,
    onChapterClick: (Int, String) -> Unit
) {
    val tocItems = remember(chapters, tocEntries) { buildReaderTocItems(chapters, tocEntries) }
    var collapsedOrderIndices by remember { mutableStateOf(emptySet<Int>()) }
    val visibleItems = remember(tocItems, collapsedOrderIndices) {
        visibleReaderTocItems(tocItems, collapsedOrderIndices)
    }
    val currentOrder = remember(tocItems, currentChapterIndex) {
        currentReaderTocOrder(tocItems, currentChapterIndex)
    }
    val currentListIndex = currentReaderTocListIndex(
        allItems = tocItems,
        visibleItems = visibleItems,
        currentChapterIndex = currentChapterIndex
    )
    val volumeCount = tocItems.count { it.depth == 0 && it.hasChildren }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (currentListIndex - 2).coerceAtLeast(0)
    )
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxHeight()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 2.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "目录",
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.onBackground
                )
                Text(
                    text = buildString {
                        append("共 ${chapters.size} 章")
                        if (volumeCount > 0) append(" · $volumeCount 卷")
                        append(" · 读到第 ${currentChapterIndex + 1} 章")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Surface(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem((currentListIndex - 2).coerceAtLeast(0))
                    }
                },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                color = palette.glass,
                contentColor = palette.accent,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.glassBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "定位",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .navigationBarsPadding()
                .blockSheetDrag(listState),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 10.dp,
                end = 10.dp,
                bottom = 18.dp
            )
        ) {
            items(visibleItems, key = ReaderTocItem::key) { item ->
                val current = item.orderIndex == currentOrder
                val expanded = item.orderIndex !in collapsedOrderIndices
                val horizontalIndent = (12 + item.depth.coerceAtMost(4) * 18).dp
                Surface(
                    onClick = {
                        when {
                            item.chapterIndex != null -> onChapterClick(item.chapterIndex, item.href)
                            item.hasChildren -> collapsedOrderIndices = collapsedOrderIndices.toggle(item.orderIndex)
                        }
                    },
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    color = if (current) {
                        palette.accentContainer.copy(alpha = 0.6f)
                    } else {
                        Color.Transparent
                    },
                    contentColor = palette.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 1.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(
                            start = horizontalIndent,
                            end = 8.dp,
                            top = if (item.hasChildren) 9.dp else 11.dp,
                            bottom = if (item.hasChildren) 9.dp else 11.dp
                        ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.hasChildren) {
                            Icon(
                                Icons.AutoMirrored.Outlined.MenuBook,
                                contentDescription = null,
                                tint = if (current) palette.accent else palette.muted,
                                modifier = Modifier.size(17.dp)
                            )
                        } else {
                            Text(
                                text = item.chapterIndex?.plus(1)?.toString()?.padStart(3, '0').orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (current) palette.accent else palette.muted
                            )
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = item.title,
                                style = if (item.hasChildren) {
                                    MaterialTheme.typography.titleSmall
                                } else {
                                    MaterialTheme.typography.bodyMedium
                                },
                                fontWeight = if (current || item.hasChildren) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = if (current) palette.accent else palette.onBackground,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            if (item.hasChildren) {
                                val childCount = tocItems.count {
                                    it.parentOrderIndex == item.orderIndex
                                }
                                Text(
                                    text = "$childCount 项",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted
                                )
                            }
                        }
                        if (current) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "当前章节",
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        if (item.hasChildren) {
                            IconButton(
                                onClick = {
                                    collapsedOrderIndices = collapsedOrderIndices.toggle(item.orderIndex)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (expanded) {
                                        Icons.Outlined.ExpandMore
                                    } else {
                                        Icons.Outlined.ChevronRight
                                    },
                                    contentDescription = if (expanded) "收起分卷" else "展开分卷",
                                    tint = palette.muted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Set<Int>.toggle(value: Int): Set<Int> =
    if (value in this) this - value else this + value

@Composable
internal fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    palette: ReaderPalette,
    onBookmarkClick: (BookmarkEntity) -> Unit,
    onDeleteBookmark: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxHeight()) {
        Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp)) {
            Text(
                text = "书签",
                style = MaterialTheme.typography.headlineSmall,
                color = palette.onBackground
            )
            Text(
                text = "共 ${bookmarks.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (bookmarks.isEmpty()) {
            Text(
                text = "还没有书签，阅读页右上角菜单即可添加。",
                color = palette.muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        } else {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding()
                    .blockSheetDrag(listState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 10.dp,
                    end = 10.dp,
                    bottom = 18.dp
                )
            ) {
                items(bookmarks, key = BookmarkEntity::id) { bookmark ->
                    Surface(
                        onClick = { onBookmarkClick(bookmark) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        color = Color.Transparent,
                        contentColor = palette.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = bookmark.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.onBackground,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = bookmark.excerpt.ifBlank { "点击返回该位置" },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.muted,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除书签",
                                    tint = palette.muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReaderError(message: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("无法打开书籍", style = MaterialTheme.typography.titleLarge)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onBack) { Text("返回书架") }
    }
}
