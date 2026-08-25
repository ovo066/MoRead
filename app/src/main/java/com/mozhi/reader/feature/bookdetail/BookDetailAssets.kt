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
import androidx.compose.material.icons.outlined.VolumeUp
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
import com.mozhi.reader.core.library.AnnotationMedia
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
@Composable
internal fun NoteEditorDialog(
    note: NoteEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var title by remember(note?.id) { mutableStateOf(note?.title.orEmpty()) }
    var content by remember(note?.id) { mutableStateOf(note?.contentMarkdown.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (note == null) "新建读书笔记" else "编辑读书笔记") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxHeight(0.68f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("标题") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("笔记正文（支持 Markdown）") },
                        minLines = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(title.trim(), content.trim()) },
                enabled = title.isNotBlank() || content.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

/** 批注作者筛选的「全部」哨兵值（null 已被「我的」占用）。 */
internal const val FILTER_ALL = -1L

@Composable
internal fun AnnotationReviewCard(
    comments: List<AnnotationEntity>,
    personaNames: Map<Long, String>,
    onDelete: (Long) -> Unit
) {
    val first = comments.first()
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "第 ${first.chapterIndex + 1} 章 · “${first.selectedText.take(180)}${if (first.selectedText.length > 180) "…" else ""}”",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            comments.forEach { annotation ->
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            annotation.personaId?.let { id ->
                                personaNames[id] ?: "已删除角色"
                            } ?: "我的批注",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(annotation.note.ifBlank { "仅标记原文" })
                    }
                    val media = remember(annotation.mediaJson) {
                        AnnotationMedia.decode(annotation.mediaJson)
                    }
                    if (!media.audioPath.isNullOrBlank()) {
                        Icon(
                            Icons.Outlined.VolumeUp,
                            contentDescription = "含语音",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    if (media.illustrationId != null) {
                        Icon(
                            Icons.Outlined.Image,
                            contentDescription = "含配图",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp).size(14.dp)
                        )
                    }
                    IconButton(onClick = { onDelete(annotation.id) }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = "删除批注", modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun IllustrationGalleryCard(
    illustration: IllustrationEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Column {
            AsyncImage(
                model = File(illustration.imagePath),
                contentDescription = "书籍插图",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(230.dp)
            )
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    illustration.chapterIndex?.let { "第 ${it + 1} 章" } ?: "全书插图",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Close, contentDescription = "删除插图")
                }
            }
        }
    }
}

@Composable
internal fun AudiobookEntryCard(
    readyChapters: Int,
    totalChapters: Int,
    totalMillis: Long,
    roleNames: List<String>,
    onManage: () -> Unit,
    onContinue: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        "AI 有声书",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (readyChapters > 0) {
                            "已制作 $readyChapters/$totalChapters 章 · ${formatDuration(totalMillis)}"
                        } else {
                            "分配角色音色，逐章合成多角色朗读"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (roleNames.isNotEmpty()) {
                Text(
                    text = roleNames.take(5).joinToString(" · ") +
                        if (roleNames.size > 5) " …" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onManage,
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("角色与音色", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                if (readyChapters > 0) {
                    OutlinedButton(
                        onClick = onPlay,
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("播放成品", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
                Button(
                    onClick = onContinue,
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (roleNames.isEmpty()) "开始制作" else "继续制作",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
