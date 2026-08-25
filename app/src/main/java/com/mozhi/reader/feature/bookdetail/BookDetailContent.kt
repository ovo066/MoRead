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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.mozhi.reader.core.datastore.PendingReaderImage
import com.mozhi.reader.ai.media.BookCoverGenerationProgress
import com.mozhi.reader.ai.media.OnlineBookCover
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
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
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.sealColor
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
            .statusBarsPadding()
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
    onContinueReading: () -> Unit
) {
    var descriptionExpanded by remember(book.id, description) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        HeroCover(
            book = book,
            modifier = Modifier.size(width = 116.dp, height = 164.dp)
        )
        Text(
            text = book.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 14.dp, start = 24.dp, end = 24.dp)
        )
        Text(
            text = "${book.author.ifBlank { "未知作者" }} · ${book.totalChapters} 章",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 5.dp)
        )
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ReadStateChip(state = book.readState(), onSelect = onEditReadState)
            tags.forEach { tag -> TagChip(tag) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, start = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onListen,
                shape = MoReadTokens.CapsuleShape,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 13.dp),
                modifier = Modifier.weight(0.42f).heightIn(min = 52.dp)
            ) {
                Icon(Icons.Outlined.Headphones, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text("听书", maxLines = 1)
            }
            Button(
                onClick = onContinueReading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = MoReadTokens.CapsuleShape,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp),
                modifier = Modifier.weight(0.58f).heightIn(min = 52.dp)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (book.lastReadAt == 0L) "开始阅读" else "继续阅读",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (description.isNotBlank()) {
            FrostedSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp, start = 20.dp, end = 20.dp)
                    .clickable { descriptionExpanded = !descriptionExpanded },
                shape = RoundedCornerShape(20.dp),
                shadowElevation = 3.dp
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = if (descriptionExpanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.End).padding(top = 6.dp)
                    )
                }
            }
        }
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
internal fun HeroCover(book: BookEntity, modifier: Modifier = Modifier) {
    val coverFile = remember(book.coverPath) {
        book.coverPath?.let(::File)?.takeIf(File::isFile)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 18.dp,
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

/** 双环卡（§3.3）：全书进度环（moss）+ 连续阅读环（seal）。 */
@Composable
internal fun RingRow(
    book: BookEntity,
    streakDays: Int,
    modifier: Modifier = Modifier
) {
    val progress = if (book.totalChapters <= 0 || book.lastReadAt == 0L) {
        0f
    } else {
        ((book.lastReadChapterIndex + 1f) / book.totalChapters).coerceIn(0f, 1f)
    }
    val seal = sealColor()
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FrostedSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RingGauge(
                    progress = progress,
                    ringColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                ) {
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "全书进度",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "第 ${book.lastReadChapterIndex + 1} / ${book.totalChapters} 章",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        FrostedSurface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(24.dp),
            shadowElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RingGauge(
                    progress = (streakDays / 30f).coerceIn(0f, 1f),
                    ringColor = seal,
                    modifier = Modifier.size(96.dp)
                ) {
                    Text(
                        text = "$streakDays",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = "连续阅读",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    text = "天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
internal fun MoreBookDetailsEntry(onClick: () -> Unit, modifier: Modifier = Modifier) {
    FrostedSurface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(22.dp),
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
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 5.dp
    ) {
        Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AssetEntryCell(Icons.Outlined.EditNote, "笔记", "$noteCount 条", onNotes, Modifier.weight(1f))
            AssetEntryCell(
                Icons.Outlined.ChatBubbleOutline,
                "批注",
                "$annotationCount 条",
                onAnnotations,
                Modifier.weight(1f)
            )
            AssetEntryCell(Icons.Outlined.Image, "插图", "$illustrationCount 张", onGallery, Modifier.weight(1f))
            AssetEntryCell(Icons.Outlined.Bookmarks, "书签", "$bookmarkCount 个", onBookmarks, Modifier.weight(1f))
        }
    }
}

@Composable
internal fun AssetEntryCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 5.dp))
        Text(count, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        shape = RoundedCornerShape(20.dp),
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
