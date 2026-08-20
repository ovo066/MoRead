package com.mozhi.reader.feature.listen

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.theme.MoReadTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookScriptScreen(
    onBack: () -> Unit,
    onConfirmed: (Long) -> Unit,
    viewModel: AudiobookScriptViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var applyNext by remember { mutableStateOf(false) }
    PlayGeneratedPreview(state.previewPath, viewModel::consumePreview)
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event is AudiobookScriptEvent.Confirmed) onConfirmed(viewModel.bookId)
        }
    }

    AudiobookPage(
        title = "剧本预览 · 第 ${viewModel.chapterIndex + 1} 章",
        subtitle = state.chapter?.title?.let { "$it · 当前只校对这一章" },
        onBack = onBack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AudiobookCard {
                    Text(
                        "第 2 步 · 排剧本",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    AudiobookHint(
                        "这里是当前章节的朗读分镜，不是整本书。旁白与角色对白会分开标明；确认后到下一步选择批量制作范围。"
                    )
                    Spacer(Modifier.height(14.dp))
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
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.generate(false) },
                            enabled = !state.isWorking,
                            shape = MoReadTokens.CapsuleShape
                        ) {
                            AudiobookSmallIcon(Icons.Outlined.Refresh, null)
                            Spacer(Modifier.size(6.dp))
                            Text("规则重排", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { viewModel.generate(true) },
                            enabled = !state.isWorking,
                            shape = MoReadTokens.CapsuleShape
                        ) {
                            AudiobookSmallIcon(Icons.Outlined.AutoAwesome, null)
                            Spacer(Modifier.size(6.dp))
                            Text("AI 精排", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (state.isWorking) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            AudiobookHint("正在排剧本…")
                        }
                    }
                    state.message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (state.segments.isEmpty()) {
                item {
                    AudiobookCard {
                        Text(
                            "本章还没有剧本",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AudiobookHint("先点「规则重排」生成分段；对白密集的章节再用「AI 精排」核对说话人。")
                    }
                }
            } else {
                val roleIds = state.roles.filter { it.name != "旁白" }.map { it.id }.toSet()
                val roleCount = state.segments.count { it.roleId in roleIds }
                item {
                    AudiobookSectionTitle(
                        "本章朗读分镜",
                        "${state.segments.size} 段 · 角色 $roleCount · 旁白 ${state.segments.size - roleCount}"
                    )
                }
            }

            itemsIndexed(state.segments, key = { _, segment -> segment.id }) { index, segment ->
                SegmentCard(
                    index = index,
                    segment = segment,
                    text = state.body.safeSubstring(segment.startCharOffset, segment.endCharOffset),
                    roles = state.roles,
                    onRole = { viewModel.setRole(segment, it) },
                    onEmotion = { viewModel.setEmotion(segment, it) },
                    onPreview = { viewModel.preview(segment) }
                )
            }

            if (state.segments.isNotEmpty()) {
                item {
                    AudiobookCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { applyNext = !applyNext },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (applyNext) Icons.Outlined.CheckBox
                                else Icons.Outlined.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (applyNext) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    "后续三章沿用本章结果",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                AudiobookHint("同样的角色分派直接确认，省去逐章点一遍。")
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = { viewModel.confirm(applyNext) },
                        enabled = !state.isWorking,
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .navigationBarsPadding()
                    ) {
                        Text("确认剧本，去合成", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentCard(
    index: Int,
    segment: AudiobookSegmentEntity,
    text: String,
    roles: List<AudiobookRoleEntity>,
    onRole: (Long?) -> Unit,
    onEmotion: (String) -> Unit,
    onPreview: () -> Unit
) {
    val role = roles.firstOrNull { it.id == segment.roleId }
    val ai = role?.engine == AudiobookEngine.AI.name
    var roleMenu by remember { mutableStateOf(false) }
    var emotionMenu by remember { mutableStateOf(false) }
    AudiobookCard(shape = RoundedCornerShape(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${index + 1} · ${if (role?.name == "旁白") "旁白段" else "角色对白"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            // 说话人：胶囊即按钮，点开选角色。
            Box {
                Surface(
                    onClick = { roleMenu = true },
                    shape = MoReadTokens.CapsuleShape,
                    color = if (role == null) {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
                    contentColor = if (role == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    }
                ) {
                    Row(
                        modifier = Modifier.padding(start = 11.dp, end = 7.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = role?.name ?: "跳过",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 96.dp)
                        )
                        Icon(
                            Icons.Outlined.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                MoReadStableDropdownMenu(
                    expanded = roleMenu,
                    onDismissRequest = { roleMenu = false },
                    width = 200.dp
                ) {
                    MoReadMenuItem(
                        text = "跳过，不朗读",
                        selected = role == null,
                        onClick = { roleMenu = false; onRole(null) }
                    )
                    roles.forEach { candidate ->
                        MoReadMenuItem(
                            text = candidate.name,
                            selected = candidate.id == segment.roleId,
                            trailingText = if (candidate.engine == AudiobookEngine.AI.name) "AI" else "系统",
                            onClick = { roleMenu = false; onRole(candidate.id) }
                        )
                    }
                }
            }
            Spacer(Modifier.size(8.dp))
            // 情绪：只有指定了角色才有意义。
            Box {
                Surface(
                    onClick = { if (role != null) emotionMenu = true },
                    shape = MoReadTokens.CapsuleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(
                        alpha = if (role == null) 0.5f else 1f
                    ),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = segment.emotion ?: "中性",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                MoReadStableDropdownMenu(
                    expanded = emotionMenu,
                    onDismissRequest = { emotionMenu = false },
                    width = 160.dp
                ) {
                    AudiobookScriptViewModel.EMOTIONS.forEach { emotion ->
                        MoReadMenuItem(
                            text = emotion,
                            selected = segment.emotion == emotion,
                            onClick = { emotionMenu = false; onEmotion(emotion) }
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (ai) {
                IconButton(onClick = onPreview) {
                    AudiobookSmallIcon(Icons.Outlined.PlayArrow, "试听本段")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (role == null) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.safeSubstring(start: Int, end: Int): String {
    val safeStart = start.coerceIn(0, length)
    val safeEnd = end.coerceIn(safeStart, length)
    return substring(safeStart, safeEnd).replace('￼', ' ').trim()
}
