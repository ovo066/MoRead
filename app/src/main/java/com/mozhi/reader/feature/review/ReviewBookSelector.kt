package com.mozhi.reader.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.NavigationSheet
import com.mozhi.reader.ui.components.NavigationSheetEdge
import com.mozhi.reader.ui.components.MoReadSearchCapsule
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.ui.theme.ReadingReviewTheme
import coil3.compose.AsyncImage
import java.io.File

@Composable
internal fun ReviewBookSelector(state: ReadingReviewState, initial: Set<Long>, onDismiss: () -> Unit, onApply: (Set<Long>) -> Unit) {
    ReadingReviewTheme {
    var query by rememberSaveable { mutableStateOf("") }
    var all by rememberSaveable { mutableStateOf(initial.isEmpty()) }
    var selected by rememberSaveable { mutableStateOf(ArrayList(initial)) }
    val books = remember(state.entries) { state.entries.map { it.book }.distinctBy { it.id }.sortedBy { it.title } }
    val ids = remember(books) { books.map { it.id }.toSet() }
    val checked = if (all) ids else selected.toSet().intersect(ids)
    val counts = remember(state.entries) { state.entries.groupingBy { it.book.id }.eachCount() }
    val visible = remember(books, query) { books.filter { it.title.contains(query, true) || it.author.contains(query, true) } }
    val list = rememberLazyListState()
    NavigationSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = .35f),
        contentHeightFraction = .78f, expandedEdge = NavigationSheetEdge.START) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().height(64.dp).testTag("review-books-header"), verticalAlignment = Alignment.CenterVertically) {
                Text("选择书籍", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭书籍选择") }
            }
            MoReadSearchCapsule(query, { query = it }, placeholder = "搜索书名或作者", testTagPrefix = "review-books")
            Row(Modifier.fillMaxWidth().height(56.dp).clickable {
                if (all || checked == ids) { all = false; selected = arrayListOf() } else all = true
            }.testTag("review-books-all"), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ids.isNotEmpty() && checked == ids) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    null, Modifier.padding(end = 12.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Text("全部书籍", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("已选 ${checked.size} / ${ids.size}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth().blockSheetDrag(list).testTag("review-books-list")) {
                items(visible, key = { it.id }) { book ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(MaterialTheme.shapes.medium)
                        .background(if (book.id in checked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .30f) else Color.Transparent).clickable {
                        val next = if (book.id in checked) checked - book.id else checked + book.id
                        all = false; selected = ArrayList(next)
                    }.padding(horizontal = 12.dp, vertical = 10.dp).testTag("review-book-select-${book.id}"), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(38.dp).height(52.dp).clip(RoundedCornerShape(7.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            if (book.coverPath != null && File(book.coverPath).isFile) AsyncImage(File(book.coverPath), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Text(book.title.take(2), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Serif,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(book.author.takeIf { it.isNotBlank() }, "${counts[book.id] ?: 0} 条记录",
                                "正文已移除".takeIf { book.removedAt > 0 }).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp))
                        }
                        Icon(if (book.id in checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            null, Modifier.size(20.dp), tint = if (book.id in checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (visible.isEmpty()) item { Text("没有找到这本书", modifier = Modifier.padding(vertical = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Button(onClick = { onApply(if (checked == ids) emptySet() else checked) }, enabled = checked.isNotEmpty(),
                shape = CircleShape, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).height(52.dp)) {
                Text(if (checked == ids) "查看全部书籍" else "查看这 ${checked.size} 本书")
            }
        }
    }
    }
}
