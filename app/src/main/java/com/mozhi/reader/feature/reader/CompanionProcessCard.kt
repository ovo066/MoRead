package com.mozhi.reader.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ai.agent.ToolCallSummary
import com.mozhi.reader.ai.embedding.BookEmbeddingProgress
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage

/**
 * 「过程」卡：一轮里的思维链 + 工具调用合成一条，挂在该轮 AI 气泡上方。
 *
 * 形制取自 Codex / Claude Code 的执行链——**默认折叠成一行淡字**，点开才看细节。
 * 过程是让人放心的，不是让人读的：它不该把回答挤下去。
 *
 * 展开策略：本轮正在跑时自动展开（看得见 agent 在干什么），跑完自动收起；
 * 用户手动开合之后一律以用户的选择为准，不再被自动逻辑改写。
 */
@Composable
internal fun CompanionProcessCard(
    steps: List<AgentExecutionStep>,
    reasoning: String?,
    palette: ReaderPalette,
    isLive: Boolean,
    modifier: Modifier = Modifier,
    stateKey: String = "process"
) {
    if (steps.isEmpty() && reasoning.isNullOrBlank()) return

    var userToggled by rememberSaveable(stateKey) { mutableStateOf(false) }
    var expanded by rememberSaveable(stateKey) { mutableStateOf(isLive) }
    LaunchedEffect(isLive) {
        if (!userToggled) expanded = isLive
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "process-chevron"
    )
    val failed = steps.any { it.state == AgentStepState.FAILED }
    val headlineTool = steps.lastOrNull { it.state == AgentStepState.RUNNING }
        ?: steps.singleOrNull()
    val headlinePresentation = headlineTool?.let {
        companionToolPresentation(it.toolName, it.displayName)
    }

    Surface(
        color = palette.glass,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, palette.glassBorder),
        modifier = modifier
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        userToggled = true
                        expanded = !expanded
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLive) {
                    CircularProgressIndicator(
                        strokeWidth = 1.6.dp,
                        color = palette.accent,
                        modifier = Modifier.size(12.dp)
                    )
                } else {
                    Icon(
                        headlinePresentation?.icon?.let(::toolIcon) ?: Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = palette.muted,
                        modifier = Modifier.size(13.dp)
                    )
                }
                Text(
                    text = processHeadline(steps, reasoning, isLive),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (failed) MaterialTheme.colorScheme.error else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 7.dp)
                )
                Icon(
                    Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "收起过程" else "展开过程",
                    tint = palette.muted,
                    modifier = Modifier
                        .size(15.dp)
                        .rotate(chevronRotation)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(180)) + fadeIn(tween(140)),
                exit = shrinkVertically(tween(160)) + fadeOut(tween(100))
            ) {
                Column(
                    modifier = Modifier.padding(start = 11.dp, end = 11.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    reasoning?.takeIf(String::isNotBlank)?.let { ReasoningBlock(it, palette) }
                    if (steps.isNotEmpty()) StepRail(steps, palette)
                }
            }
        }
    }
}

/** 思维链正文：长推理先截到 8 行，愿意读的人再点开——它是佐证，不是内容。 */
@Composable
private fun ReasoningBlock(reasoning: String, palette: ReaderPalette) {
    var fullyExpanded by rememberSaveable(reasoning.length) { mutableStateOf(false) }
    var overflowing by remember(reasoning) { mutableStateOf(false) }
    Column {
        Text(
            text = reasoning.trim(),
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
            maxLines = if (fullyExpanded) Int.MAX_VALUE else COLLAPSED_REASONING_LINES,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result -> overflowing = result.hasVisualOverflow || fullyExpanded }
        )
        if (overflowing) {
            TextButton(
                onClick = { fullyExpanded = !fullyExpanded },
                contentPadding = PaddingValues(
                    horizontal = 0.dp,
                    vertical = 2.dp
                )
            ) {
                Text(
                    text = if (fullyExpanded) "收起思考" else "展开全部思考",
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.accent
                )
            }
        }
    }
}

