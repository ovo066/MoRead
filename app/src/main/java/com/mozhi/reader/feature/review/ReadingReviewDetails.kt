package com.mozhi.reader.feature.review

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.feature.reader.AnnotationStylePanel
import com.mozhi.reader.feature.reader.annotationSolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.mozhi.reader.core.datastore.ReviewShareTemplate
import com.mozhi.reader.feature.reader.AiRichText
import com.mozhi.reader.feature.reader.DiscussionUiState
import com.mozhi.reader.feature.reader.companionChatPalette
import com.mozhi.reader.ui.components.MoReadPageDialog
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.MoReadIcons
import com.mozhi.reader.ui.components.safeTopPadding
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.ReadingReviewTheme
import com.mozhi.reader.ui.theme.LocalReadingReviewStyle
import com.mozhi.reader.ui.theme.ReviewLocationShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ReviewPageDialog(
    title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit = {},
    bottomBar: @Composable () -> Unit = {}, closeAtEnd: Boolean = false, tonalBackground: Boolean = false,
    quietHeader: Boolean = false, content: @Composable ColumnScope.() -> Unit
) {
    ReadingReviewTheme {
    MoReadPageDialog(onDismissRequest = onDismiss) {
        Box(Modifier.fillMaxSize().background(when {
            tonalBackground -> MaterialTheme.colorScheme.primary.copy(alpha = .10f).compositeOver(MaterialTheme.colorScheme.background)
            closeAtEnd -> LocalReadingReviewStyle.current.paper
            else -> androidx.compose.ui.graphics.Color.Transparent
        })
            .imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 740.dp).fillMaxSize().safeTopPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!closeAtEnd && quietHeader) IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.ChevronLeft, "关闭回顾页面", Modifier.size(28.dp), tint = LocalReadingReviewStyle.current.toolbarInk)
                    } else if (!closeAtEnd) FilledTonalIconButton(onClick = onDismiss, shape = CircleShape,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭回顾页面") }
                    Text(title, style = if (closeAtEnd) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) else MaterialTheme.typography.titleMedium,
                        textAlign = if (closeAtEnd) TextAlign.Start else TextAlign.Center,
                        color = if (closeAtEnd) LocalReadingReviewStyle.current.caption else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = if (closeAtEnd) 24.dp else 0.dp))
                    actions()
                    if (closeAtEnd) IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭回顾页面", Modifier.size(26.dp), tint = LocalReadingReviewStyle.current.toolbarInk) }
                }
                Column(Modifier.fillMaxWidth().weight(1f), content = content)
                bottomBar()
            }
        }
    }
    }
}

