package com.mozhi.reader.feature.bookdetail

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import com.mozhi.reader.ui.theme.moReadMetrics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.mikepenz.markdown.m3.Markdown
import com.mozhi.reader.core.database.entity.BookEntity
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.mozhi.reader.ui.components.blockSheetDrag
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.BookReadState
import com.mozhi.reader.core.database.entity.BookTagEntity
import com.mozhi.reader.core.database.entity.BookTagRefEntity
import com.mozhi.reader.core.database.entity.BookmarkEntity
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.NoteEntity
import com.mozhi.reader.core.database.entity.label
import com.mozhi.reader.core.database.entity.readState
import com.mozhi.reader.core.database.entity.ShelfGroupEntity
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.CompanionAutonomySettings
import com.mozhi.reader.core.datastore.PendingReaderImage
import com.mozhi.reader.ai.media.BookCoverGenerationProgress
import com.mozhi.reader.ai.media.OnlineBookCover
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.core.library.BookReadSpan
import com.mozhi.reader.core.library.readFraction
import com.mozhi.reader.core.library.readPercent
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.core.library.NoteRepository
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.ReadingHeatmap
import com.mozhi.reader.ui.components.RingGauge
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.components.StatCell
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadDropdownMenu
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.components.safeTopPadding
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.sealColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 书籍详情页（design/ui-adaptation-plan.md §3），独立 destination。 */
internal const val MAX_TAGS = 12

@Composable
internal fun DetailTopBar(
    onBack: () -> Unit,
    onEditInfo: () -> Unit,
    onChangeCover: () -> Unit,
    onPickGroup: () -> Unit,
    onPickTags: () -> Unit,
    groupName: String,
    tagCount: Int,
    onBookmarks: () -> Unit
) {
    var editMenu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 阅读页退场后才恢复状态栏，详情页从首帧起就要保留稳定的顶部安全区。
            .safeTopPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
        }
        Text(
            text = "书籍详情",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // 编辑收纳了信息/封面与书架整理（分组、标签），正文区不再为它留一整张卡。
            Box {
                FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                    IconButton(onClick = { editMenu = true }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑")
                    }
                }
                MoReadStableDropdownMenu(
                    expanded = editMenu,
                    onDismissRequest = { editMenu = false },
                    width = 224.dp
                ) {
                    MoReadMenuItem(
                        text = "书名与作者",
                        icon = Icons.Outlined.Edit,
                        onClick = { editMenu = false; onEditInfo() }
                    )
                    MoReadMenuItem(
                        text = "更换封面",
                        icon = Icons.Outlined.Image,
                        onClick = { editMenu = false; onChangeCover() }
                    )
                    MoReadMenuDivider()
                    MoReadMenuItem(
                        text = "所属分组",
                        icon = Icons.Outlined.Folder,
                        trailingText = groupName,
                        onClick = { editMenu = false; onPickGroup() }
                    )
                    MoReadMenuItem(
                        text = "标签",
                        icon = Icons.Outlined.Sell,
                        trailingText = if (tagCount == 0) "未设置" else "$tagCount 个",
                        onClick = { editMenu = false; onPickTags() }
                    )
                }
            }
            FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                IconButton(onClick = onBookmarks) {
                    Icon(Icons.Outlined.Bookmarks, contentDescription = "书签")
                }
            }
        }
    }
}

