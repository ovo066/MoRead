package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mozhi.reader.core.database.entity.AnnotationColors
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.AnnotationReplyEntity
import com.mozhi.reader.core.database.entity.AnnotationStyle
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.library.AnnotationMedia
import com.mozhi.reader.feature.reader.render.AnnotationInk
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.components.blockSheetDrag
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 划线本色 → Compose Color；面板与正文渲染共用 AnnotationInk，保证所见即所得。 */
internal fun annotationSolidColor(colorTag: String, palette: ReaderPalette): Color =
    Color(AnnotationInk.solidColor(colorTag, palette.isDark, palette.accent.toArgb()))

/**
 * 样式微调条：上排三样式、下排四预设色 + 自定义调色。即划即改浮条与讨论串头部共用；
 * 点击即回调，由调用方实时写库。自定义色以 "#RRGGBB" 存进 colorTag。
 */
@Composable
internal fun AnnotationStylePanel(
    selectedStyle: AnnotationStyle,
    selectedColor: String,
    palette: ReaderPalette,
    onChange: (AnnotationStyle, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val customActive = selectedColor.startsWith("#")
    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AnnotationStyle.entries.forEach { style ->
                StyleSample(
                    style = style,
                    colorTag = selectedColor,
                    selected = style == selectedStyle,
                    palette = palette,
                    onClick = { onChange(style, selectedColor) }
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnnotationColors.ALL.forEach { tag ->
                val color = annotationSolidColor(tag, palette)
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.9f))
                        .border(
                            width = if (tag == selectedColor) 2.dp else 0.dp,
                            color = if (tag == selectedColor) palette.onBackground else Color.Transparent,
                            shape = CircleShape
                        )
                        .clickable { onChange(selectedStyle, tag) }
                )
            }
            // 自定义调色：未启用时是描边取色器图标，启用后显示当前自定义色
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(
                        if (customActive) {
                            annotationSolidColor(selectedColor, palette).copy(alpha = 0.9f)
                        } else {
                            Color.Transparent
                        }
                    )
                    .border(
                        width = if (customActive) 2.dp else 1.dp,
                        color = if (customActive) palette.onBackground else palette.glassBorder,
                        shape = CircleShape
                    )
                    .clickable { pickerOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Colorize,
                    contentDescription = "自定义划线颜色",
                    tint = if (customActive) palette.background else palette.muted,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
    if (pickerOpen) {
        InkColorPickerDialog(
            initial = annotationSolidColor(selectedColor, palette),
            onDismiss = { pickerOpen = false },
            onConfirm = { hex ->
                pickerOpen = false
                onChange(selectedStyle, hex)
            }
        )
    }
}

/** 与外观、排版页共用笔记软件式色板；确认回调 "#RRGGBB"。 */
@Composable
private fun InkColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var preview by remember { mutableStateOf(initial) }
    val hex = String.format(Locale.ROOT, "#%06X", preview.toArgb() and 0x00FFFFFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义划线颜色") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                NoteStyleColorPalette(
                    color = preview,
                    onColorChange = { preview = it }
                )
                Text(
                    "荧光会自动降透明度垫在文字下方，直线与波浪使用原色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(hex) }) { Text("使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 单个样式示例：以「文字」实绘该样式，选中态加边框。 */
@Composable
private fun StyleSample(
    style: AnnotationStyle,
    colorTag: String,
    selected: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    val solid = annotationSolidColor(colorTag, palette)
    val label = when (style) {
        AnnotationStyle.HIGHLIGHT -> "荧光"
        AnnotationStyle.UNDERLINE -> "直线"
        AnnotationStyle.WAVY -> "波浪"
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) palette.accentContainer.copy(alpha = 0.5f) else Color.Transparent,
        contentColor = palette.onBackground,
        border = BorderStroke(1.dp, if (selected) palette.accent else palette.glassBorder)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "文字",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.drawBehind {
                    when (style) {
                        AnnotationStyle.HIGHLIGHT -> drawRoundRect(
                            color = solid.copy(alpha = if (palette.isDark) 0.20f else 0.28f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                        )
                        AnnotationStyle.UNDERLINE -> drawLine(
                            color = solid,
                            start = Offset(0f, size.height - 1.dp.toPx()),
                            end = Offset(size.width, size.height - 1.dp.toPx()),
                            strokeWidth = 2.dp.toPx(),
                            cap = StrokeCap.Round
                        )
                        AnnotationStyle.WAVY -> {
                            val amplitude = 1.6.dp.toPx()
                            val baseY = size.height - amplitude
                            val segments = AnnotationInk.wavySegments(size.width, 8.dp.toPx())
                            if (segments > 0) {
                                val path = Path()
                                val step = size.width / segments
                                path.moveTo(0f, baseY)
                                var up = true
                                var x = 0f
                                repeat(segments) {
                                    val controlY = if (up) baseY - amplitude else baseY + amplitude
                                    path.quadraticTo(x + step / 2f, controlY, x + step, baseY)
                                    x += step
                                    up = !up
                                }
                                drawPath(path, solid, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
                            }
                        }
                    }
                }
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = palette.muted
            )
        }
    }
}

/** 讨论串参与者（楼主与回复的去重作者序列，用户以 null 表示）。 */
private fun participants(
    annotations: List<AnnotationEntity>,
    replies: List<AnnotationReplyEntity>
): List<Long?> = buildList {
    annotations.forEach { if (it.personaId !in this) add(it.personaId) }
    replies.forEach { if (it.personaId !in this) add(it.personaId) }
}

@Composable
private fun ParticipantAvatar(
    personaId: Long?,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    size: androidx.compose.ui.unit.Dp
) {
    val persona = personaId?.let { id -> personas.firstOrNull { it.id == id } }
    if (personaId == null || (persona == null && personaId < 0)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(palette.accentContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "我",
                tint = palette.accent,
                modifier = Modifier.size(size * 0.62f)
            )
        }
    } else if (persona != null) {
        PersonaAvatarImage(
            name = persona.name,
            avatarPath = persona.avatarPath,
            modifier = Modifier.size(size)
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(palette.glass),
            contentAlignment = Alignment.Center
        ) {
            Text("？", color = palette.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 段评讨论串弹层：头像叠排 + 原文引用 + 楼层（楼主 = 批注想法，回复来自
 * annotation_replies）+ @ 角色行 + 胶囊输入。AI 回复流式渲染。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AnnotationDiscussionSheet(
    annotations: List<AnnotationEntity>,
    replies: List<AnnotationReplyEntity>,
    streaming: DiscussionStreaming?,
    error: String?,
    personas: List<PersonaEntity>,
    illustrations: List<IllustrationEntity>,
    palette: ReaderPalette,
    onPlayAudio: (String) -> Unit,
    onSend: (target: AnnotationEntity, text: String, respondPersonaId: Long?) -> Unit,
    onUpdateStyle: (annotationId: Long, style: AnnotationStyle, colorTag: String) -> Unit,
    onDeleteAnnotation: (Long) -> Unit,
    onDeleteReply: (Long) -> Unit,
    onCancelStreaming: () -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    // AI 回应是可选项：默认不邀请任何角色，点 @ 行头像才会让 TA 回应
    var respondTarget by remember { mutableStateOf<Long?>(null) }
    val clipboard = LocalClipboardManager.current
    val quote = annotations.firstOrNull()?.selectedText.orEmpty()
    val talkCount = annotations.count { it.note.isNotBlank() } + replies.size
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 新回复落地或 AI 开始应答时跟到底部；流式正文逐 token 长高不再拽视口，
    // 回复在视口下方生长，生成期间可自由上滑回看。
    LaunchedEffect(replies.size, streaming != null) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val people = participants(annotations, replies)
            val visible = people.take(3)
            Box(modifier = Modifier.widthIn(min = (26 + (visible.size - 1).coerceAtLeast(0) * 17).dp)) {
                visible.forEachIndexed { index, personaId ->
                    Box(modifier = Modifier.offset(x = (index * 17).dp)) {
                        ParticipantAvatar(personaId, personas, palette, 26.dp)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 9.dp)
            ) {
                Text("段落讨论", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = if (talkCount > 0) "$talkCount 条讨论" else "还没有讨论，说点什么吧",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted
                )
            }
            TextButton(onClick = onDismiss) { Text("完成", color = palette.accent) }
        }

        if (quote.isNotBlank()) {
            Surface(
                color = palette.accentContainer.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
            ) {
                Text(
                    text = "“${quote.take(160)}${if (quote.length > 160) "…" else ""}”",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.onBackground.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // fill=false：内容短时按内容高，键盘弹出时优先压缩列表、保住输入区
                .weight(1f, fill = false)
                .blockSheetDrag(listState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (annotations.isEmpty()) {
                item {
                    Text(
                        "这段批注已删除。",
                        color = palette.muted,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            annotations.forEach { annotation ->
                item(key = "annotation-${annotation.id}") {
                    AnnotationOpenerBlock(
                        annotation = annotation,
                        personas = personas,
                        palette = palette,
                        illustrations = illustrations,
                        onPlayAudio = onPlayAudio,
                        timeText = timeFormat.format(Date(annotation.createdAt)),
                        onUpdateStyle = onUpdateStyle,
                        onCopy = {
                            clipboard.setText(AnnotatedString(annotation.note))
                        },
                        onDelete = { onDeleteAnnotation(annotation.id) }
                    )
                }
                val threadReplies = replies.filter { it.annotationId == annotation.id }
                items(threadReplies, key = { "reply-${it.id}" }) { reply ->
                    ReplyRow(
                        reply = reply,
                        personas = personas,
                        palette = palette,
                        illustrations = illustrations,
                        onPlayAudio = onPlayAudio,
                        timeText = timeFormat.format(Date(reply.createdAt)),
                        onCopy = { clipboard.setText(AnnotatedString(reply.contentMarkdown)) },
                        onDelete = { onDeleteReply(reply.id) }
                    )
                }
            }
            streaming?.let { active ->
                item(key = "streaming") {
                    StreamingReplyRow(
                        streaming = active,
                        personas = personas,
                        palette = palette,
                        onCancel = onCancelStreaming
                    )
                }
            }
            error?.let { message ->
                item(key = "error") {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                    )
                }
            }
        }

        if (personas.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "@",
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.muted,
                    modifier = Modifier.padding(end = 2.dp)
                )
                personas.forEach { persona ->
                    val selected = respondTarget == persona.id
                    Surface(
                        onClick = { respondTarget = if (selected) null else persona.id },
                        shape = RoundedCornerShape(15.dp),
                        color = if (selected) palette.accentContainer.copy(alpha = 0.65f) else palette.glass,
                        contentColor = if (selected) palette.accent else palette.muted,
                        border = BorderStroke(1.dp, if (selected) palette.accent else palette.glassBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            PersonaAvatarImage(
                                name = persona.name,
                                avatarPath = persona.avatarPath,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                persona.name,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 88.dp)
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp)
                // 键盘弹出时导航栏内边距归零，避免 ime + navigationBars 双重叠加出空隙
                .windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime)),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(21.dp),
                color = palette.glass,
                contentColor = palette.onBackground,
                border = BorderStroke(1.dp, palette.glassBorder),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    modifier = Modifier.padding(start = 14.dp, end = 5.dp, top = 5.dp, bottom = 5.dp)
                ) {
                    BasicTextField(
                        value = input,
                        onValueChange = { input = it },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = palette.onBackground),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(palette.accent),
                        maxLines = 4,
                        decorationBox = { inner ->
                            Box(modifier = Modifier.padding(vertical = 7.dp)) {
                                if (input.isEmpty()) {
                                    Text(
                                        text = if (respondTarget != null) {
                                            "说点什么，TA 会回应…"
                                        } else {
                                            "写下你的想法…"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.muted
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 6.dp)
                    )
                    val canSend = input.isNotBlank() && annotations.isNotEmpty() && streaming == null
                    Surface(
                        onClick = {
                            val target = annotations.firstOrNull { it.personaId == null } ?: annotations.first()
                            onSend(target, input.trim(), respondTarget)
                            input = ""
                        },
                        enabled = canSend,
                        shape = CircleShape,
                        color = if (canSend) palette.accent else palette.glass,
                        contentColor = if (canSend) palette.background else palette.muted,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send,
                                contentDescription = "发送",
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 楼主层：作者行 + 想法正文 + （自己的批注）样式调整入口；长按复制/删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AnnotationOpenerBlock(
    annotation: AnnotationEntity,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    illustrations: List<IllustrationEntity>,
    onPlayAudio: (String) -> Unit,
    timeText: String,
    onUpdateStyle: (Long, AnnotationStyle, String) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var stylePanelOpen by remember { mutableStateOf(false) }
    val authorName = annotation.personaId?.let { id ->
        personas.firstOrNull { it.id == id }?.name ?: "已删除角色"
    } ?: "我"
    val inkTag = annotation.colorTag.ifBlank {
        annotation.personaId?.let(AnnotationColors::forPersona) ?: AnnotationColors.AMBER
    }
    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ParticipantAvatar(annotation.personaId, personas, palette, 24.dp)
                Column(modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            authorName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.accent
                        )
                        Box(
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(annotationSolidColor(inkTag, palette))
                        )
                    }
                    Text(
                        timeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        color = palette.muted
                    )
                }
                if (annotation.personaId == null) {
                    TextButton(onClick = { stylePanelOpen = !stylePanelOpen }) {
                        Text("样式", style = MaterialTheme.typography.labelSmall, color = palette.muted)
                    }
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (annotation.note.isNotBlank()) {
                        DropdownMenuItem(
                            text = { Text("复制") },
                            onClick = {
                                menuOpen = false
                                onCopy()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("删除批注") },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        }
                    )
                }
            }
            if (annotation.note.isNotBlank()) {
                AiRichText(
                    content = annotation.note,
                    palette = palette,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    "划了这段原文，还没写想法",
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            AnnotationMediaContent(
                media = AnnotationMedia.decode(annotation.mediaJson),
                text = annotation.note,
                illustrations = illustrations,
                palette = palette,
                onPlayAudio = onPlayAudio
            )
            if (stylePanelOpen && annotation.personaId == null) {
                AnnotationStylePanel(
                    selectedStyle = AnnotationStyle.fromWire(annotation.style),
                    selectedColor = AnnotationColors.normalize(annotation.colorTag),
                    palette = palette,
                    onChange = { style, color -> onUpdateStyle(annotation.id, style, color) }
                )
            }
        }
    }
}

/** 回复层：缩进一级；长按复制/删除。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReplyRow(
    reply: AnnotationReplyEntity,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    illustrations: List<IllustrationEntity>,
    onPlayAudio: (String) -> Unit,
    timeText: String,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val authorName = reply.personaId?.let { id ->
        personas.firstOrNull { it.id == id }?.name ?: "已删除角色"
    } ?: "我"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(reply.personaId, personas, palette, 22.dp)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (reply.personaId != null) palette.accent else palette.onBackground
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
            AiRichText(
                content = reply.contentMarkdown,
                palette = palette,
                modifier = Modifier.padding(top = 2.dp)
            )
            AnnotationMediaContent(
                media = AnnotationMedia.decode(reply.mediaJson),
                text = reply.contentMarkdown,
                illustrations = illustrations,
                palette = palette,
                onPlayAudio = onPlayAudio
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("复制") },
                onClick = {
                    menuOpen = false
                    onCopy()
                }
            )
            DropdownMenuItem(
                text = { Text("删除") },
                onClick = {
                    menuOpen = false
                    onDelete()
                }
            )
        }
    }
}

@Composable
private fun AnnotationMediaContent(
    media: AnnotationMedia,
    text: String,
    illustrations: List<IllustrationEntity>,
    palette: ReaderPalette,
    onPlayAudio: (String) -> Unit
) {
    if (media.isEmpty) return
    media.audioPath?.takeIf(String::isNotBlank)?.let { path ->
        CompanionVoiceBubble(
            text = text,
            clip = VoiceClipState(path = path),
            palette = palette,
            onPrepare = {},
            onRegenerate = {},
            onPlay = onPlayAudio
        )
    }
    media.illustrationId?.let { id ->
        illustrations.firstOrNull { it.id == id }?.let { illustration ->
            coil3.compose.AsyncImage(
                model = illustration.imagePath,
                contentDescription = "批注配图",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 220.dp)
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        }
    }
}

/** AI 正在回复：头像 + 流式正文 + 工具活动行 + 停止。 */
@Composable
private fun StreamingReplyRow(
    streaming: DiscussionStreaming,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    onCancel: () -> Unit
) {
    val persona = personas.firstOrNull { it.id == streaming.personaId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(streaming.personaId, personas, palette, 22.dp)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    persona?.name ?: "角色",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.accent
                )
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = palette.accent,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .size(11.dp)
                )
            }
            streaming.toolLabel?.let { label ->
                Text(
                    text = "$label…",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (streaming.text.isNotBlank()) {
                StreamingAiRichText(
                    content = streaming.text,
                    palette = palette,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Surface(
            onClick = onCancel,
            shape = CircleShape,
            color = palette.glass,
            contentColor = palette.muted,
            border = BorderStroke(1.dp, palette.glassBorder),
            modifier = Modifier.size(26.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Stop,
                    contentDescription = "停止生成",
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
