package com.mozhi.reader.feature.review

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.feature.reader.AiRichText
import com.mozhi.reader.feature.reader.companionChatPalette
import com.mozhi.reader.ui.theme.LocalReadingReviewStyle
import com.mozhi.reader.ui.theme.isDarkTheme

@Composable
internal fun ReviewComposerDialog(
    entries: List<ReviewEntry>, personas: List<PersonaEntity>, draft: ReviewDraft?, comment: Boolean,
    onDismiss: () -> Unit, onGenerate: (List<ReviewEntry>, Long, String) -> Unit,
    onEdit: (String, String) -> Unit, onStop: () -> Unit, onSave: () -> Unit
) {
    var bookId by rememberSaveable { mutableStateOf(entries.firstOrNull()?.book?.id) }
    var personaId by rememberSaveable { mutableStateOf<Long?>(null) }
    var instruction by rememberSaveable { mutableStateOf("") }
    var excluded by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var showSources by rememberSaveable { mutableStateOf(true) }
    var confirmClose by remember { mutableStateOf(false) }
    var hadDraft by remember { mutableStateOf(false) }
    LaunchedEffect(draft) {
        if (draft != null) hadDraft = true
        else if (hadDraft) onDismiss()
    }
    val candidates = remember(entries, bookId) { bookId?.let { reviewSourceSelection(entries, it) }.orEmpty() }
    val sources = candidates.filter { it.key !in excluded }
    val close = {
        if (draft?.saving != true) {
            if (draft != null || instruction.isNotBlank()) confirmClose = true else onDismiss()
        }
    }
    ReviewPageDialog(if (comment) "AI 点评笔记" else "共创读书笔记", close, bottomBar = {
        val actionColors = ButtonDefaults.buttonColors(containerColor = LocalReadingReviewStyle.current.accent,
            contentColor = if (isDarkTheme()) MaterialTheme.colorScheme.background else Color.White)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (draft?.running == true) FilledTonalButton(onClick = onStop, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(60.dp)) { Text("停止生成") }
            else if (draft != null) Button(onClick = onSave, colors = actionColors, shape = CircleShape, enabled = !draft.saving && draft.content.isNotBlank(), modifier = Modifier.fillMaxWidth().height(60.dp)) {
                Text(if (draft.saving) "正在保存……" else "保存笔记", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            } else Button(onClick = { personaId?.let { onGenerate(sources, it, instruction) } }, shape = CircleShape,
                colors = actionColors, enabled = sources.isNotEmpty() && personas.any { it.id == personaId }, modifier = Modifier.fillMaxWidth().height(60.dp).testTag("review-generate")) {
                Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(22.dp)); Spacer(Modifier.width(10.dp)); Text("生成草稿", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (draft == null) {
                ReviewComposerBookChoice(entries.map { it.book }.distinctBy { it.id }, bookId) { bookId = it; excluded = arrayListOf() }
                ReviewCompanionPanel(personas, personaId, { personaId = it })
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("这次想聊什么", style = MaterialTheme.typography.titleSmall)
                        ReviewWritingField(instruction, { instruction = it },
                            if (comment) "挑战一下我的观点，或提出另一种解释……" else "例如，围绕“记忆与遗忘”，保留我的个人感受……",
                            Modifier.fillMaxWidth(), minLines = 3)
                    }
                }
                val available = entries.count { it.book.id == bookId }
                if (candidates.size < available) Text("本次展示当前顺序的前 ${candidates.size} 条完整记录（最多 20 条、2.4 万字）。可先调整筛选范围，再选择其他记录。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column {
                        Row(Modifier.fillMaxWidth().clickable { showSources = !showSources }.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.FormatQuote, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("已选 ${sources.size} 条素材", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                            Icon(if (showSources) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (showSources) "收起素材" else "展开素材")
                        }
                        if (showSources) candidates.forEach { entry ->
                            val checked = entry.key !in excluded
                            Row(Modifier.fillMaxWidth().clickable { excluded = ArrayList(if (checked) excluded + entry.key else excluded - entry.key) }
                                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                                    Text("${entry.author} · ${entry.locationLabel}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    if (entry.title.isNotBlank()) Text(entry.title, style = MaterialTheme.typography.titleSmall)
                                    if (entry.quote.isNotBlank()) Text(entry.quote, style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.bodyMedium), modifier = Modifier.padding(top = 6.dp))
                                    if (entry.body.isNotBlank()) Text(entry.body, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                                }
                                Icon(if (checked) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                    if (checked) "已选择素材" else "未选择素材", Modifier.size(20.dp), tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            } else {
                Text("${draft.sources.first().book.title} · ${draft.sources.size} 条素材", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                draft.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (draft.running) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    AiRichText(draft.content.ifBlank { "正在把摘录与想法连接起来……" }, companionChatPalette())
                } else {
                    if (draft.error != null && !draft.saving) FilledTonalButton(shape = CircleShape, onClick = {
                        onGenerate(draft.sources, draft.personaId, instruction)
                    }) { Text("重新生成") }
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                            ReviewWritingField(draft.title, { if (!draft.saving) onEdit(it, draft.content) }, "笔记标题", Modifier.fillMaxWidth(), minLines = 1)
                            ReviewWritingField(draft.content, { if (!draft.saving) onEdit(draft.title, it) }, "草稿 · 可以直接修改", Modifier.fillMaxWidth(), minLines = 12)
                        }
                    }
                }
            }
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { confirmClose = false }, title = { Text("离开共创？") },
        text = { Text("尚未保存的草稿和输入将被丢弃。") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃草稿") } },
        dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("继续编辑") } })
}

@Composable
private fun ReviewComposerBookChoice(books: List<BookEntity>, selected: Long?, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(onClick = { expanded = true }, shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Book, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("这次回顾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(books.firstOrNull { it.id == selected }?.title ?: "选择一本书", style = MaterialTheme.typography.titleSmall)
                }
                Icon(Icons.Outlined.ExpandMore, "选择回顾书籍", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            books.forEach { book -> DropdownMenuItem(text = { Text(book.title) }, onClick = { onSelect(book.id); expanded = false }) }
        }
    }
}
