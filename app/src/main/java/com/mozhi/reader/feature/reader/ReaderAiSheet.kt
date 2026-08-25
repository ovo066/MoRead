package com.mozhi.reader.feature.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The selection-AI bottom panel: streaming reply, stop/retry, follow-up questions. Assistant
 * replies render as Markdown themed to the active [ReaderPalette].
 */
@Composable
fun ReaderAiSheet(
    state: ReaderAiUiState,
    palette: ReaderPalette,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit
) {
    val request = state.request ?: return
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val timeline = remember(state.messages) { buildCompanionTimeline(state.messages) }
    val liveExecutionSteps = remember(timeline, state.executionSteps) {
        val historicalCallIds = timeline.filterIsInstance<CompanionTimelineItem.Process>()
            .flatMap { it.steps }
            .mapTo(hashSetOf()) { it.callId }
        state.executionSteps.filterNot { it.callId in historicalCallIds }
    }

    // 只在结构变化（消息条数、流式开始/结束、工具阶段）时定位到最新；
    // 流式正文逐 token 长高不再触发滚动——回答在视口下方生长，用户随时可以
    // 上滑回看而不会被拽回底部。
    LaunchedEffect(
        timeline.size,
        state.streamingText != null,
        state.toolStatus,
        liveExecutionSteps.size
    ) {
        val itemCount = timeline.size + (if (state.streamingText != null) 1 else 0) + 1
        if (itemCount > 0) listState.scrollToItem(itemCount - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = palette.accentContainer,
                contentColor = palette.accent
            ) {
                Text(
                    text = request.action.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
            Text(
                text = request.selection.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp, max = 420.dp)
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(timeline, key = CompanionTimelineItem::key) { item ->
                when (item) {
                    is CompanionTimelineItem.Bubble -> when (item.message.role) {
                        "user" -> UserBubble(item.message.content, palette)
                        else -> AssistantBubble(
                            text = item.message.content,
                            palette = palette,
                            streaming = false,
                            onCopy = { onCopy(item.message.content) }
                        )
                    }
                    is CompanionTimelineItem.Process -> CompanionProcessCard(
                        steps = item.steps,
                        reasoning = item.reasoning,
                        palette = palette,
                        isLive = false,
                        stateKey = item.key
                    )
                    is CompanionTimelineItem.Media -> Unit
                }
            }
            state.streamingText?.let { streaming ->
                item(key = "streaming") {
                    AssistantBubble(
                        text = streaming,
                        palette = palette,
                        streaming = true,
                        onCopy = null
                    )
                }
            }
            if (liveExecutionSteps.isNotEmpty()) {
                item(key = "live-tool-timeline") {
                    CompanionProcessCard(
                        steps = liveExecutionSteps,
                        reasoning = null,
                        palette = palette,
                        isLive = true,
                        stateKey = "selection-live"
                    )
                }
            } else {
                state.toolStatus?.let { status ->
                    item(key = "tool-status") {
                        Text(
                            text = status,
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.accent,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                        )
                    }
                }
            }
            state.error?.let { error ->
                item(key = "error") {
                    ErrorCard(error, palette, onRetry)
                }
            }
            item(key = "tail-spacer") { Box(Modifier.size(2.dp)) }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = {
                    Text(
                        if (state.messages.isEmpty()) "就这段文字提问…" else "继续追问…",
                        color = palette.muted
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.accent,
                    unfocusedBorderColor = palette.glassBorder,
                    cursorColor = palette.accent
                ),
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            )
            if (state.isStreaming) {
                Surface(
                    shape = CircleShape,
                    color = palette.accentContainer,
                    contentColor = palette.accent
                ) {
                    IconButton(onClick = onStop) {
                        Icon(Icons.Outlined.Stop, contentDescription = "停止生成")
                    }
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = palette.accent,
                    contentColor = palette.onAccent
                ) {
                    IconButton(
                        onClick = {
                            onSend(input)
                            input = ""
                        },
                        enabled = state.conversationId != null
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, palette: ReaderPalette) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            shape = RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp),
            color = palette.accentContainer,
            contentColor = palette.onBackground
        ) {
            Text(
                // The context wrapper is an implementation detail; show the question part only.
                text = text.substringAfter("【问题】\n", missingDelimiterValue = text)
                    .substringAfter("【选段】\n", missingDelimiterValue = text)
                    .trim(),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(0.86f)
            )
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    palette: ReaderPalette,
    streaming: Boolean,
    onCopy: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = palette.glass,
                shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp)
            )
            .clickable(enabled = onCopy != null) { onCopy?.invoke() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        if (streaming) {
            StreamingAiRichText(content = "$text▍", palette = palette)
        } else {
            AiRichText(content = text, palette = palette)
        }
        if (!streaming && onCopy != null) {
            Text(
                text = "点按复制",
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, palette: ReaderPalette, onRetry: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = palette.glass,
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onBackground
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onRetry) {
                    Text("重试", color = palette.accent)
                }
            }
        }
    }
}