@Composable
internal fun DetailHero(
    book: BookEntity,
    tags: List<String>,
    description: String,
    onEditReadState: (BookReadState?) -> Unit,
    onListen: () -> Unit,
    onContinueReading: () -> Unit,
    progress: Float = 0f,
    chapterTitle: String = ""
) {
    var descriptionExpanded by remember(book.id, description) { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val wide = com.mozhi.reader.ui.rememberMoReadWindowWidth() == com.mozhi.reader.ui.MoReadWindowWidth.EXPANDED
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        DetailBookObject(
            book = book,
            glow = accent.copy(alpha = 0.6f),
            modifier = Modifier
                .padding(top = if (wide) 24.dp else 6.dp)
                .size(width = if (wide) 176.dp else 134.dp, height = if (wide) 250.dp else 190.dp)
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 26.dp, start = 24.dp, end = 24.dp)
        )
        Text(
            text = "${book.author.ifBlank { "未知作者" }} · ${book.totalChapters} 章",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReadStateChip(state = book.readState(), onSelect = onEditReadState)
            tags.forEach { tag -> TagChip(tag) }
        }
        DetailProgressLine(
            book = book,
            progress = progress,
            chapterTitle = chapterTitle,
            modifier = Modifier.padding(top = 22.dp, start = 28.dp, end = 28.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 18.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FrostedSurface(shape = MoReadTokens.CapsuleShape, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .clickable(enabled = book.removedAt == 0L, onClick = onListen)
                        .heightIn(min = 54.dp)
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(19.dp), tint = accent)
                    Spacer(Modifier.width(8.dp))
                    Text("听书", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            Button(
                onClick = onContinueReading,
                enabled = book.removedAt == 0L,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MoReadTokens.CapsuleShape,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 54.dp)
                    .shadow(14.dp, MoReadTokens.CapsuleShape, ambientColor = accent, spotColor = accent)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (book.removedAt > 0L) "正文已移除" else if (book.lastReadAt == 0L) "开始阅读" else "继续阅读",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (description.isNotBlank()) {
            FrostedSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 20.dp, end = 20.dp)
                    .clickable { descriptionExpanded = !descriptionExpanded },
                shape = RoundedCornerShape(moReadMetrics().radiusFor(22)),
                shadowElevation = 3.dp
            ) {
                Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "“",
                            fontFamily = FontFamily.Serif,
                            style = MaterialTheme.typography.headlineMedium,
                            color = accent.copy(alpha = 0.55f),
                            modifier = Modifier.height(26.dp)
                        )
                        Text(
                            stringResource(R.string.detail_description),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.15f),
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Text(
                        text = stringResource(if (descriptionExpanded) R.string.detail_description_less else R.string.detail_description_more),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        modifier = Modifier.align(Alignment.End).padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/** Where the reader is: chapter, percentage and a hairline bar in the book's own colour. */
@Composable
internal fun DetailProgressLine(book: BookEntity, progress: Float, chapterTitle: String, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val started = book.lastReadAt != 0L
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (started) stringResource(R.string.detail_progress_chapter, book.lastReadChapterIndex + 1, book.totalChapters)
                else stringResource(R.string.detail_progress_not_started),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${readPercent(progress)}%",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                color = accent
            )
        }
        Box(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(5.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent)))
            )
        }
        if (started && chapterTitle.isNotBlank()) Text(
            chapterTitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
internal fun ReadStateChip(state: BookReadState, onSelect: (BookReadState?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            shape = MoReadTokens.CapsuleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            Row(
                modifier = Modifier.padding(start = 10.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = state.label(), style = MaterialTheme.typography.labelSmall)
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = "修改阅读状态",
                    modifier = Modifier
                        .padding(start = 2.dp)
                        .size(14.dp)
                )
            }
        }
        MoReadDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MoReadMenuItem(
                text = "按进度自动判断",
                selected = false,
                onClick = {
                    expanded = false
                    onSelect(null)
                }
            )
            BookReadState.entries.forEach { candidate ->
                MoReadMenuItem(
                    text = candidate.label(),
                    selected = candidate == state,
                    onClick = {
                        expanded = false
                        onSelect(candidate)
                    }
                )
            }
        }
    }
}

/** 只读标签胶囊（编辑在弹窗里做）。 */
@Composable
internal fun TagChip(tag: String) {
    Surface(
        shape = MoReadTokens.CapsuleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
        )
    }
}

@Composable
internal fun HeroCover(
    book: BookEntity,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(moReadMetrics().radiusFor(14)),
    shadow: Boolean = true
) {
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Surface(
        modifier = modifier,
        shape = shape,
        shadowElevation = if (shadow) 18.dp else 0.dp,
        color = com.mozhi.reader.feature.bookshelf.coverColor(book.title)
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    com.mozhi.reader.feature.bookshelf.coverColor(book.title),
                                    Color.Black.copy(alpha = 0.45f)
                                )
                            )
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(5.dp)
                            .padding(start = 2.dp)
                            .background(Color.White.copy(alpha = 0.30f))
                    )
                    // 直排书名：超过一列就从右往左折列，与书架 fallback 封面同规则。
                    val display = if (book.title.length > 23) book.title.take(22) + "…" else book.title
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 14.dp, end = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        display.chunked(8).asReversed().forEach { column ->
                            Text(
                                text = column.toCharArray().joinToString("\n"),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.95f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 本书随读段评：与「本书 AI 索引」同一片区，都是按书的 AI 设置。
 * 总开关没开时这里只做说明，不假装能改——改了也不会触发生成。
 */
@Composable
internal fun BookAnnotationLimitsCard(
    autonomy: CompanionAutonomySettings,
    bookId: Long,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val perBook = autonomy.annotationLimitsByBook[bookId]?.enabled == true
    val effective = autonomy.annotationLimitsFor(bookId)
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(24)),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "本书随读段评",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = when {
                        !autonomy.proactiveAnnotationsEnabled ->
                            "随读段评当前全局关闭；到设置 › AI 与伴读里打开后这里才会生成批注。"
                        perBook -> "本书单独设置：${effective.summary()}"
                        else -> "跟随全局：${effective.summary()}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One glass strip with the reading numbers that used to fill two ring cards and a row of tiles. */
@Composable
internal fun DetailGlance(
    totalDurationMs: Long,
    readingDays: Int,
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    val seal = sealColor()
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(22)),
        shadowElevation = 5.dp
    ) {
        Row(
            modifier = Modifier.padding(vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlanceMetric(formatDuration(totalDurationMs), stringResource(R.string.detail_glance_time), Modifier.weight(1f))
            GlanceDivider()
            GlanceMetric(pluralStringResource(R.plurals.detail_days, readingDays, readingDays), stringResource(R.string.detail_glance_days), Modifier.weight(1f))
            GlanceDivider()
            GlanceMetric(
                pluralStringResource(R.plurals.detail_days, streakDays, streakDays),
                stringResource(R.string.detail_glance_streak),
                Modifier.weight(1f),
                dot = seal.takeIf { streakDays > 0 }
            )
        }
    }
}

@Composable
private fun GlanceMetric(value: String, label: String, modifier: Modifier, dot: Color? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            dot?.let { Box(Modifier.padding(end = 5.dp).size(6.dp).clip(CircleShape).background(it)) }
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun GlanceDivider() {
    Box(Modifier.width(1.dp).height(30.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)))
}

@Composable
internal fun MoreBookDetailsEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FrostedSurface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(22)),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("更多书籍信息", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "有声书制作、阅读热力等低频内容",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null)
        }
    }
}
@Composable
internal fun ReadingAssetsEntry(
    noteCount: Int,
    annotationCount: Int,
    illustrationCount: Int,
    bookmarkCount: Int,
    onNotes: () -> Unit,
    onAnnotations: () -> Unit,
    onGallery: () -> Unit,
    onBookmarks: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssetEntryCell(Icons.Outlined.EditNote, stringResource(R.string.detail_asset_notes), noteCount, onNotes, Modifier.weight(1f).testTag("detail-asset-notes"))
        AssetEntryCell(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.detail_asset_annotations), annotationCount, onAnnotations, Modifier.weight(1f).testTag("detail-asset-annotations"))
        AssetEntryCell(Icons.Outlined.Image, stringResource(R.string.detail_asset_illustrations), illustrationCount, onGallery, Modifier.weight(1f).testTag("detail-asset-illustrations"))
        AssetEntryCell(Icons.Outlined.Bookmarks, stringResource(R.string.detail_asset_bookmarks), bookmarkCount, onBookmarks, Modifier.weight(1f).testTag("detail-asset-bookmarks"))
    }
}

