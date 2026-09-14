package com.mozhi.reader.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.theme.sectionCardColor
import com.mozhi.reader.ui.theme.sectionHairline

/** Providers and role assignments share the same page so every selected model has a clear owner. */
@Composable
fun AiServiceScreen(
    onBack: () -> Unit,
    onOpenProvider: (Long) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) { is SettingsEvent.ShowMessage -> snackbar.showSnackbar(event.message) }
        }
    }
    Box(Modifier.fillMaxSize()) {
        AiServiceContent(state, onBack, onOpenProvider, viewModel::assignModel,
            viewModel::retryEmbedding, viewModel::rebuildEmbedding)
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(20.dp))
    }
}

@Composable
internal fun AiServiceContent(
    state: SettingsUiState, onBack: () -> Unit, onOpenProvider: (Long) -> Unit,
    onAssign: (ModelRole, Long?) -> Unit, onRetry: () -> Unit = {}, onRebuild: () -> Unit = {}
) {
    val modelsByProvider = remember(state.models) { state.models.groupBy(AiModelEntity::providerId) }
    MoReadSecondaryPage(title = "AI 服务", subtitle = "连接模型供应商，为不同阅读任务选择合适的模型。", onBack = onBack,
        actions = { IconButton(onClick = { onOpenProvider(0) }) { Icon(Icons.Outlined.Add, "添加供应商") } }) {
        if (!state.isLoaded) return@MoReadSecondaryPage
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("模型供应商", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                Text("${state.providers.size} 家 · ${state.models.size} 个模型", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.providers, key = { "provider-${it.id}" }) { provider ->
            ProviderRow(provider, modelsByProvider[provider.id].orEmpty()) { onOpenProvider(provider.id) }
        }
        item { DashedAddRow(label = "添加供应商", onClick = { onOpenProvider(0) }) }
        item {
            Text("模型分配", style = MaterialTheme.typography.titleMedium)
            Text("点击用途行选择模型，模型可以重复用于不同任务。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { ModelAssignmentCard(state.providers, state.models, state.assignments, onAssign) }
        item {
            Text("主动段评未单独分配时使用批量任务（cheap）模型。重排可不配置；失败时沿用本地检索排序。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { EmbeddingLibraryStatusCard(state.embeddingProgress, onRetry, onRebuild) }
    }
}

@Composable
private fun ProviderRow(provider: AiProviderEntity, models: List<AiModelEntity>, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = sectionCardColor(), border = BorderStroke(1.dp, sectionHairline())) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AiIdentityIcon(providerBrand(provider.adapter, provider.name, provider.baseUrl), size = 54.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(provider.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(models.take(2).joinToString(" · ") { it.modelName }.ifBlank { "添加模型后即可分配" },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${ProviderProtocolPolicy.providerChatDialect(provider).label()} · ${models.size} 个模型",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ModelAssignmentCard(providers: List<AiProviderEntity>, models: List<AiModelEntity>,
    assignments: Map<ModelRole, Long?>, onSelect: (ModelRole, Long?) -> Unit) {
    val providersById = providers.associateBy { it.id }
    val groups = listOf(
        "阅读与伴读" to listOf(ModelRole.CHAT, ModelRole.CHEAP, ModelRole.PROACTIVE_ANNOTATION, ModelRole.SUGGESTION),
        "检索与媒体" to listOf(ModelRole.EMBEDDING, ModelRole.RERANK, ModelRole.TTS, ModelRole.IMAGE)
    )
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        groups.forEach { (title, roles) ->
            MoReadSection(title = title) {
                roles.forEachIndexed { index, role ->
                    if (index > 0) MoReadRowDivider()
                    val eligible = models.filter { model -> model.type == role.requiredModelType() &&
                        providersById[model.providerId]?.let { ProviderProtocolPolicy.isSupported(it, model) } == true }
                    val fallback = when (role) {
                        ModelRole.PROACTIVE_ANNOTATION -> eligible.firstOrNull { it.id == assignments[ModelRole.CHEAP] }
                        ModelRole.SUGGESTION -> eligible.firstOrNull { it.id == assignments[ModelRole.CHEAP] }
                            ?: eligible.firstOrNull { it.id == assignments[ModelRole.CHAT] }
                        else -> null
                    }
                    ModelAssignmentRow(role, eligible, providersById, assignments[role], fallback) { onSelect(role, it) }
                }
            }
        }
    }
}

@Composable
private fun ModelAssignmentRow(role: ModelRole, models: List<AiModelEntity>, providersById: Map<Long, AiProviderEntity>,
    selectedModelId: Long?, fallback: AiModelEntity?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = models.firstOrNull { it.id == selectedModelId }
    val invalidAssignment = selectedModelId != null && selected == null
    val effective = if (selectedModelId == null) fallback else selected
    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "选择${role.label()}模型") { expanded = true }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        if (effective != null) ModelIdentityIcon(effective.modelName, effective.type)
        else AiIdentityIcon(null, role.icon())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(role.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(effective?.modelName ?: if (invalidAssignment) "已分配模型不可用" else role.unassignedLabel(), style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (effective != null) "${if (selected == null) "跟随默认 · " else ""}${providersById[effective.providerId]?.name.orEmpty()}"
                else if (invalidAssignment) "请重新选择模型或检查供应商配置" else role.purpose(), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) {
        val choices = listOf(SettingChoice("", role.unassignedLabel(), role.purpose(), role.icon())) + models.map { model ->
            SettingChoice(model.id.toString(), model.modelName, providersById[model.providerId]?.name.orEmpty(), model.type.icon(), modelBrand(model.modelName))
        }
        SettingChoiceDialog("选择${role.label()}模型", choices, selectedModelId?.toString().orEmpty(), { expanded = false }) {
            expanded = false
            onSelect(it.toLongOrNull())
        }
    }
}

private fun ModelRole.unassignedLabel(): String = when (this) {
    ModelRole.PROACTIVE_ANNOTATION -> "跟随批量任务（cheap）"
    ModelRole.SUGGESTION -> "跟随默认模型"
    ModelRole.RERANK -> "使用本地排序"
    else -> "选择模型"
}

private fun ModelRole.icon(): ImageVector = when (this) {
    ModelRole.CHAT -> Icons.Outlined.ChatBubbleOutline
    ModelRole.CHEAP -> Icons.Outlined.Bolt
    ModelRole.PROACTIVE_ANNOTATION -> Icons.Outlined.EditNote
    ModelRole.SUGGESTION -> Icons.Outlined.Lightbulb
    ModelRole.EMBEDDING -> Icons.Outlined.DataArray
    ModelRole.RERANK -> Icons.Outlined.Sort
    ModelRole.TTS -> Icons.Outlined.GraphicEq
    ModelRole.IMAGE -> Icons.Outlined.Image
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
                    EmbeddingIndexStage.DISABLED -> "按书籍管理 AI 索引"
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
                    append("已选 ${progress.enabledBooks}/${progress.totalBooks} 本")
                    if (progress.totalChapters > 0) {
                        append(" · ${progress.indexedChapters}/${progress.totalChapters} 章")
                    }
                    if (progress.message.isNotBlank()) append(" · ").append(progress.message)
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
            if (progress.stage != EmbeddingIndexStage.NOT_CONFIGURED &&
                progress.stage != EmbeddingIndexStage.DISABLED
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onRetry) { Text("重试已选") }
                    TextButton(onClick = onRebuild) { Text("重建已选") }
                }
            }
        }
    }
}

internal fun ModelRole.label(): String = when (this) {
    ModelRole.CHAT -> "主对话"
    ModelRole.CHEAP -> "批量任务"
    ModelRole.SUGGESTION -> "建议回复"
    ModelRole.PROACTIVE_ANNOTATION -> "主动段评"
    ModelRole.EMBEDDING -> "Embedding"
    ModelRole.RERANK -> "Rerank（可选）"
    ModelRole.TTS -> "语音朗读"
    ModelRole.IMAGE -> "生图"
}

private fun ModelRole.purpose(): String = when (this) {
    ModelRole.CHAT -> "角色对话与问答"
    ModelRole.CHEAP -> "摘要、索引等后台任务"
    ModelRole.SUGGESTION -> "输入框上方的快捷回复，不选就用批量任务模型"
    ModelRole.PROACTIVE_ANNOTATION -> "自动随读批注，未分配时使用 cheap"
    ModelRole.EMBEDDING -> "全文与想法检索"
    ModelRole.RERANK -> "书内检索候选重排"
    ModelRole.TTS -> "听书语音合成"
    ModelRole.IMAGE -> "角色头像与插图"
}

private fun ModelRole.requiredModelType(): AiModelType = when (this) {
    ModelRole.CHAT, ModelRole.CHEAP, ModelRole.SUGGESTION, ModelRole.PROACTIVE_ANNOTATION -> AiModelType.CHAT
    ModelRole.EMBEDDING -> AiModelType.EMBEDDING
    ModelRole.RERANK -> AiModelType.RERANK
    ModelRole.TTS -> AiModelType.TTS
    ModelRole.IMAGE -> AiModelType.IMAGE
}
