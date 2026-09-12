package com.mozhi.reader.feature.companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mozhi.reader.core.database.entity.BookEntity
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBookPicker(books: List<BookEntity>, selected: Set<Long>, onToggle: (Long) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(books, query) { books.filter { query.isBlank() || it.title.contains(query, true) || it.author.contains(query, true) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.66f).navigationBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("重点讨论", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(selected.size.toString() + "/4", style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onDismiss) { Text("完成") }
            }
            Text("可以不选，伴读也能按需查书。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(query, { query = it }, placeholder = { Text("书名或作者") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (filtered.isEmpty()) item { Text("没有找到书籍", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(filtered, key = { it.id }) { book -> LibraryBookChoice(book, book.id in selected, true) { onToggle(book.id) } }
            }
        }
    }
}

@Composable
internal fun LibraryBookChoice(book: BookEntity, selected: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() }).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp, 54.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                AsyncImage(book.coverPath?.let(::File), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(book.author.ifBlank { "作者未知" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Checkbox(selected, null, enabled = enabled)
        }
    }
}