/** A small tile tinted with the book's colour: icon, a big count, and what it counts. */
@Composable
internal fun AssetEntryCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.primary
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(moReadMetrics().radiusFor(18)),
        color = accent.copy(alpha = if (isDarkTheme()) 0.16f else 0.10f).compositeOver(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 10.dp)) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            Text(
                "$count",
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** 剧情梗概排在普通笔记前；点卡片直接展开 Markdown 全文回顾。 */
@Composable
internal fun NotesSection(
    notes: List<NoteEntity>,
    onNoteClick: (NoteEntity) -> Unit,
    onShowAll: () -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ordered = notes.sortedForReview()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(
                title = "剧情梗概与读书笔记",
                trailing = "全部 ${notes.size} 条",
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onCreate) {
                Icon(Icons.Outlined.EditNote, contentDescription = null, modifier = Modifier.size(17.dp))
                Text("写笔记", modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (ordered.isEmpty()) {
            Text(
                text = "点击“写笔记”记录想法，也可让伴读角色保存剧情梗概。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            ordered.take(5).forEach { note ->
                NoteCard(note = note, onClick = { onNoteClick(note) })
            }
            if (ordered.size > 5) {
                TextButton(onClick = onShowAll, modifier = Modifier.align(Alignment.End)) {
                    Text("查看全部 ${ordered.size} 条")
                }
            }
        }
    }
}

internal fun List<NoteEntity>.sortedForReview(): List<NoteEntity> = sortedWith(
    compareByDescending<NoteEntity> { it.kind == NoteRepository.KIND_PLOT_SUMMARY }
        .thenByDescending(NoteEntity::updatedAt)
)

@Composable
internal fun NoteCard(note: NoteEntity, onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(moReadMetrics().radiusFor(20)),
        shadowElevation = 4.dp
    ) {
        Row(modifier = Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
            )
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = note.title.ifBlank {
                        if (note.kind == NoteRepository.KIND_PLOT_SUMMARY) "剧情梗概" else "读书笔记"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Serif,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = note.contentMarkdown
                        .replace(Regex("[#*_`>\\[\\]]"), "")
                        .replace('\n', ' ')
                        .trim(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Text(
                    text = buildString {
                        note.relatedChapterIndex?.let { append("截至第 ${it + 1} 章 · ") }
                        append(formatDate(note.updatedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
internal fun BookmarkEntryRow(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MoReadTokens.CapsuleShape,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Bookmarks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "书签 $count 处",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            )
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun formatDuration(durationMs: Long): String {
    val totalMinutes = durationMs / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 -> "${hours}h${minutes.toString().padStart(2, '0')}m"
        totalMinutes > 0 -> "${totalMinutes}m"
        durationMs > 0 -> "<1m"
        else -> "0m"
    }
}

internal fun formatDate(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
        .format(DateTimeFormatter.ofPattern("M月d日"))
