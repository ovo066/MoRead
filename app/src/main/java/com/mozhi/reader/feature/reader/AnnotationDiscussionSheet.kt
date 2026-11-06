package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.mozhi.reader.R
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalDensity
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
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.85f else 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = "我",
                tint = palette.background,
                modifier = Modifier.size(size * 0.58f)
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
 * 段评讨论串弹层，「页边批注」的写法：
 * 被讨论的原文用宋体排在顶部，并按它在正文里的样子画出划线（荧光 / 直线 / 波浪），
 * 样式与颜色就地可改；批注沿一根朱砂线挂下来，每条用一小段横线挂在线上，回复再缩进一级。
 * 不再套引文胶囊、描边卡、弹层边三层容器——纸是同一张纸。
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
    onDismiss: () -> Unit,
    defaultRespondPersonaId: Long? = null,
    onRememberRespondPersona: (Long) -> Unit = {}
) {
    var input by remember { mutableStateOf("") }
    // 点名默认落在「上次点过的角色 → 当前伴读角色 → 第一个角色」上，不必每次重选。
    // 取消点名（再点一次选中的胶囊）只对这一条发言生效，不写回记忆。
    var overridden by rememberSaveable { mutableStateOf(false) }
    var overriddenTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val fallbackTarget = personas.firstOrNull { it.id == defaultRespondPersonaId }?.id
        ?: personas.firstOrNull()?.id
    val respondTarget = if (overridden) overriddenTarget else fallbackTarget
    val respondPersona = personas.firstOrNull { it.id == respondTarget }
    val clipboard = LocalClipboardManager.current
    val ownAnnotation = annotations.firstOrNull { it.personaId == null }
    val quoteSource = ownAnnotation ?: annotations.firstOrNull()
    val quote = quoteSource?.selectedText.orEmpty()
    val talkCount = annotations.count { it.note.isNotBlank() } + replies.size
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    val focusRequester = remember { FocusRequester() }
    val seal = palette.seal

    // 新回复落地或 AI 开始应答时跟到底部；流式正文逐 token 长高不再拽视口，
    // 回复在视口下方生长，生成期间可自由上滑回看。
    LaunchedEffect(replies.size, streaming != null) {
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }

    // 共用父弹层处理 IME 避让；键盘弹出时只压缩可滚动内容，输入区留在键盘上方。
    // 只在可见性翻转时跟一次底，不逐帧跟随 IME 动画。
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val keyboardVisible by remember(density, imeInsets) {
        derivedStateOf { imeInsets.getBottom(density) > 0 }
    }
    LaunchedEffect(keyboardVisible) {
        if (!keyboardVisible) return@LaunchedEffect
        val total = listState.layoutInfo.totalItemsCount
        if (total > 0) listState.scrollToItem(total - 1)
    }
    val replyTo: (Long?) -> Unit = { personaId ->
        if (personaId != null && personas.any { it.id == personaId }) {
            overridden = true
            overriddenTarget = personaId
            onRememberRespondPersona(personaId)
        }
        runCatching { focusRequester.requestFocus() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = buildString {
                    append("段落讨论")
                    if (talkCount > 0) append(" · ").append(talkCount)
                },
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                fontWeight = FontWeight.SemiBold,
                color = seal,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) {
                Text("完成", color = palette.onBackground, fontWeight = FontWeight.SemiBold)
            }
        }

        var styleEditing by rememberSaveable { mutableStateOf(false) }

        // 摘录和样式也属于滚动内容；固定在列表外会在 IME 压缩视口后挤掉输入框。
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                // fill=false：内容短时按内容高，键盘弹出时优先压缩列表、保住输入区
                .weight(1f, fill = false)
                .blockSheetDrag(listState)
                .drawBehind {
                    // 一根朱砂线从原文下沿垂下来，越往下越淡。
                    val x = THREAD_LINE_X.toPx()
                    drawLine(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(seal.copy(alpha = 0.75f), seal.copy(alpha = 0.08f))
                        ),
                        start = Offset(x, listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "discussion-quote" }
                            ?.let { (it.offset + it.size).toFloat().coerceIn(0f, size.height) } ?: 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1.5.dp.toPx()
                    )
                },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item(key = "discussion-quote") {
                // 原文只是「在讨论哪一句」的索引，讨论才是主体：原文小一号、默认折三行，
                // 样式条收在原文标题行的小钮里，要改时再展开，不常驻占一整行。
                if (quote.isNotBlank() && quoteSource != null) {
                    val quoteStyle = AnnotationStyle.fromWire(quoteSource.style)
                    val quoteInk = annotationSolidColor(
                        quoteSource.colorTag.ifBlank {
                            quoteSource.personaId?.let(AnnotationColors::forPersona) ?: AnnotationColors.AMBER
                        },
                        palette
                    )
                    QuoteExcerpt(
                        text = quote,
                        style = quoteStyle,
                        ink = quoteInk,
                        palette = palette,
                        styleToggle = if (ownAnnotation != null) {
                            {
                                StyleToggleChip(
                                    style = quoteStyle,
                                    ink = quoteInk,
                                    expanded = styleEditing,
                                    palette = palette,
                                    onClick = { styleEditing = !styleEditing }
                                )
                            }
                        } else null,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                }
                ownAnnotation?.let { own ->
                    AnimatedVisibility(
                        visible = styleEditing,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        InlineStyleControls(
                            selectedStyle = AnnotationStyle.fromWire(own.style),
                            selectedColor = AnnotationColors.normalize(own.colorTag),
                            palette = palette,
                            onChange = { style, color -> onUpdateStyle(own.id, style, color) },
                            modifier = Modifier.padding(bottom = 10.dp)
                        )
                    }
                }
            }
            if (annotations.isEmpty()) {
                item {
                    Text(
                        "这段批注已删除。",
                        color = palette.muted,
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 8.dp, bottom = 8.dp)
                    )
                }
            }
            annotations.forEach { annotation ->
                item(key = "annotation-${annotation.id}") {
                    ThreadComment(
                        authorId = annotation.personaId,
                        personas = personas,
                        palette = palette,
                        timeText = timeFormat.format(Date(annotation.createdAt)),
                        depth = 0,
                        onReply = { replyTo(annotation.personaId) },
                        onCopy = if (annotation.note.isNotBlank()) {
                            { clipboard.setText(AnnotatedString(annotation.note)) }
                        } else null,
                        deleteLabel = "删除批注",
                        onDelete = { onDeleteAnnotation(annotation.id) }
                    ) {
                        val media = AnnotationMedia.decode(annotation.mediaJson)
                        if (annotation.note.isNotBlank()) {
                            if (!media.hasVoice) ThreadText(annotation.note, palette)
                        } else {
                            Text(
                                "划了这段原文，还没写想法",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.muted
                            )
                        }
                        AnnotationMediaContent(
                            media = media,
                            text = annotation.note,
                            illustrations = illustrations,
                            palette = palette,
                            onPlayAudio = onPlayAudio
                        )
                    }
                }
                val threadReplies = replies.filter { it.annotationId == annotation.id }
                items(threadReplies, key = { "reply-${it.id}" }) { reply ->
                    ThreadComment(
                        authorId = reply.personaId,
                        personas = personas,
                        palette = palette,
                        timeText = timeFormat.format(Date(reply.createdAt)),
                        depth = 1,
                        onReply = { replyTo(reply.personaId) },
                        onCopy = { clipboard.setText(AnnotatedString(reply.contentMarkdown)) },
                        deleteLabel = "删除",
                        onDelete = { onDeleteReply(reply.id) }
                    ) {
                        val media = AnnotationMedia.decode(reply.mediaJson)
                        if (!media.hasVoice) ThreadText(reply.contentMarkdown, palette)
                        AnnotationMediaContent(
                            media = media,
                            text = reply.contentMarkdown,
                            illustrations = illustrations,
                            palette = palette,
                            onPlayAudio = onPlayAudio
                        )
                    }
                }
            }
            if (talkCount == 0 && annotations.isNotEmpty() && streaming == null) {
                item(key = "empty") {
                    Text(
                        text = "还没有讨论，说点什么吧",
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 4.dp, bottom = 4.dp)
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
                        modifier = Modifier.padding(start = THREAD_CONTENT_START, top = 2.dp)
                    )
                }
            }
        }

        if (personas.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "@",
                    style = MaterialTheme.typography.titleSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(end = 2.dp)
                )
                personas.forEach { persona ->
                    val selected = respondTarget == persona.id
                    Surface(
                        onClick = {
                            overridden = true
                            if (selected) {
                                overriddenTarget = null
                            } else {
                                overriddenTarget = persona.id
                                onRememberRespondPersona(persona.id)
                            }
                        },
                        shape = CircleShape,
                        color = if (selected) palette.background else Color.Transparent,
                        contentColor = if (selected) palette.onBackground else palette.muted,
                        border = BorderStroke(
                            if (selected) 1.5.dp else 0.5.dp,
                            if (selected) palette.onBackground else palette.glassBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            PersonaAvatarImage(
                                name = persona.name,
                                avatarPath = persona.avatarPath,
                                modifier = Modifier.size(24.dp)
                            )
                            Text(
                                persona.name,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 96.dp)
                            )
                        }
                    }
                }
            }
        }

        val canSend = (input.isNotBlank() || respondPersona != null) && annotations.isNotEmpty() && streaming == null
        ReaderComposerBar(
            input = input,
            onInputChange = { input = it },
            placeholder = respondPersona?.let { stringResource(R.string.discussion_placeholder_persona, it.name) }
                ?: stringResource(R.string.discussion_placeholder),
            canSend = canSend,
            isStreaming = streaming != null,
            palette = palette,
            onSend = {
                val target = annotations.firstOrNull { it.personaId == null } ?: annotations.first()
                onSend(target, input.trim(), respondTarget)
                input = ""
            },
            onStop = onCancelStreaming,
            maxLines = 4,
            textFieldModifier = Modifier.focusRequester(focusRequester),
            modifier = Modifier.padding(top = 10.dp, bottom = 10.dp)
        )
    }
}