/** 工具步骤时间线：左侧一条竖导轨，每步「状态图标 + 工具名 + 参数摘要 + 结果预览」。 */
@Composable
private fun StepRail(steps: List<AgentExecutionStep>, palette: ReaderPalette) {
    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .padding(start = 2.dp, top = 3.dp, bottom = 3.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(palette.glassBorder)
        )
        Column(
            modifier = Modifier.padding(start = 9.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            steps.forEach { step -> StepRow(step, palette) }
        }
    }
}

@Composable
private fun StepRow(step: AgentExecutionStep, palette: ReaderPalette) {
    val presentation = remember(step.toolName, step.displayName) {
        companionToolPresentation(step.toolName, step.displayName)
    }
    val summary = remember(step.toolName, step.arguments) {
        ToolCallSummary.summarize(step.toolName, step.arguments)
    }
    Row(verticalAlignment = Alignment.Top) {
        Surface(
            shape = CircleShape,
            color = palette.accent.copy(alpha = 0.1f),
            contentColor = palette.accent,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = toolIcon(presentation.icon),
                    contentDescription = presentation.title,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = presentation.title,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = if (step.state == AgentStepState.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        palette.onBackground
                    }
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.size(15.dp), contentAlignment = Alignment.Center) {
                    when (step.state) {
                        AgentStepState.RUNNING -> CircularProgressIndicator(
                            strokeWidth = 1.5.dp,
                            color = palette.accent,
                            modifier = Modifier.size(11.dp)
                        )
                        AgentStepState.SUCCEEDED -> Icon(
                            Icons.Outlined.Check,
                            contentDescription = "已完成",
                            tint = palette.accent,
                            modifier = Modifier.size(13.dp)
                        )
                        AgentStepState.FAILED -> Icon(
                            Icons.Outlined.Close,
                            contentDescription = "未完成",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
            Text(
                text = presentation.description,
                style = MaterialTheme.typography.labelSmall,
                color = palette.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp)
            )
            if (summary.isNotBlank()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // 失败原因永远看得见；成功的结果预览是「点开才看」的那一层。
            val body = when {
                step.state == AgentStepState.FAILED -> step.detail
                else -> step.resultPreview
            }
            body.takeIf { it.isNotBlank() && it != "已完成" }?.let { detail ->
                Text(
                    text = detail.trim(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (step.state == AgentStepState.FAILED) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                    } else {
                        palette.muted.copy(alpha = 0.85f)
                    },
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

/** 折叠态那一行：正在跑时说当下在干什么，跑完了说一共干了什么。 */
private fun processHeadline(
    steps: List<AgentExecutionStep>,
    reasoning: String?,
    isLive: Boolean
): String {
    val running = steps.lastOrNull { it.state == AgentStepState.RUNNING }
    if (isLive && running != null) {
        val presentation = companionToolPresentation(running.toolName, running.displayName)
        return "正在${presentation.action}…"
    }
    if (isLive && steps.isEmpty() && !reasoning.isNullOrBlank()) return "正在思考…"
    val failedCount = steps.count { it.state == AgentStepState.FAILED }
    val actions = steps
        .map { companionToolPresentation(it.toolName, it.displayName).action }
        .distinct()
    return buildList {
        if (!reasoning.isNullOrBlank()) add("已思考")
        when {
            actions.size <= 2 -> addAll(actions)
            actions.isNotEmpty() -> add(actions.take(2).joinToString(" · ") + " 等 ${actions.size} 项操作")
        }
        if (failedCount > 0) add("$failedCount 个未完成")
    }.joinToString(" · ").ifEmpty { "执行过程" }
}

private fun toolIcon(icon: CompanionToolIcon): ImageVector = when (icon) {
    CompanionToolIcon.SEARCH -> Icons.Outlined.Search
    CompanionToolIcon.BOOK -> Icons.AutoMirrored.Outlined.MenuBook
    CompanionToolIcon.MEMORY -> Icons.Outlined.Psychology
    CompanionToolIcon.ANNOTATION -> Icons.Outlined.EditNote
    CompanionToolIcon.NOTE -> Icons.AutoMirrored.Outlined.Article
    CompanionToolIcon.IMAGE -> Icons.Outlined.Image
    CompanionToolIcon.AUDIO -> Icons.Outlined.RecordVoiceOver
    CompanionToolIcon.WEB -> Icons.Outlined.Language
    CompanionToolIcon.PROGRESS -> Icons.Outlined.QueryStats
    CompanionToolIcon.PLAN -> Icons.Outlined.CalendarMonth
    CompanionToolIcon.GENERIC -> Icons.Outlined.AutoAwesome
}

/**
 * 场景分隔：iMessage 日期分隔的形制——两侧渐隐细线夹一行淡字。
 * 它是「你们现在聊的是这一章」的提示，不是一张需要被读的卡片。
 */
@Composable
internal fun ChatSceneDivider(text: String, palette: ReaderPalette) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FadingRule(palette, fadeToStart = true, modifier = Modifier.weight(1f))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = palette.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
        FadingRule(palette, fadeToStart = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FadingRule(
    palette: ReaderPalette,
    fadeToStart: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = if (fadeToStart) {
        listOf(Color.Transparent, palette.glassBorder)
    } else {
        listOf(palette.glassBorder, Color.Transparent)
    }
    Box(
        modifier = modifier
            .height(1.dp)
            .background(Brush.horizontalGradient(colors))
    )
}

/**
 * 全文索引状态胶囊。就绪与未配置时**整条不渲染**——「已就绪」这种消息
 * 说完就该消失，不该一直占着聊天流的第一行。
 */
@Composable
internal fun EmbeddingProgressCapsule(
    progress: BookEmbeddingProgress,
    palette: ReaderPalette,
    onRetry: () -> Unit
) {
    if (progress.stage == EmbeddingIndexStage.READY ||
        progress.stage == EmbeddingIndexStage.NOT_CONFIGURED
    ) {
        return
    }
    val isProblem = progress.stage == EmbeddingIndexStage.BLOCKED ||
        progress.stage == EmbeddingIndexStage.FAILED
    val showBar = progress.stage == EmbeddingIndexStage.INDEXING ||
        (progress.stage == EmbeddingIndexStage.QUEUED && progress.indexedChapters > 0)
    Surface(
        color = palette.glass,
        shape = if (isProblem) RoundedCornerShape(14.dp) else CircleShape,
        border = BorderStroke(
            1.dp,
            if (isProblem) MaterialTheme.colorScheme.error.copy(alpha = 0.45f) else palette.glassBorder
        )
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 22.dp)
            ) {
                if (progress.stage == EmbeddingIndexStage.INDEXING) {
                    CircularProgressIndicator(
                        strokeWidth = 1.5.dp,
                        color = palette.accent,
                        modifier = Modifier.size(11.dp)
                    )
                }
                Text(
                    text = buildString {
                        append(
                            when (progress.stage) {
                                EmbeddingIndexStage.DISABLED -> "本书未启用 AI 索引"
                                EmbeddingIndexStage.QUEUED -> "全文索引等待中"
                                EmbeddingIndexStage.INDEXING -> "正在建立全文索引"
                                EmbeddingIndexStage.BLOCKED -> "全文索引需要处理"
                                EmbeddingIndexStage.FAILED -> "全文索引失败"
                                else -> ""
                            }
                        )
                        if (progress.totalChapters > 0 && !isProblem) {
                            append(" · ${progress.indexedChapters}/${progress.totalChapters}")
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isProblem) MaterialTheme.colorScheme.error else palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = if (progress.stage == EmbeddingIndexStage.INDEXING) 6.dp else 0.dp
                    )
                )
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(
                        horizontal = 8.dp,
                        vertical = 0.dp
                    ),
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text(
                        text = if (progress.stage == EmbeddingIndexStage.DISABLED) "启用" else "重试",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent
                    )
                }
            }
            // 问题态才展开第二行说清原因；进行中只留一条细进度。
            if (isProblem && progress.message.isNotBlank()) {
                Text(
                    text = progress.message,
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp, bottom = 2.dp)
                )
            }
            if (showBar) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    color = palette.accent,
                    trackColor = palette.glassBorder,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .padding(top = 4.dp)
                )
            }
        }
    }
}

private const val COLLAPSED_REASONING_LINES = 8