@Composable
internal fun ReviewDetailDialog(
    entry: ReviewEntry, personas: List<PersonaEntity>, thread: DiscussionUiState,
    onDismiss: () -> Unit, onLocate: () -> Unit,
    onEdit: (String, String) -> Unit, onDelete: () -> Unit, onExport: () -> Unit,
    onSendReply: (String, Long?) -> Unit, onStopReply: () -> Unit, onCommentNote: () -> Unit,
    onStyleChange: (AnnotationStyle, String) -> Unit = { _, _ -> }
) {
    ReadingReviewTheme {
    val palette = companionChatPalette()
    val visual = LocalReadingReviewStyle.current
    val clipboard = LocalClipboardManager.current
    var edit by rememberSaveable(entry.key) { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reply by rememberSaveable(entry.key) { mutableStateOf("") }
    var inviteAi by rememberSaveable(entry.key) { mutableStateOf(false) }
    var personaId by rememberSaveable(entry.key) { mutableStateOf<Long?>(null) }
    var writingReply by rememberSaveable(entry.key) { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    ReviewPageDialog("", onDismiss, quietHeader = true, actions = {
        Box {
            IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, "更多笔记操作", tint = visual.toolbarInk) }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("复制全文") }, onClick = { clipboard.setText(AnnotatedString(reviewMarkdown(listOf(entry)))); more = false })
                DropdownMenuItem(text = { Text("编辑想法") }, onClick = { more = false; edit = true })
            }
        }
    }, bottomBar = {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background)))
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.weight(1f)) {
                    Row(Modifier.height(64.dp).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(reviewMarkdown(listOf(entry)))) }) { Icon(Icons.Outlined.ContentCopy, "复制全文", Modifier.size(24.dp), tint = visual.toolbarInk) }
                        if (entry.annotation != null) IconButton(onClick = { styleOpen = true }) { Icon(Icons.Outlined.Palette, "划线样式", Modifier.size(24.dp), tint = visual.toolbarInk) }
                        IconButton(onClick = onExport) { Icon(Icons.Outlined.IosShare, "导出卡片与文字", Modifier.size(24.dp), tint = visual.toolbarInk) }
                        IconButton(onClick = { deleting = true }) { Icon(Icons.Outlined.DeleteOutline, "删除这条记录", Modifier.size(24.dp), tint = visual.toolbarInk) }
                    }
                }
                Spacer(Modifier.width(12.dp))
                FilledIconButton(onClick = onLocate, enabled = entry.canLocate, shape = ReviewLocationShape, modifier = Modifier.size(66.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = visual.accent,
                        contentColor = if (isDarkTheme()) MaterialTheme.colorScheme.background else Color.White)) {
                    Icon(Icons.Outlined.MyLocation, if (entry.canLocate) "回到原文" else "原文暂不可定位", Modifier.size(28.dp))
                }
            }
        }
    }) {
        LazyColumn(Modifier.fillMaxSize().testTag("review-detail-list"), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Column(Modifier.fillMaxWidth().heightIn(min = 252.dp)) {
                Text("“", fontFamily = FontFamily.Serif, fontSize = 52.sp, lineHeight = 44.sp, color = visual.accent.copy(alpha = .48f), modifier = Modifier.padding(start = 20.dp))
                Spacer(Modifier.height(28.dp))
                SelectionContainer {
                    Text(entry.quote.ifBlank { entry.title.ifBlank { "读书笔记" } },
                        style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 40.sp)))
                }
                Spacer(Modifier.height(34.dp))
                }
                Text("${entry.book.title} · ${entry.locationLabel} · ${SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp))}${if (entry.book.removedAt > 0) " · 正文已移除" else ""}",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp), color = visual.caption)
                entry.annotation?.let { annotation ->
                    ReviewDetailInkRow(annotation.colorTag, annotationSolidColor(annotation.colorTag, palette),
                        AnnotationStyle.fromWire(annotation.style), visual.accent, { color -> onStyleChange(AnnotationStyle.fromWire(annotation.style), color) })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entry.personaId != null) {
                        PersonaAvatarImage(entry.author.removePrefix("AI · "), entry.authorAvatarPath, Modifier.size(30.dp), MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (entry.personaId == null) "我的想法" else entry.author, color = visual.accentText,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = .5.sp), modifier = Modifier.weight(1f))
                    IconButton(onClick = { edit = true }) { Icon(Icons.Outlined.Edit, "编辑想法", Modifier.size(16.dp), tint = palette.muted.copy(alpha = .65f)) }
                }
                if (entry.body.isBlank()) Text("还没有想法", style = MaterialTheme.typography.bodyMedium, color = palette.muted)
                else SelectionContainer { ReviewNoteText(entry.body) }
            }
            if (entry.annotation != null) {
                items(thread.replies, key = { it.id }) { item ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val author = personas.firstOrNull { it.id == item.personaId }
                            if (item.personaId != null) {
                                PersonaAvatarImage(author?.name ?: "已删除角色", author?.avatarPath, Modifier.size(36.dp), MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.width(12.dp))
                            }
                            Text(if (item.personaId != null) "${author?.name ?: "已删除角色"}的批注" else "我的想法",
                                color = visual.accentText, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = .5.sp))
                        }
                        Box(Modifier.padding(start = 18.dp, top = 8.dp).drawBehind {
                            drawLine(visual.accent.copy(alpha = .48f), Offset.Zero, Offset(0f, size.height), strokeWidth = 1.5.dp.toPx())
                        }.padding(start = 28.dp)) {
                            SelectionContainer { ReviewNoteText(item.contentMarkdown) }
                        }
                    }
                }
                thread.streaming?.let { streaming -> item {
                    Text("AI · ${personas.firstOrNull { it.id == streaming.personaId }?.name ?: "伴读"}", color = palette.accent)
                    AiRichText(streaming.text.ifBlank { streaming.toolLabel ?: "正在思考这段话……" }, palette)
                    FilledTonalButton(onClick = onStopReply, shape = CircleShape) { Text("停止生成") }
                } }
                item {
                    thread.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 12.dp)) }
                    FilledTonalButton(onClick = { writingReply = true }, shape = CircleShape, modifier = Modifier.padding(start = 46.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.primary)) {
                        Text("接着聊", style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp))
                    }
                }
            } else item {
                FilledTonalButton(onClick = onCommentNote, shape = CircleShape) { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("邀请 AI 点评") }
            }
        }
    }
    if (writingReply) ReviewPageDialog("接着聊", onDismiss = { writingReply = false }, actions = {
        FilledIconButton(onClick = { onSendReply(reply, if (inviteAi) personaId else null); reply = ""; writingReply = false },
            shape = CircleShape, enabled = thread.streaming == null && (reply.isNotBlank() || (inviteAi && personaId != null))) {
            Icon(MoReadIcons.Send, "发送", Modifier.size(20.dp))
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Text(entry.quote, style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp)), color = palette.muted)
            ReviewWritingField(reply, { reply = it }, "顺着这句话，写一点新的想法……", Modifier.fillMaxWidth().testTag("review-reply"))
            ReviewCompanionPanel(personas, personaId, { personaId = it }, inviteAi, { invited ->
                inviteAi = invited
                personaId = if (invited) personaId ?: personas.firstOrNull()?.id else null
            })
        }
    }
    if (edit) ReviewEditDialog(entry, onDismiss = { edit = false }, onSave = { title, content -> onEdit(title, content); edit = false })
    if (styleOpen) entry.annotation?.let { annotation ->
        AlertDialog(onDismissRequest = { styleOpen = false }, title = { Text("划线样式") }, text = {
            AnnotationStylePanel(AnnotationStyle.fromWire(annotation.style), annotation.colorTag, palette, onStyleChange)
        }, confirmButton = { TextButton(onClick = { styleOpen = false }) { Text("完成") } })
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这条${entry.kindLabel}？") },
        text = { Text(if (entry.annotation != null) "这条划线、想法和关联讨论将一并删除。书籍原文不受影响。" else "删除这篇笔记，书籍原文和其他划线不受影响。") },
        confirmButton = { TextButton(onClick = { deleting = false; onDelete() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } })
    }
}