/** 朱砂线的横坐标与批注正文的起始缩进；挂钩横线从线伸到头像。 */
private val THREAD_LINE_X = 5.dp
private val THREAD_HOOK_END = 18.dp
private val THREAD_CONTENT_START = 22.dp
private val THREAD_REPLY_INDENT = 34.dp

/**
 * 被讨论的原文：宋体，按它在正文里的样子画划线——读者一眼看出这是哪一种划线、什么颜色，
 * 改样式时这里立刻跟着变。逐行取文字边界作画，换行处不会连成一整块。
 */
@Composable
private fun StyledQuote(
    text: String,
    style: AnnotationStyle,
    ink: Color,
    palette: ReaderPalette,
    maxLines: Int,
    onOverflowChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val display = remember(text) { if (text.length > QUOTE_MAX_CHARS) text.take(QUOTE_MAX_CHARS) + "…" else text }
    Text(
        text = display,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
            fontSize = QUOTE_FONT_SIZE,
            lineHeight = QUOTE_LINE_HEIGHT
        ),
        // 比讨论正文退半步：读者认得出是哪一句即可，不跟讨论抢视线。
        color = palette.onBackground.copy(alpha = if (palette.isDark) 0.74f else 0.72f),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = {
            layout = it
            onOverflowChange(it.hasVisualOverflow)
        },
        modifier = modifier.drawBehind {
            val result = layout ?: return@drawBehind
            val glyph = QUOTE_FONT_SIZE.toPx()
            for (line in 0 until result.lineCount) {
                val left = result.getLineLeft(line)
                val right = result.getLineRight(line)
                if (right - left < 1f) continue
                val baseline = result.getLineBaseline(line)
                when (style) {
                    AnnotationStyle.HIGHLIGHT -> drawRoundRect(
                        color = ink.copy(alpha = if (palette.isDark) 0.26f else 0.30f),
                        topLeft = Offset(left - 2.dp.toPx(), baseline - glyph * 1.1f),
                        size = androidx.compose.ui.geometry.Size(right - left + 4.dp.toPx(), glyph * 1.42f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                    )
                    AnnotationStyle.UNDERLINE -> drawLine(
                        color = ink,
                        start = Offset(left, baseline + 4.dp.toPx()),
                        end = Offset(right, baseline + 4.dp.toPx()),
                        strokeWidth = 1.4.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    AnnotationStyle.WAVY -> {
                        val amplitude = 1.5.dp.toPx()
                        val y = baseline + 4.dp.toPx()
                        val segments = AnnotationInk.wavySegments(right - left, 8.dp.toPx())
                        if (segments > 0) {
                            val path = Path()
                            val step = (right - left) / segments
                            path.moveTo(left, y)
                            var x = left
                            var up = true
                            repeat(segments) {
                                path.quadraticTo(x + step / 2f, if (up) y - amplitude else y + amplitude, x + step, y)
                                x += step
                                up = !up
                            }
                            drawPath(path, ink, style = Stroke(1.3.dp.toPx(), cap = StrokeCap.Round))
                        }
                    }
                }
            }
        }
    )
}

private const val QUOTE_MAX_CHARS = 200
private const val QUOTE_COLLAPSED_LINES = 3

/** 原文比讨论正文（[THREAD_FONT_SIZE]）小一档：大屏上宽幅摘录不再压过讨论。 */
private val QUOTE_FONT_SIZE = 14.sp
private val QUOTE_LINE_HEIGHT = 23.sp
private val THREAD_FONT_SIZE = 16.sp
private val THREAD_LINE_HEIGHT = 26.sp

/**
 * 原文摘录：书里的字和讨论里的话必须一眼分开。原文是小一号的宋体、带划线、垫一层淡纸色，
 * 左缘一道朱砂细线，上方一行「原文」小标；讨论用界面黑体，直接排在纸上、字号更大。
 * 默认折成三行，点摘录展开全文。[styleToggle] 放在小标行末，用来展开样式条。
 */
@Composable
private fun QuoteExcerpt(
    text: String,
    style: AnnotationStyle,
    ink: Color,
    palette: ReaderPalette,
    styleToggle: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    val expandable = expanded || overflowing
    val seal = palette.seal
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp)
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discussion_quote_label),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                color = palette.muted,
                modifier = Modifier.weight(1f)
            )
            styleToggle?.invoke()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.06f else 0.035f))
                .then(if (expandable) Modifier.clickable { expanded = !expanded } else Modifier)
                .drawBehind {
                    val inset = 10.dp.toPx()
                    drawLine(
                        color = seal.copy(alpha = 0.55f),
                        start = Offset(0f, inset),
                        end = Offset(0f, size.height - inset),
                        strokeWidth = 2.5.dp.toPx()
                    )
                }
                .padding(start = 14.dp, end = 12.dp, top = 9.dp, bottom = 9.dp)
        ) {
            StyledQuote(
                text = text,
                style = style,
                ink = ink,
                palette = palette,
                maxLines = if (expanded) Int.MAX_VALUE else QUOTE_COLLAPSED_LINES,
                onOverflowChange = { if (!expanded) overflowing = it }
            )
            if (expandable) {
                Text(
                    text = stringResource(
                        if (expanded) R.string.discussion_quote_collapse else R.string.discussion_quote_expand
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 2.dp)
                )
            }
        }
    }
}

