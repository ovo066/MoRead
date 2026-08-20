package com.mozhi.reader.feature.listen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.ui.theme.MoReadTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookProductionScreen(
    onBack: () -> Unit,
    onOpenScript: (Long, Int) -> Unit,
    onPlay: (Long) -> Unit,
    viewModel: AudiobookProductionViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmLargeJob by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AudiobookProductionEvent.OpenScript) {
                onOpenScript(viewModel.bookId, event.chapterIndex)
            }
        }
    }

    val visibleChapters = state.chapters
        .filter { it.chapterIndex in state.startChapter..state.endChapter }

    AudiobookPage(
        title = "有声书制作",
        subtitle = state.book?.title,
        onBack = onBack
    ) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 6.dp,
                    bottom = 132.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    AudiobookCard {
                        Text(
                            "第 3 步 · 合成",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AudiobookHint("默认从当前章往后做 3 章。只有确认过剧本的章节会真正合成。")
                        if (state.chapters.isNotEmpty()) {
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "制作到第 ${state.endChapter + 1} 章",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Slider(
                                value = state.endChapter.toFloat(),
                                onValueChange = { viewModel.setEndChapter(it.toInt()) },
                                valueRange = state.startChapter.toFloat()..
                                    (state.chapters.size - 1)
                                        .coerceAtLeast(state.startChapter).toFloat(),
                                steps = (state.chapters.size - state.startChapter - 2)
                                    .coerceAtLeast(0)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                AudiobookMetric(
                                    primary = "${state.estimate.segmentCount} 段",
                                    secondary = "AI ${state.estimate.aiSegmentCount} · 系统 ${state.estimate.systemSegmentCount}"
                                )
                                AudiobookMetric(
                                    primary = "¥${"%.2f".format(state.estimate.estimatedCost)}",
                                    secondary = "约 ${state.estimate.totalChars} 字"
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            AudiobookHint(
                                "按 ¥${"%.2f".format(AudiobookScriptViewModel.DEFAULT_PRICE_PER_10K)}/万字估算，实际以服务商账单为准。"
                            )
                        }
                    }
                }

                if (visibleChapters.isNotEmpty()) {
                    item { AudiobookSectionTitle("章节", "${visibleChapters.size} 章") }
                }

                items(visibleChapters, key = { it.id }) { chapter ->
                    val chapterState = state.chapterStates
                        .firstOrNull { it.chapterIndex == chapter.chapterIndex }
                    ChapterRow(
                        index = chapter.chapterIndex,
                        title = chapter.title,
                        stateName = chapterState?.state,
                        readySegments = chapterState?.readySegmentCount ?: 0,
                        totalSegments = chapterState?.segmentCount ?: 0,
                        onConfirm = { onOpenScript(viewModel.bookId, chapter.chapterIndex) }
                    )
                }

                state.message?.let {
                    item {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // 底部常驻操作舱：进度与主按钮永远在手边，不用滚到列表末尾去找。
            AudiobookCard(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(24.dp)
            ) {
                if (state.progressText.isNotBlank()) {
                    Text(
                        state.progressText,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    state.progress?.let {
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(CircleShape),
                            gapSize = 0.dp,
                            drawStopIndicator = {}
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.isRunning) {
                        OutlinedButton(
                            onClick = viewModel::pause,
                            shape = MoReadTokens.CapsuleShape,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            AudiobookSmallIcon(Icons.Outlined.Pause, null)
                            Spacer(Modifier.size(6.dp))
                            Text("暂停", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Button(
                            onClick = {
                                if (state.estimate.totalChars > LARGE_JOB_CHAR_LIMIT) {
                                    confirmLargeJob = true
                                } else {
                                    viewModel.startProduction()
                                }
                            },
                            enabled = state.estimate.segmentCount > 0,
                            shape = MoReadTokens.CapsuleShape,
                            modifier = Modifier.weight(1f).height(48.dp)
                        ) {
                            AudiobookSmallIcon(Icons.Outlined.Bolt, null)
                            Spacer(Modifier.size(6.dp))
                            Text("开始制作", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedButton(
                        onClick = { onPlay(viewModel.bookId) },
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        AudiobookSmallIcon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.size(6.dp))
                        Text("播放成品", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    if (confirmLargeJob) {
        AlertDialog(
            onDismissRequest = { confirmLargeJob = false },
            title = { Text("确认大批量制作", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "本次约 ${state.estimate.totalChars} 字，可能产生较高费用。仍要开始吗？",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmLargeJob = false; viewModel.startProduction() }) {
                    Text("确认制作")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmLargeJob = false }) { Text("取消") }
            }
        )
    }
}

/** 单章行：左侧状态点 + 章名，右侧状态文案或「去确认」。 */
@Composable
private fun ChapterRow(
    index: Int,
    title: String,
    stateName: String?,
    readySegments: Int,
    totalSegments: Int,
    onConfirm: () -> Unit
) {
    data class Look(val icon: ImageVector, val text: String, val color: Color)

    val scheme = MaterialTheme.colorScheme
    val look = when (stateName) {
        AudiobookChapterState.READY.name ->
            Look(Icons.Outlined.CheckCircle, "已制作 · $readySegments 段", scheme.primary)
        AudiobookChapterState.SYNTHESIZING.name ->
            Look(Icons.Outlined.Bolt, "制作中 · $readySegments/$totalSegments", scheme.primary)
        AudiobookChapterState.CONFIRMED.name ->
            Look(Icons.Outlined.HourglassEmpty, "剧本已确认，待合成", scheme.onSurfaceVariant)
        AudiobookChapterState.STALE.name ->
            Look(Icons.Outlined.Refresh, "剧本已过期，需重排", scheme.error)
        AudiobookChapterState.SCRIPTED.name ->
            Look(Icons.Outlined.HourglassEmpty, "等待确认", scheme.onSurfaceVariant)
        else ->
            Look(Icons.Outlined.HourglassEmpty, "尚未排剧本", scheme.onSurfaceVariant)
    }
    val done = stateName in setOf(
        AudiobookChapterState.CONFIRMED.name,
        AudiobookChapterState.SYNTHESIZING.name,
        AudiobookChapterState.READY.name
    )

    AudiobookCard(shape = RoundedCornerShape(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                look.icon,
                contentDescription = null,
                tint = look.color,
                modifier = Modifier.size(19.dp)
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "第 ${index + 1} 章 · $title",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    look.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = look.color
                )
            }
            if (!done) {
                Surface(
                    onClick = onConfirm,
                    shape = MoReadTokens.CapsuleShape,
                    color = scheme.secondaryContainer,
                    contentColor = scheme.onSecondaryContainer
                ) {
                    Text(
                        "去确认",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

private const val LARGE_JOB_CHAR_LIMIT = 100_000