@Composable
private fun ReviewEditDialog(entry: ReviewEntry, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf(entry.title) }
    var body by rememberSaveable { mutableStateOf(entry.body) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val close = { if (title != entry.title || body != entry.body) confirmDiscard = true else onDismiss() }
    ReviewPageDialog("写下想法", close, actions = {
        FilledTonalButton(onClick = { onSave(title, body) }, shape = CircleShape,
            enabled = entry.annotation != null || title.isNotBlank() || body.isNotBlank()) { Text("保存") }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (entry.quote.isNotBlank()) Text(entry.quote, style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.bodyLarge), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (entry.note != null) ReviewWritingField(title, { title = it }, "给这篇笔记起个名字", Modifier.fillMaxWidth(), minLines = 1)
            ReviewWritingField(body, { body = it }, "此刻想到了什么？", Modifier.fillMaxWidth(), minLines = 10)
        }
    }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("放弃未保存的修改？") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("放弃修改") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续写") } })
}

@Composable
internal fun ReviewPagerDialog(entries: List<ReviewEntry>, onDismiss: () -> Unit,
    onOpen: (ReviewEntry) -> Unit, onLocate: (ReviewEntry) -> Unit, onExport: (ReviewEntry) -> Unit) {
    val pager = rememberPagerState { entries.size }
    ReviewPageDialog(if (entries.isEmpty()) "回顾完成" else "%02d / %02d".format(pager.currentPage + 1, entries.size), onDismiss, closeAtEnd = true) {
        if (entries.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("这些记录已移除或不在当前可读范围内") }
        else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
            HorizontalPager(pager, key = { entries[it].key }, beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize().testTag("review-pager")) { index ->
                val entry = entries[index]
                Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)
                    .graphicsLayer {
                        val motion = reviewCardMotion(pager.currentPage - index + pager.currentPageOffsetFraction)
                        scaleX = motion.scale; scaleY = motion.scale; alpha = motion.alpha
                        rotationZ = motion.rotation; translationY = motion.dropDp.dp.toPx()
                    }.padding(start = 16.dp, end = 16.dp, top = 48.dp, bottom = 52.dp)) {
                    Text("“", fontFamily = FontFamily.Serif, fontSize = 58.sp, lineHeight = 46.sp,
                        color = LocalReadingReviewStyle.current.accent.copy(alpha = .48f), modifier = Modifier.padding(start = 26.dp))
                    Spacer(Modifier.height(30.dp))
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Top) {
                        SelectionContainer { Text(entry.quote.ifBlank { reviewPlainText(entry.body) },
                            style = reviewQuoteStyle(entry.book.id, MaterialTheme.typography.headlineSmall.copy(fontSize = 28.sp, lineHeight = 50.sp))) }
                        Spacer(Modifier.height(24.dp))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    Text(entry.book.title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(top = 18.dp))
                    val date = SimpleDateFormat("MM-dd", Locale.getDefault()).format(Date(entry.timestamp))
                    Text("${entry.locationLabel} · $date ${if (entry.annotation != null) "划下" else "写下"}" +
                        if (entry.annotation != null && entry.body.isNotBlank()) " · ${if (entry.personaId == null) "你写过一条笔记" else entry.author}" else "",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = LocalReadingReviewStyle.current.toolbarInk, modifier = Modifier.padding(top = 5.dp))
                }
            }
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                val firstDot = (pager.currentPage - 3).coerceIn(0, (entries.size - 7).coerceAtLeast(0))
                (firstDot until minOf(firstDot + 7, entries.size)).forEach { index ->
                    val active = pager.currentPage == index
                    val height by animateDpAsState(if (active) 20.dp else 6.dp, label = "回顾进度高度")
                    val color by animateColorAsState(LocalReadingReviewStyle.current.accent.copy(alpha = if (active) 1f else .38f), label = "回顾进度颜色")
                    Box(Modifier.width(6.dp).height(height).clip(CircleShape).background(color))
                }
            }
            }
            val entry = entries[pager.currentPage.coerceIn(entries.indices)]
            Text("左右滑动，翻阅摘录", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold), color = LocalReadingReviewStyle.current.caption,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 18.dp))
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { onLocate(entry) }, enabled = entry.canLocate, shape = CircleShape, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = LocalReadingReviewStyle.current.accent)) { Icon(Icons.Outlined.MyLocation, "回到原文", Modifier.size(26.dp)) }
                Button(onClick = { onOpen(entry) }, shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = LocalReadingReviewStyle.current.accent,
                        contentColor = if (isDarkTheme()) MaterialTheme.colorScheme.background else Color.White),
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp)) { Icon(Icons.Outlined.Edit, null, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text("记一笔", fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
                FilledTonalIconButton(onClick = { onExport(entry) }, shape = CircleShape, modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                        contentColor = LocalReadingReviewStyle.current.accent)) { Icon(Icons.Outlined.IosShare, "分享回顾卡片", Modifier.size(26.dp)) }
            }
        }
    }
}