/** 原文标题行末的样式钮：一枚当前色点 + 样式名，点开 / 收起下面的样式条。 */
@Composable
private fun StyleToggleChip(
    style: AnnotationStyle,
    ink: Color,
    expanded: Boolean,
    palette: ReaderPalette,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (expanded) palette.onBackground.copy(alpha = if (palette.isDark) 0.09f else 0.06f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(ink)
        )
        Text(
            text = stringResource(
                when (style) {
                    AnnotationStyle.HIGHLIGHT -> R.string.annotation_style_highlight
                    AnnotationStyle.UNDERLINE -> R.string.annotation_style_underline
                    AnnotationStyle.WAVY -> R.string.annotation_style_wavy
                }
            ),
            style = MaterialTheme.typography.labelMedium,
            color = palette.muted
        )
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = stringResource(R.string.discussion_style_edit),
            tint = palette.muted,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** 紧跟原文的样式条：三段小分段 + 四枚色点 + 自定义取色，点一下上面那句立刻变。 */
@Composable
private fun InlineStyleControls(
    selectedStyle: AnnotationStyle,
    selectedColor: String,
    palette: ReaderPalette,
    onChange: (AnnotationStyle, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val customActive = selectedColor.startsWith("#")
    Row(
        // 三段分段 + 五枚色点约 350dp，窄屏上横向滑动而不是被裁掉。
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.08f else 0.055f))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            AnnotationStyle.entries.forEach { style ->
                val selected = style == selectedStyle
                Box(
                    modifier = Modifier
                        .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(9.dp)) else Modifier)
                        .clip(RoundedCornerShape(9.dp))
                        .background(if (selected) palette.background else Color.Transparent)
                        .clickable { onChange(style, selectedColor) }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                ) {
                    Text(
                        text = stringResource(
                            when (style) {
                                AnnotationStyle.HIGHLIGHT -> R.string.annotation_style_highlight
                                AnnotationStyle.UNDERLINE -> R.string.annotation_style_underline
                                AnnotationStyle.WAVY -> R.string.annotation_style_wavy
                            }
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) palette.onBackground else palette.muted
                    )
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnnotationColors.ALL.forEach { tag ->
                InkDot(
                    color = annotationSolidColor(tag, palette),
                    selected = tag == selectedColor,
                    palette = palette,
                    onClick = { onChange(selectedStyle, tag) }
                )
            }
            Box(
                modifier = Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (customActive) annotationSolidColor(selectedColor, palette) else Color.Transparent)
                    .border(
                        width = if (customActive) 2.dp else 1.dp,
                        color = if (customActive) palette.background else palette.glassBorder,
                        shape = CircleShape
                    )
                    .clickable { pickerOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Colorize,
                    contentDescription = "自定义划线颜色",
                    tint = if (customActive) palette.background else palette.muted,
                    modifier = Modifier.size(13.dp)
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

@Composable
private fun InkDot(color: Color, selected: Boolean, palette: ReaderPalette, onClick: () -> Unit) {
    // 选中态：外圈一道同色细环，与纸之间留一线空隙，像印泥盒里拿起的那一枚。
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .border(if (selected) 1.5.dp else 0.dp, if (selected) color else Color.Transparent, CircleShape)
            .clickable(onClick = onClick)
            .padding(if (selected) 4.dp else 1.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** 讨论正文：界面黑体、比原文大一档——它是这张弹层的主体，是对话不是书。原文那段才用小号宋体。 */
@Composable
private fun ThreadText(content: String, palette: ReaderPalette) {
    val base = MaterialTheme.typography
    val talk = remember(base) {
        base.copy(bodyMedium = base.bodyMedium.copy(fontSize = THREAD_FONT_SIZE, lineHeight = THREAD_LINE_HEIGHT))
    }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = talk) {
        AiRichText(content = content, palette = palette)
    }
}

/**
 * 讨论串里的一条：挂在朱砂线上的头像、名字与身份、时间、宋体正文，下面一行「回复 / 复制」。
 * [depth] 为 1 时是回复：再缩进一级，用一段折角线接回上一条。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThreadComment(
    authorId: Long?,
    personas: List<PersonaEntity>,
    palette: ReaderPalette,
    timeText: String,
    depth: Int,
    onReply: () -> Unit,
    onCopy: (() -> Unit)?,
    deleteLabel: String,
    onDelete: () -> Unit,
    content: @Composable () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val seal = palette.seal
    val muted = palette.muted
    val authorName = authorId?.let { id ->
        personas.firstOrNull { it.id == id }?.name ?: "已删除角色"
    } ?: "我"
    val avatarSize = if (depth == 0) 30.dp else 26.dp
    val start = if (depth == 0) THREAD_CONTENT_START else THREAD_CONTENT_START + THREAD_REPLY_INDENT
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val cy = 10.dp.toPx() + avatarSize.toPx() / 2f
                if (depth == 0) {
                    drawLine(
                        color = seal.copy(alpha = 0.7f),
                        start = Offset(THREAD_LINE_X.toPx(), cy),
                        end = Offset(THREAD_HOOK_END.toPx(), cy),
                        strokeWidth = 1.5.dp.toPx()
                    )
                } else {
                    // 折角线：从上一条头像正下方垂下，圆角转向这条回复的头像。
                    val x = THREAD_CONTENT_START.toPx() + 15.dp.toPx()
                    val end = start.toPx() - 4.dp.toPx()
                    val r = 7.dp.toPx()
                    val path = Path().apply {
                        moveTo(x, 0f)
                        lineTo(x, cy - r)
                        quadraticTo(x, cy, x + r, cy)
                        lineTo(end, cy)
                    }
                    drawPath(path, muted.copy(alpha = 0.45f), style = Stroke(1.2.dp.toPx()))
                }
            }
            .combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
            .padding(start = start, top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(authorId, personas, palette, avatarSize)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = avatarSize)) {
                Text(
                    authorName,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 140.dp)
                )
                Text(
                    text = stringResource(if (authorId == null) R.string.discussion_role_me else R.string.discussion_role_persona),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier
                        .padding(start = 7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.onBackground.copy(alpha = if (palette.isDark) 0.09f else 0.06f))
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
                Text(
                    timeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            Column(Modifier.padding(top = 2.dp)) { content() }
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ThreadAction(stringResource(R.string.discussion_reply), palette, onReply)
                onCopy?.let { copy -> ThreadAction(stringResource(R.string.discussion_copy), palette, copy) }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(deleteLabel) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ThreadAction(label: String, palette: ReaderPalette, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = palette.muted,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    )
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
        // 有语音就只给语音条，文字收在条内，点开才显示，避免同一句话说两遍。
        CompanionVoiceBubble(
            text = text,
            clip = VoiceClipState(path = path),
            palette = palette,
            onPrepare = {},
            onRegenerate = {},
            onPlay = onPlayAudio,
            expandedContent = { ThreadText(text, palette) }
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

/** AI 正在回复：挂在讨论串末尾的一条回复，头像 + 流式正文 + 工具活动行 + 停止。 */
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
            .padding(start = THREAD_CONTENT_START + THREAD_REPLY_INDENT, top = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        ParticipantAvatar(streaming.personaId, personas, palette, 26.dp)
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 26.dp)) {
                Text(
                    persona?.name ?: "角色",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.onBackground
                )
                CircularProgressIndicator(
                    strokeWidth = 1.5.dp,
                    color = palette.seal,
                    modifier = Modifier
                        .padding(start = 8.dp)
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
                val base = MaterialTheme.typography
                val serif = remember(base) {
                    base.copy(bodyMedium = base.bodyMedium.copy(fontSize = THREAD_FONT_SIZE, lineHeight = THREAD_LINE_HEIGHT))
                }
                MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = serif) {
                    StreamingAiRichText(
                        content = streaming.text,
                        palette = palette,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Surface(
            onClick = onCancel,
            shape = CircleShape,
            color = palette.onBackground.copy(alpha = if (palette.isDark) 0.10f else 0.06f),
            contentColor = palette.muted,
            modifier = Modifier.size(28.dp)
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
