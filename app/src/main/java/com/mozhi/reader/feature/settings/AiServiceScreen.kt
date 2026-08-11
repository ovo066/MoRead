package com.mozhi.reader.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.ai.embedding.LibraryEmbeddingProgress
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadDropdownMenu
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadMenuSection
import com.mozhi.reader.ui.theme.MoReadTokens
import java.util.Locale

/**
 * AI 服务二级页：供应商列表与模型分配从设置一级页搬到这里。
 * 两者要一起看——分配模型时得知道有哪些供应商——所以合成一页而不是拆两页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiServiceScreen(
    onBack: () -> Unit,
    onOpenProvider: (Long) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val modelsByProvider = remember(state.models) { state.models.groupBy(AiModelEntity::providerId) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.ShowMessage -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }

    MoReadBackdrop {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    title = { Text("AI 服务") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text(
                        "模型供应商",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp)
                    )
                }
                items(state.providers, key = AiProviderEntity::id) { provider ->
                    ProviderRow(
                        provider = provider,
                        models = modelsByProvider[provider.id].orEmpty(),
                        onClick = { onOpenProvider(provider.id) }
                    )
                }
                item {
                    DashedAddRow(label = "添加 Provider", onClick = { onOpenProvider(0) })
                }

                item {
                    Text(
                        "模型分配",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp)
                    )
                }
                item {
                    ModelAssignmentCard(
                        providers = state.providers,
                        models = state.models,
                        assignments = state.assignments,
                        onSelect = viewModel::assignModel
                    )
                }
                item {
                    EmbeddingLibraryStatusCard(
                        progress = state.embeddingProgress,
                        onRetry = viewModel::retryEmbedding,
                        onRebuild = viewModel::rebuildEmbedding
                    )
                }
            }
        }
    }
}

/** Provider 紧凑行：logo 位 + 名称 + 类型·方言 + 模型数，点击进详情三级页管理。 */
@Composable
private fun ProviderRow(
    provider: AiProviderEntity,
    models: List<AiModelEntity>,
    onClick: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = provider.name.take(2).uppercase(Locale.ROOT),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
            ) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append(provider.adapter.label())
                        append(" · ")
                        append(ProviderProtocolPolicy.providerChatDialect(provider).label())
                        val capabilities = models.map(AiModelEntity::type).distinct()
                        if (capabilities.isNotEmpty()) {
                            append(" · ")
                            append(capabilities.joinToString("/") { it.label() })
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
            Surface(
                shape = MoReadTokens.CapsuleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
            ) {
                Text(
                    text = "${models.size} 个模型",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/** 模型分配：一张卡 6 行，右侧胶囊下拉 —— 按「模型」而不是按 Provider 分配。 */
@Composable
private fun ModelAssignmentCard(
    providers: List<AiProviderEntity>,
    models: List<AiModelEntity>,
    assignments: Map<ModelRole, Long?>,
    onSelect: (ModelRole, Long?) -> Unit
) {
    val providersById = providers.associateBy { it.id }
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 6.dp
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            ModelRole.entries.forEach { role ->
                val eligible = models.filter { model ->
                    model.type == role.requiredModelType() &&
                        providersById[model.providerId]?.let { provider ->
                            ProviderProtocolPolicy.isSupported(provider, model)
                        } == true
                }
                ModelAssignmentRow(
                    role = role,
                    models = eligible,
                    providersById = providersById,
                    selectedModelId = assignments[role],
                    onSelect = { modelId -> onSelect(role, modelId) }
                )
            }
        }
    }
}

@Composable
private fun ModelAssignmentRow(
    role: ModelRole,
    models: List<AiModelEntity>,
    providersById: Map<Long, AiProviderEntity>,
    selectedModelId: Long?,
    onSelect: (Long?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedModelId }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(role.label(), style = MaterialTheme.typography.titleSmall)
            Text(
                role.purpose(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            val assigned = selected != null
            Surface(
                onClick = { expanded = true },
                shape = MoReadTokens.CapsuleShape,
                color = if (assigned) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                } else {
                    Color.Transparent
                },
                contentColor = if (assigned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (assigned) {
                    null
                } else {
                    BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
            ) {
                Text(
                    text = selected?.modelName ?: "未分配",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                        .widthIn(max = 132.dp)
                )
            }
            MoReadDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                MoReadMenuItem(
                    text = "不分配",
                    selected = selectedModelId == null,
                    onClick = {
                        expanded = false
                        onSelect(null)
                    }
                )
                models.groupBy { it.providerId }.forEach { (providerId, group) ->
                    MoReadMenuSection(label = providersById[providerId]?.name ?: "未知 Provider")
                    group.forEach { model ->
                        MoReadMenuItem(
                            text = model.modelName,
                            selected = model.id == selectedModelId,
                            onClick = {
                                expanded = false
                                onSelect(model.id)
                            }
                        )
                    }
                }
                if (models.isEmpty()) {
                    MoReadMenuItem(
                        text = "没有可用模型，先在上面的供应商里添加",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = { expanded = false }
                    )
                }
            }
        }
    }
}

/** 向量索引不是「分配了模型就算成功」；这里展示实际落盘章节与可操作错误。 */
@Composable
private fun EmbeddingLibraryStatusCard(
    progress: LibraryEmbeddingProgress,
    onRetry: () -> Unit,
    onRebuild: () -> Unit
) {
    val problem = progress.stage == EmbeddingIndexStage.BLOCKED ||
        progress.stage == EmbeddingIndexStage.FAILED
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = when (progress.stage) {
                    EmbeddingIndexStage.NOT_CONFIGURED -> "全文索引未配置"
                    EmbeddingIndexStage.QUEUED -> "全文索引等待中"
                    EmbeddingIndexStage.INDEXING -> "正在生成全文索引"
                    EmbeddingIndexStage.READY -> "全文索引可用"
                    EmbeddingIndexStage.BLOCKED -> "全文索引需要处理"
                    EmbeddingIndexStage.FAILED -> "全文索引失败"
                },
                style = MaterialTheme.typography.titleSmall,
                color = if (problem) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            Text(
                text = buildString {
                    progress.modelName?.let { append(it).append(" · ") }
                    if (progress.totalChapters > 0) {
                        append("${progress.indexedChapters}/${progress.totalChapters} 章 · ")
                    }
                    append(progress.message)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            if (progress.stage == EmbeddingIndexStage.INDEXING ||
                (progress.stage == EmbeddingIndexStage.QUEUED && progress.indexedChapters > 0)
            ) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                )
            }
            if (progress.stage != EmbeddingIndexStage.NOT_CONFIGURED) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRetry) { Text("继续 / 重试") }
                    TextButton(onClick = onRebuild) { Text("完整重建") }
                }
            }
        }
    }
}

internal fun ModelRole.label(): String = when (this) {
    ModelRole.CHAT -> "主对话"
    ModelRole.CHEAP -> "批量任务"
    ModelRole.SUGGESTION -> "建议回复"
    ModelRole.EMBEDDING -> "Embedding"
    ModelRole.TTS -> "语音朗读"
    ModelRole.IMAGE -> "生图"
}

private fun ModelRole.purpose(): String = when (this) {
    ModelRole.CHAT -> "角色对话与问答"
    ModelRole.CHEAP -> "摘要、索引等后台任务"
    ModelRole.SUGGESTION -> "伴读输入区快捷回复，缺省用批量任务模型"
    ModelRole.EMBEDDING -> "全文与想法检索"
    ModelRole.TTS -> "听书语音合成"
    ModelRole.IMAGE -> "角色头像与插图"
}

private fun ModelRole.requiredModelType(): AiModelType = when (this) {
    ModelRole.CHAT, ModelRole.CHEAP, ModelRole.SUGGESTION -> AiModelType.CHAT
    ModelRole.EMBEDDING -> AiModelType.EMBEDDING
    ModelRole.TTS -> AiModelType.TTS
    ModelRole.IMAGE -> AiModelType.IMAGE
}