@Composable
internal fun ReviewExportDialog(entry: ReviewEntry, onDismiss: () -> Unit, onMarkdown: () -> Unit,
    onImage: (ReviewCardStyle, ReviewExportOptions) -> Unit,
    onSaveTemplate: suspend (ReviewShareTemplate) -> Unit = {}, onDeleteTemplate: suspend (String) -> Unit = {}) {
    ReadingReviewTheme {
    val dark = isDarkTheme()
    var style by rememberSaveable { mutableStateOf(if (dark) ReviewCardStyle.NIGHT else ReviewCardStyle.PAPER) }
    var thought by rememberSaveable { mutableStateOf(true) }
    var book by rememberSaveable { mutableStateOf(true) }
    var date by rememberSaveable { mutableStateOf(true) }
    var watermark by rememberSaveable { mutableStateOf(true) }
    val settings = LocalReviewReaderSettings.current
    val scope = rememberCoroutineScope()
    var templateId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingTemplate by remember { mutableStateOf<ReviewShareTemplate?>(null) }
    var menu by remember { mutableStateOf(false) }
    var deletingTemplate by remember { mutableStateOf(false) }
    var templateError by remember { mutableStateOf<String?>(null) }
    var pendingSaved by remember { mutableStateOf<ReviewShareTemplate?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val templates = settings.reviewShareTemplates.let { stored -> pendingSaved?.let { saved -> stored.filterNot { it.id == saved.id } + saved } ?: stored }
    val template = templates.firstOrNull { it.id == templateId }
    val font = reviewFontSpec(settings, entry.book.id, dark)
    val base = ReviewExportOptions(thought, book, date, watermark, font)
    val options = reviewTemplateOptions(base, template, settings, entry.book.id, dark)
    fun newTemplate() = (template ?: ReviewShareTemplate("", "${style.label} · 自定", style.background, style.foreground, style.accent))
        .copy(id = java.util.UUID.randomUUID().toString(), name = template?.let { "${it.name} · 副本" } ?: "${style.label} · 自定")
    val preview by produceState<Result<Bitmap>?>(null, entry, style, options) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { renderReviewCard(entry, style, options, width = 540) } }
    }
    val clipboard = LocalClipboardManager.current
    ReviewPageDialog("导出卡片", onDismiss, tonalBackground = true, actions = {
        FilledTonalIconButton(onClick = { editingTemplate = newTemplate() }, shape = CircleShape, modifier = Modifier.size(44.dp),
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = LocalReadingReviewStyle.current.accentText)) { Icon(Icons.Outlined.Add, "新建自定义模板", Modifier.size(24.dp)) }
        if (template != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "管理自定义模板") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("编辑模板") }, onClick = { menu = false; editingTemplate = template })
                DropdownMenuItem(text = { Text("另存为新模板") }, onClick = { menu = false; editingTemplate = newTemplate() })
                DropdownMenuItem(text = { Text("删除模板") }, onClick = { menu = false; deletingTemplate = true })
            }
        }
    }, bottomBar = {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onImage(style, options) }, enabled = preview?.isSuccess == true, shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = LocalReadingReviewStyle.current.accent,
                    contentColor = if (dark) MaterialTheme.colorScheme.background else Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 60.dp)) { Icon(Icons.Outlined.IosShare, null, Modifier.size(24.dp)); Spacer(Modifier.width(10.dp)); Text("分享图片", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            FilledTonalIconButton(onClick = { clipboard.setText(AnnotatedString(reviewMarkdown(listOf(entry)))) }, shape = CircleShape, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.ContentCopy, "复制文字") }
            FilledTonalIconButton(onClick = onMarkdown, shape = CircleShape, modifier = Modifier.size(56.dp)) { Icon(Icons.Outlined.Description, "导出 Markdown") }
        }
    }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.fillMaxWidth().height(350.dp), contentAlignment = Alignment.Center) {
                preview?.getOrNull()?.let { bitmap ->
                    Image(bitmap.asImageBitmap(), "导出图片预览", Modifier.height(350.dp).aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .clip(MaterialTheme.shapes.large).testTag("review-export-preview"), contentScale = ContentScale.Fit)
                }
            }
            preview?.exceptionOrNull()?.let { Text(it.message ?: "预览失败", color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewCardStyle.entries.filter { it != ReviewCardStyle.COVER || !entry.book.coverPath.isNullOrBlank() }.forEach { item ->
                    ReviewTemplateThumbnail(entry, item, base, item.label, template == null && style == item) { style = item; templateId = null }
                }
                templates.forEach { custom ->
                    ReviewTemplateThumbnail(entry, ReviewCardStyle.PAPER, reviewTemplateOptions(base, custom, settings, entry.book.id, dark),
                        custom.name, templateId == custom.id) { templateId = custom.id }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewExportOption("书名章节", Icons.Outlined.Book, book, { book = !book }, Modifier.weight(1f))
                ReviewExportOption("日期", Icons.Outlined.CalendarToday, date, { date = !date }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReviewExportOption(if (entry.personaId == null) "我的想法" else "AI 批注", Icons.Outlined.Edit, thought, { thought = !thought }, Modifier.weight(1f))
                ReviewExportOption("水印", Icons.Outlined.BookmarkBorder, watermark, { watermark = !watermark }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    editingTemplate?.let { initial -> ReviewTemplateEditor(initial, entry, base, onDismiss = { editingTemplate = null }, onSave = { saved ->
        onSaveTemplate(saved); pendingSaved = saved; templateId = saved.id
    }) }
    if (deletingTemplate && template != null) AlertDialog(onDismissRequest = { if (!deleting) deletingTemplate = false },
        title = { Text("删除「${template.name}」？") }, text = { Text(templateError ?: "删除后仍可使用所有预设模板。") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                try { onDeleteTemplate(template.id); if (pendingSaved?.id == template.id) pendingSaved = null; templateId = null; deletingTemplate = false; templateError = null }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { templateError = error.message ?: "删除失败，请重试" }
                finally { deleting = false }
            }
        }) { Text("删除") } }, dismissButton = { TextButton(enabled = !deleting, onClick = { deletingTemplate = false }) { Text("取消") } })
    }
}

@Composable
private fun ReviewExportOption(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier, shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) {
        Box(Modifier.height(76.dp).fillMaxWidth()) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(icon, null, Modifier.size(20.dp))
                Text(label, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold))
            }
            if (selected) Icon(Icons.Outlined.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(8.dp).size(14.dp))
        }
    }
}

@Composable
internal fun ReviewWritingField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, minLines: Int = 4) {
    BasicTextField(value, onChange, modifier = modifier, minLines = minLines,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface, lineHeight = 30.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
            Box { if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 30.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)); field() }
        })
}

internal fun reviewDate(timestamp: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.getDefault()).format(Date(timestamp))
