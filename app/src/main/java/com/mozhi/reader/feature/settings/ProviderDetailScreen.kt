package com.mozhi.reader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.PromptCacheTtl
import com.mozhi.reader.ai.client.ReasoningEffort
import com.mozhi.reader.ai.provider.AiProviderDraft
import com.mozhi.reader.ai.provider.AiModelDraft
import com.mozhi.reader.ai.provider.CatalogModel
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.theme.MoReadTokens

/**
 * Provider 详情二级页（providerId = 0 新建）：基本信息 + 模型管理 + 连接测试。
 * 新建保存成功后就地转入编辑模式，可直接接着拉取/添加模型。
 */
@Composable
fun ProviderDetailScreen(
    onBack: () -> Unit,
    viewModel: ProviderDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val catalogPick by viewModel.catalogPick.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showModelEditor by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf<AiModelEntity?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                ProviderDetailEvent.Deleted -> onBack()
                is ProviderDetailEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    MoReadBackdrop {
        Box(
            Modifier
                .fillMaxSize()
                .imePadding()
        ) {
            ProviderForm(
                state = state,
                onBack = onBack,
                onSave = viewModel::save,
                onTest = viewModel::test,
                onDelete = { confirmDelete = true },
                onFetchModels = viewModel::fetchModelCatalog,
                onAddModel = {
                    editingModel = null
                    showModelEditor = true
                },
                onEditModel = { model ->
                    editingModel = model
                    showModelEditor = true
                }
            )
            if (state.isWorking) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .align(Alignment.TopCenter)
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 20.dp, vertical = 18.dp)
            )
        }
    }

    if (confirmDelete) {
        val provider = state.provider
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除 ${provider?.name ?: "Provider"}？") },
            text = { Text("对应的加密 API Key、模型与分配也会清除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.delete()
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }

    state.provider?.let { provider ->
        if (showModelEditor) {
            ModelEditorDialog(
                provider = provider,
                model = editingModel,
                onDismiss = { showModelEditor = false },
                onConfirm = { draft ->
                    showModelEditor = false
                    viewModel.saveModel(draft)
                },
                onDelete = editingModel?.let { model ->
                    {
                        showModelEditor = false
                        viewModel.removeModel(model)
                    }
                }
            )
        }
    }

    catalogPick?.let { pick ->
        ModelCatalogPickDialog(
            pick = pick,
            onDismiss = viewModel::dismissCatalogPick,
            onConfirm = viewModel::confirmCatalogPick
        )
    }
}

@Composable
private fun ProviderForm(
    state: ProviderDetailState,
    onBack: () -> Unit,
    onSave: (AiProviderDraft) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
    onFetchModels: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (AiModelEntity) -> Unit
) {
    val provider = state.provider
    var name by remember(provider?.id) { mutableStateOf(provider?.name.orEmpty()) }
    var baseUrl by remember(provider?.id) {
        mutableStateOf(provider?.baseUrl ?: ApiDialect.OPENAI.defaultBaseUrl())
    }
    var apiKey by remember(provider?.id) { mutableStateOf("") }
    var extraJson by remember(provider?.id) { mutableStateOf(provider?.extraJson ?: "{}") }
    var adapter by remember(provider?.id) {
        mutableStateOf(provider?.adapter ?: AiProviderAdapter.CUSTOM)
    }
    var dialect by remember(provider?.id) {
        mutableStateOf(
            ProviderProtocolPolicy.normalizeChatDialect(
                adapter,
                ApiDialect.fromWire(provider?.apiFormat)
            )
        )
    }
    var reasoning by remember(provider?.id) {
        mutableStateOf(ChatOptions.fromExtraJson(provider?.extraJson).reasoning)
    }
    var cacheTtl by remember(provider?.id) {
        mutableStateOf(ChatOptions.fromExtraJson(provider?.extraJson).cacheTtl)
    }

    fun buildDraft() = AiProviderDraft(
        id = provider?.id ?: 0,
        name = name,
        baseUrl = baseUrl,
        type = provider?.type ?: AiProviderType.CHAT,
        apiFormat = dialect.name,
        adapter = adapter,
        extraJson = extraJson.withReasoningKey(reasoning).withCacheKeys(cacheTtl),
        apiKey = apiKey
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
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
                    text = if (state.isNew) "添加 AI Provider" else provider!!.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!state.isNew) {
                        FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                            IconButton(onClick = onDelete) {
                                Icon(
                                    Icons.Outlined.Delete,
                                    contentDescription = "删除 Provider",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
                        IconButton(
                            onClick = { onSave(buildDraft()) },
                            enabled = name.isNotBlank() && baseUrl.isNotBlank()
                        ) {
                            Icon(
                                Icons.Outlined.Check,
                                contentDescription = "保存",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }

        item { SectionLabel(title = "基本信息") }
        item {
            FrostedSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.isNew) {
                        Column {
                            Text("供应商预设", style = MaterialTheme.typography.titleSmall)
                            ProviderPresetChips(
                                selected = adapter,
                                onSelect = { preset ->
                                    adapter = preset?.adapter ?: AiProviderAdapter.CUSTOM
                                    preset?.let {
                                        name = it.name
                                        baseUrl = it.baseUrl
                                        dialect = it.dialect
                                    }
                                }
                            )
                        }
                    } else {
                        Text(
                            "适配：${adapter.label()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Column {
                        Text("默认聊天协议", style = MaterialTheme.typography.titleSmall)
                        DialectChips(
                            selected = dialect,
                            options = ProviderProtocolPolicy.supportedChatDialects(adapter),
                            onSelect = { picked ->
                                dialect = picked
                                if (state.isNew) baseUrl = picked.defaultBaseUrl()
                            }
                        )
                        Text(
                            if (adapter == AiProviderAdapter.OPENROUTER) {
                                "只影响对话模型，其余能力自动选择接口"
                            } else {
                                "每个模型也可以单独指定协议"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        supportingText = {
                            Text("支持 HTTP / HTTPS；HTTP 仅建议用于可信局域网。示例：${dialect.defaultBaseUrl()}")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = {
                            Text(if (state.isNew) "API Key" else "API Key（留空保持不变）")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "对话、向量、语音、生图的能力在每个模型上单独设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item { SectionLabel(title = "请求选项") }
        item {
            FrostedSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column {
                        Text("思考等级", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "只对推理模型生效，想得越深越慢、越贵",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = reasoning == null,
                                onClick = { reasoning = null },
                                label = { Text("默认") }
                            )
                            ReasoningEffort.entries.forEach { effort ->
                                FilterChip(
                                    selected = reasoning == effort,
                                    onClick = { reasoning = effort },
                                    label = { Text(effort.label()) }
                                )
                            }
                        }
                    }
                    if (dialect == ApiDialect.CLAUDE) {
                        Column {
                            Text("提示词缓存", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "角色设定等重复内容缓存在服务端，长对话更省钱",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = cacheTtl == null,
                                    onClick = { cacheTtl = null },
                                    label = { Text("关闭") }
                                )
                                FilterChip(
                                    selected = cacheTtl == PromptCacheTtl.FIVE_MINUTES,
                                    onClick = { cacheTtl = PromptCacheTtl.FIVE_MINUTES },
                                    label = { Text("5 分钟") }
                                )
                                FilterChip(
                                    selected = cacheTtl == PromptCacheTtl.ONE_HOUR,
                                    onClick = { cacheTtl = PromptCacheTtl.ONE_HOUR },
                                    label = { Text("1 小时") }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = extraJson,
                        onValueChange = { extraJson = it },
                        label = { Text("厂商附加参数 JSON") },
                        supportingText = {
                            Text("如 temperature、top_p、max_tokens")
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        if (!state.isNew) {
            item { SectionLabel(title = "模型") }
            item {
                ModelsCard(
                    models = state.models,
                    onFetchModels = onFetchModels,
                    onAddModel = onAddModel,
                    onEditModel = onEditModel
                )
            }
            item {
                Button(
                    onClick = onTest,
                    shape = MoReadTokens.CapsuleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) { Text(if (state.connected) "测试连接（已连接）" else "测试连接") }
            }
        } else {
            item {
                Text(
                    text = "保存后就能拉取模型列表并测试连接。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }
    }
}

@Composable
private fun DialectChips(
    selected: ApiDialect,
    options: List<ApiDialect>,
    onSelect: (ApiDialect) -> Unit
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.chunked(2).forEach { rowDialects ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowDialects.forEach { apiDialect ->
                    FilterChip(
                        selected = selected == apiDialect,
                        onClick = { onSelect(apiDialect) },
                        label = { Text(apiDialect.label()) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderPresetChips(
    selected: AiProviderAdapter,
    onSelect: (ProviderPreset?) -> Unit
) {
    FlowRow(
        modifier = Modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CommonProviderPresets.forEach { preset ->
            FilterChip(
                selected = selected == preset.adapter,
                onClick = { onSelect(preset) },
                label = { Text(preset.name) }
            )
        }
        FilterChip(
            selected = selected == AiProviderAdapter.CUSTOM,
            onClick = { onSelect(null) },
            label = { Text("自定义") }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelTypeChips(selected: AiModelType, onSelect: (AiModelType) -> Unit) {
    FlowRow(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AiModelType.entries.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(type.label()) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelsCard(
    models: List<AiModelEntity>,
    onFetchModels: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (AiModelEntity) -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                models.forEach { model ->
                    InputChip(
                        selected = false,
                        onClick = { onEditModel(model) },
                        shape = MoReadTokens.CapsuleShape,
                        label = {
                            Text(
                                "${model.type.label()} · ${model.modelName}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Outlined.Tune,
                                contentDescription = "配置 ${model.modelName}",
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
                AssistChip(
                    onClick = onFetchModels,
                    shape = MoReadTokens.CapsuleShape,
                    label = { Text("拉取模型") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                AssistChip(
                    onClick = onAddModel,
                    shape = MoReadTokens.CapsuleShape,
                    label = { Text("手动添加") },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            if (models.isEmpty()) {
                Text(
                    "还没有模型：拉取或手动添加后才能在「模型分配」里使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

/** 模型级能力、端点与参数配置；新建时名称可用逗号/换行批量填写。 */
@Composable
private fun ModelEditorDialog(
    provider: AiProviderEntity,
    model: AiModelEntity?,
    onDismiss: () -> Unit,
    onConfirm: (AiModelDraft) -> Unit,
    onDelete: (() -> Unit)?
) {
    var input by remember(model?.id) { mutableStateOf(model?.modelName.orEmpty()) }
    var type by remember(model?.id) { mutableStateOf(model?.type ?: AiModelType.CHAT) }
    var chatApiFormat by remember(model?.id) {
        mutableStateOf(model?.chatApiFormat.orEmpty())
    }
    var endpointPath by remember(model?.id) { mutableStateOf(model?.endpointPath.orEmpty()) }
    var extraJson by remember(model?.id) { mutableStateOf(model?.extraJson ?: "{}") }
    val automaticEndpoint = defaultModelEndpoint(provider.adapter, type)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (model == null) "添加模型到 ${provider.name}" else "配置模型") },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("模型名") },
                        supportingText = {
                            Text(if (model == null) "可用逗号或换行批量添加" else "服务端模型 ID")
                        },
                        minLines = 1,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Text("模型能力", style = MaterialTheme.typography.titleSmall)
                    ModelTypeChips(selected = type, onSelect = { type = it })
                }
                if (type == AiModelType.CHAT) {
                    item {
                        Text("聊天协议", style = MaterialTheme.typography.titleSmall)
                        ModelChatDialectChips(
                            provider = provider,
                            selectedWire = chatApiFormat,
                            onSelect = { chatApiFormat = it }
                        )
                    }
                }
                item {
                    OutlinedTextField(
                        value = endpointPath,
                        onValueChange = { endpointPath = it },
                        label = { Text("专用端点（可选）") },
                        supportingText = {
                            Text(
                                if (automaticEndpoint.isBlank()) {
                                    "留空使用供应商协议的默认对话端点"
                                } else {
                                    "留空自动使用 $automaticEndpoint"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    OutlinedTextField(
                        value = extraJson,
                        onValueChange = { extraJson = it },
                        label = { Text("模型专用参数 JSON") },
                        supportingText = {
                            Text(
                                when (type) {
                                    AiModelType.IMAGE ->
                                        "例：{\"body\":{\"aspect_ratio\":\"16:9\",\"resolution\":\"2K\"}}"
                                    AiModelType.TTS -> if (provider.adapter == AiProviderAdapter.MINIMAX) {
                                        "MiniMax 例：{\"body\":{\"group_id\":\"可选\",\"voice_setting\":{\"voice_id\":\"male-qn-qingse\",\"speed\":1.0,\"vol\":1.0,\"pitch\":0},\"audio_setting\":{\"format\":\"mp3\"}}}"
                                    } else {
                                        "OpenAI 例：{\"body\":{\"voice\":\"alloy\",\"response_format\":\"mp3\",\"speed\":1.0}}"
                                    }
                                    else -> "会覆盖供应商上的同名参数"
                                }
                            )
                        },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        AiModelDraft(
                            id = model?.id ?: 0,
                            modelName = input,
                            type = type,
                            chatApiFormat = chatApiFormat.takeIf {
                                type == AiModelType.CHAT
                            }.orEmpty(),
                            endpointPath = endpointPath,
                            extraJson = extraJson
                        )
                    )
                },
                enabled = input.isNotBlank()
            ) { Text(if (model == null) "添加" else "保存") }
        },
        dismissButton = {
            Row {
                onDelete?.let {
                    TextButton(onClick = it) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChatDialectChips(
    provider: AiProviderEntity,
    selectedWire: String,
    onSelect: (String) -> Unit
) {
    val inherited = ProviderProtocolPolicy.providerChatDialect(provider)
    FlowRow(
        modifier = Modifier.padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedWire.isBlank(),
            onClick = { onSelect("") },
            label = { Text("跟随默认 · ${inherited.label()}") }
        )
        ProviderProtocolPolicy.supportedChatDialects(provider.adapter).forEach { dialect ->
            FilterChip(
                selected = selectedWire == dialect.name,
                onClick = { onSelect(dialect.name) },
                label = { Text(dialect.label()) }
            )
        }
    }
}

/** 拉取到的模型目录：多选加入，已添加的置灰。 */
@Composable
private fun ModelCatalogPickDialog(
    pick: ModelCatalogPick,
    onDismiss: () -> Unit,
    onConfirm: (List<CatalogModel>) -> Unit
) {
    val selected = remember(pick) { mutableStateOf(setOf<String>()) }
    var query by rememberSaveable(pick.provider.id) { mutableStateOf("") }
    val normalizedQuery = query.trim()
    val visibleModels = remember(pick.models, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            pick.models
        } else {
            pick.models.filter { model ->
                model.modelName.contains(normalizedQuery, ignoreCase = true) ||
                    model.endpointPath.contains(normalizedQuery, ignoreCase = true) ||
                    model.type.label().contains(normalizedQuery, ignoreCase = true)
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${pick.provider.name} · ${pick.models.size} 个模型") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    singleLine = true,
                    label = { Text("搜索模型") },
                    placeholder = { Text("名称或类型") }
                )
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    if (visibleModels.isEmpty()) {
                        item {
                            Text(
                                text = "没有匹配的模型",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    items(visibleModels, key = CatalogModel::key) { model ->
                        val added = model.key in pick.alreadyAdded
                        val checked = added || model.key in selected.value
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !added) {
                                    selected.value = if (model.key in selected.value) {
                                        selected.value - model.key
                                    } else {
                                        selected.value + model.key
                                    }
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = !added
                            )
                            Column(modifier = Modifier.padding(start = 4.dp)) {
                                Text(
                                    text = model.modelName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (added) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = model.type.label(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(pick.models.filter { it.key in selected.value })
                },
                enabled = selected.value.isNotEmpty()
            ) { Text("添加所选（${selected.value.size}）") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

private fun defaultModelEndpoint(
    adapter: AiProviderAdapter,
    type: AiModelType
): String = when (type) {
    AiModelType.CHAT -> ""
    AiModelType.EMBEDDING -> "/embeddings"
    AiModelType.TTS -> if (adapter == AiProviderAdapter.MINIMAX) "/t2a_v2" else "/audio/speech"
    AiModelType.IMAGE -> if (adapter == AiProviderAdapter.OPENROUTER) {
        "/images"
    } else {
        "/images/generations"
    }
}
