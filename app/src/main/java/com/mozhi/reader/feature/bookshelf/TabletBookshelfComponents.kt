package com.mozhi.reader.feature.bookshelf

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.database.entity.label as readStateLabel
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent

@Composable
internal fun TabletLibraryTitle(state: BookshelfUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("我的书库", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text("${state.totalBooks} 本藏书 · 总有一本，值得再翻开",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Recent books keep their real reading progress; filtering the library never changes this list. */
@Composable
internal fun TabletContinueReading(state: BookshelfUiState, onOpenBook: (Long) -> Unit) {
    val recent = remember(state.allBooks, state.recentBook) {
        (listOfNotNull(state.recentBook) + state.allBooks.sortedByDescending { it.lastReadAt })
            .distinctBy { it.id }.filter { it.lastReadAt > 0 && it.readState() == BookReadState.READING }.take(2)
    }
    if (recent.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("tablet-continue"), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("继续阅读", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        BoxWithConstraints {
            // The cards respond to the content pane, after the sidebar has taken its share.
            val visibleCount = if (maxWidth >= 760.dp) 2 else 1
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                recent.take(visibleCount).forEach { book ->
                    ContinueBook(book, state.readSpans[book.id],
                        chapter = if (book.id == state.recentBook?.id) state.recentChapterTitle else "",
                        modifier = Modifier.weight(1f), onOpen = { onOpenBook(book.id) })
                }
            }
        }
    }
}

@Composable
private fun ContinueBook(book: BookEntity, span: BookReadSpan?, chapter: String, modifier: Modifier, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val progress = readFraction(book, span)
    Surface(onClick = onOpen, color = colors.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp), modifier = modifier.testTag("tablet-resume-${book.id}")) {
        Row(Modifier.padding(22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalAlignment = Alignment.CenterVertically) {
            CompactBookArtwork(book, Modifier.width(90.dp).height(132.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(book.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "未知作者" }, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chapter.ifBlank { "上次读到第 ${book.lastReadChapterIndex + 1} 章" },
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = colors.primary, trackColor = colors.surfaceContainerHighest,
                    gapSize = 0.dp, drawStopIndicator = {})
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已读 ${readPercent(progress)}%", Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    Text("继续阅读", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, null,
                        Modifier.padding(start = 6.dp).size(16.dp), tint = colors.primary)
                }
            }
        }
    }
}

/** Search remains in reach while browsing; layout controls stay in the existing top-right menu. */
@Composable
internal fun TabletLibraryToolbar(
    state: BookshelfUiState, count: Int, query: String, onQueryChange: (String) -> Unit,
    onClearFilter: () -> Unit, floating: Boolean = false
) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxWidth().testTag("tablet-library-toolbar")) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BoxWithConstraints {
                val narrow = maxWidth < 720.dp
                val title: @Composable () -> Unit = {
                    // Keep geometry stable when pinned; only the search capsule floats above books.
                    Column(Modifier.alpha(if (floating) 0f else 1f)
                        .then(if (floating) Modifier.clearAndSetSemantics {} else Modifier)) {
                        Text(if (query.isNotBlank()) "搜索结果" else state.filter.readState?.readStateLabel()
                            ?: if (state.filter.groupId != null || state.filter.ungroupedOnly) state.selectedGroupName else "全部书籍",
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("$count 本", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (narrow) title()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!narrow) Box(Modifier.weight(1f)) { title() }
                        Box(if (narrow) Modifier.weight(1f) else Modifier.width(280.dp)) {
                            com.mozhi.reader.ui.components.MoReadSearchCapsule(query, onQueryChange, floating = floating,
                                placeholder = "搜索书名、作者或标签", testTagPrefix = "shelf")
                        }
                    }
                }
            }
            if (state.filter.isActive) {
                Row(Modifier.alpha(if (floating) 0f else 1f)
                    .then(if (floating) Modifier.clearAndSetSemantics {} else Modifier),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(buildList {
                        if (state.filter.groupId != null || state.filter.ungroupedOnly) add(state.selectedGroupName)
                        state.filter.readState?.let { add(it.readStateLabel()) }
                        addAll(state.tags.filter { it.id in state.filter.tagIds }.map { it.name })
                    }.joinToString(" · "), modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onClearFilter, enabled = !floating) { Text("清除筛选") }
                }
            }
        }
    }
}
