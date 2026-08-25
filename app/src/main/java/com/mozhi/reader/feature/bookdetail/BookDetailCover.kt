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
@Composable
internal fun ImageLibraryPickerDialog(
    images: List<ReaderImageAsset>,
    currentPath: String?,
    onSelect: (ReaderImageAsset) -> Unit,
    onImport: () -> Unit,
    onSearch: () -> Unit,
    onGenerate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("更换封面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoverSourceAction(
                        icon = Icons.Outlined.Language,
                        label = "网络搜寻",
                        supporting = "按书名作者匹配",
                        onClick = onSearch,
                        modifier = Modifier.weight(1f)
                    )
                    CoverSourceAction(
                        icon = Icons.Outlined.AutoAwesome,
                        label = "AI 生成",
                        supporting = "自动分析书籍内容",
                        onClick = onGenerate,
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "图片库",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (images.isEmpty()) {
                    Text("图片库还是空的，可以导入、搜索或生成一张新封面。")
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                    items(images, key = ReaderImageAsset::id) { image ->
                        ListItem(
                            headlineContent = {
                                Text(image.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                            supportingContent = {
                                if (image.width > 0 && image.height > 0) {
                                    Text("${image.width} × ${image.height}")
                                }
                            },
                            leadingContent = {
                                AsyncImage(
                                    model = File(image.filePath),
                                    contentDescription = image.displayName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                                )
                            },
                            trailingContent = if (currentPath == image.filePath) {
                                { Icon(Icons.Outlined.Check, contentDescription = "当前封面") }
                            } else {
                                null
                            },
                            modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable {
                                onSelect(image)
                            }
                        )
                    }
                }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onImport) { Text("导入新图片") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
internal fun BookIndexCard(
    progress: BookEmbeddingProgress,
    onEnabledChange: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onRebuild: () -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = progress.stage != EmbeddingIndexStage.DISABLED
    val problem = progress.stage == EmbeddingIndexStage.BLOCKED ||
        progress.stage == EmbeddingIndexStage.FAILED
    FrostedSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "本书 AI 索引",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        if (enabled) {
                            "只为这本书建立语义索引；关闭会删除已生成的向量。"
                        } else {
                            "默认关闭，不消耗 Embedding 额度；搜索仍可使用本地关键词。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) {
                Text(
                    text = buildString {
                        if (progress.totalChapters > 0) {
                            append("${progress.indexedChapters}/${progress.totalChapters} 章")
                            if (progress.message.isNotBlank()) append(" · ")
                        }
                        append(progress.message)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (problem) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
                if (progress.stage == EmbeddingIndexStage.INDEXING ||
                    progress.stage == EmbeddingIndexStage.QUEUED
                ) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                    )
                }
                if (progress.stage != EmbeddingIndexStage.INDEXING &&
                    progress.stage != EmbeddingIndexStage.NOT_CONFIGURED
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (progress.stage != EmbeddingIndexStage.READY) {
                            TextButton(onClick = onRetry) { Text("继续 / 重试") }
                        }
                        TextButton(onClick = onRebuild) { Text("重新建立") }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CoverSourceAction(
    icon: ImageVector,
    label: String,
    supporting: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 13.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(
                supporting,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun OnlineCoverPickerDialog(
    results: List<OnlineBookCover>,
    queries: List<String>,
    agentEnhanced: Boolean,
    isWorking: Boolean,
    onRefresh: () -> Unit,
    onSelect: (OnlineBookCover) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("网络封面") },
        text = {
            when {
                isWorking && results.isEmpty() -> Box(
                    Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
                results.isEmpty() -> Text("没有找到结果，可以修改书名或作者后重试。")
                else -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.AutoAwesome,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        if (agentEnhanced) "Agent 识别作品后搜索" else "按书名与作者精确搜索",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    if (isWorking) {
                                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                                    }
                                }
                                queries.forEachIndexed { index, query ->
                                    Text(
                                        "${index + 1}. $query",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                    items(results, key = OnlineBookCover::imageUrl) { candidate ->
                        Surface(
                            onClick = { onSelect(candidate) },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                SubcomposeAsyncImage(
                                    model = candidate.imageUrl,
                                    contentDescription = candidate.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(width = 58.dp, height = 84.dp)
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                    loading = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                        }
                                    },
                                    error = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Icon(
                                                Icons.Outlined.Image,
                                                contentDescription = "预览加载失败",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    success = { SubcomposeAsyncImageContent() }
                                )
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(candidate.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        candidate.author.ifBlank { "未知作者" },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                    Text(
                                        candidate.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "选择")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onRefresh, enabled = !isWorking) { Text("重新搜索") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun AiCoverPromptDialog(
    isWorking: Boolean,
    onGenerate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 生成封面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "留空时，Agent 会阅读本书内容，并可使用网络搜索补充公开资料后自动撰写提示词。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("自定义方向（可选）") },
                    placeholder = { Text("例如：水墨武侠、冷色悬疑、复古科幻…") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onGenerate(prompt) }, enabled = !isWorking) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text("开始生成", modifier = Modifier.padding(start = 6.dp))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun AiCoverGenerationProgressDialog(
    progress: BookCoverGenerationProgress,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
                Text("正在生成 AI 封面", modifier = Modifier.padding(start = 10.dp))
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                LinearProgressIndicator(
                    progress = { progress.fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${(progress.fraction * 100).toInt().coerceIn(1, 100)}% · 完成后会自动进入裁剪预览",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消生成") }
        }
    )
}

@Composable
internal fun CoverCropDialog(
    pending: PendingReaderImage,
    isWorking: Boolean,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    var focusY by remember(pending.cachePath) { mutableStateOf(0.5f) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("裁剪封面") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                ) {
                    AsyncImage(
                        model = File(pending.cachePath),
                        contentDescription = "封面裁剪预览",
                        contentScale = ContentScale.Crop,
                        alignment = BiasAlignment(0f, focusY * 2f - 1f),
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.08f), Color.Transparent, Color.Black.copy(alpha = 0.12f))
                            )
                        )
                    )
                }
                Text("上下拖动焦点", style = MaterialTheme.typography.labelLarge)
                Slider(value = focusY, onValueChange = { focusY = it }, valueRange = 0f..1f)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("靠上", style = MaterialTheme.typography.labelSmall)
                    Text("居中", style = MaterialTheme.typography.labelSmall)
                    Text("靠下", style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(focusY) }, enabled = !isWorking) {
                if (isWorking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text("使用此裁剪")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isWorking) { Text("取消") } }
    )
}
