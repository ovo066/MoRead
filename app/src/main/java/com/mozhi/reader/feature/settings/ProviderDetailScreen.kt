package com.mozhi.reader.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mozhi.reader.ui.components.MoReadPageDialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.PromptCacheTtl
import com.mozhi.reader.ai.client.ReasoningEffort
import com.mozhi.reader.ai.provider.AiModelDraft
import com.mozhi.reader.ai.provider.AiProviderDraft
import com.mozhi.reader.ai.provider.CatalogModel
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection

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
internal fun ProviderForm(
    state: ProviderDetailState, onBack: () -> Unit, onSave: (AiProviderDraft) -> Unit,
    onTest: () -> Unit, onDelete: () -> Unit, onFetchModels: () -> Unit,
    onAddModel: () -> Unit, onEditModel: (AiModelEntity) -> Unit
) {
    val provider = state.provider
    var name by remember(provider?.id) { mutableStateOf(provider?.name.orEmpty()) }
    var baseUrl by remember(provider?.id) { mutableStateOf(provider?.baseUrl ?: ApiDialect.OPENAI.defaultBaseUrl()) }
    var apiKey by remember(provider?.id) { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var extraJson by remember(provider?.id) { mutableStateOf(provider?.extraJson ?: "{}") }
    var adapter by remember(provider?.id) { mutableStateOf(provider?.adapter ?: AiProviderAdapter.CUSTOM) }
    var dialect by remember(provider?.id) { mutableStateOf(ProviderProtocolPolicy.normalizeChatDialect(adapter, ApiDialect.fromWire(provider?.apiFormat))) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val options = remember(extraJson) { ChatOptions.fromExtraJson(extraJson) }
    val dirty = provider != null && (name != provider.name || baseUrl != provider.baseUrl ||
        apiKey.isNotBlank() || extraJson != provider.extraJson || dialect.name != provider.apiFormat)
    val validJson = remember(extraJson) { runCatching { com.mozhi.reader.ai.client.AiJson.parseToJsonElement(extraJson.ifBlank { "{}" }) is kotlinx.serialization.json.JsonObject }.getOrDefault(false) }
    fun draft() = AiProviderDraft(id = provider?.id ?: 0, name = name, baseUrl = baseUrl,
        type = provider?.type ?: AiProviderType.CHAT, apiFormat = dialect.name, adapter = adapter,
        extraJson = extraJson.ifBlank { "{}" }, apiKey = apiKey)

    MoReadSecondaryPage(title = if (state.isNew) "添加供应商" else "供应商配置",
        subtitle = if (state.isNew) "选择服务，填入连接信息，然后添加模型。" else "${provider!!.name} · 管理连接、模型与默认参数",
        onBack = onBack,
        actions = { if (!state.isNew) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除供应商", tint = MaterialTheme.colorScheme.error) } },
        bottomBar = {
            Button(onClick = { onSave(draft()) }, enabled = !state.isWorking && name.isNotBlank() && baseUrl.isNotBlank() && validJson,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding().heightIn(min = 52.dp)) {
                Icon(Icons.Outlined.Check, null, Modifier.padding(end = 8.dp).size(18.dp))
                Text("保存配置")
            }
        }) {
        if (state.isNew) item {
            val preset = CommonProviderPresets.firstOrNull { it.adapter == adapter && it.baseUrl == baseUrl }
            SettingChoiceField("供应商", preset?.name ?: adapter.label(), preset?.baseUrl.orEmpty(),
                CommonProviderPresets.map { SettingChoice(it.baseUrl, it.name, it.baseUrl, brand = providerBrand(it.adapter, it.name, it.baseUrl)) } +
                    SettingChoice("", "自定义供应商", "兼容接口、本地服务或中转地址", Icons.Outlined.Dns),
                onSelect = { key ->
                    val choice = CommonProviderPresets.firstOrNull { it.baseUrl == key }
                    adapter = choice?.adapter ?: AiProviderAdapter.CUSTOM
                    if (choice != null) { name = choice.name; baseUrl = choice.baseUrl; dialect = choice.dialect }
                }, brand = providerBrand(adapter, name, baseUrl), icon = Icons.Outlined.Hub)
        }
        item {
            MoReadSection(title = "连接信息", icon = Icons.Outlined.Link) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (!state.isNew) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AiIdentityIcon(providerBrand(adapter, name, baseUrl), size = 48.dp)
                        Column { Text(name, style = MaterialTheme.typography.titleMedium)
                            Text(adapter.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Badge, null) }, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("接口地址 · Base URL") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Language, null) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(apiKey, { apiKey = it }, label = { Text(if (state.isNew) "API Key" else "API Key（留空保持不变）") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Key, null) },
                        trailingIcon = { IconButton(onClick = { keyVisible = !keyVisible }) { Icon(if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "切换密钥可见性") } },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    DialectField(dialect, ProviderProtocolPolicy.supportedChatDialects(adapter)) { picked ->
                        if (state.isNew && baseUrl == dialect.defaultBaseUrl()) baseUrl = picked.defaultBaseUrl()
                        dialect = picked
                    }
                }
            }
        }
        if (!state.isNew) {
            item {
                MoReadSection(title = "已添加的模型 · ${state.models.size}", icon = Icons.Outlined.AutoAwesome) {
                    ModelsCard(state.models, !state.isWorking && !dirty, onFetchModels, onAddModel, onEditModel)
                }
            }
            item {
                MoReadSection {
                    MoReadRow(title = "连接测试", icon = Icons.Outlined.WifiTethering,
                        subtitle = if (dirty) "连接信息已修改，请先保存" else if (state.connected) "上次测试成功" else "使用已保存的连接与模型",
                        trailing = { TextButton(onClick = onTest, enabled = !state.isWorking && !dirty) { Text("测试") } })
                }
            }
        }
        item {
            MoReadSection(title = "默认生成参数", icon = Icons.Outlined.Tune, footer = "各模型可以覆盖这些默认值；留空使用服务端默认。") {
                ModelParameterRows(extraJson, { extraJson = it }, temperatureMax = if (dialect == ApiDialect.CLAUDE) 1.0 else 2.0)
                MoReadRowDivider(inset = 16.dp)
                MoReadBlock {
                    SettingChoiceField("思考强度", options.reasoning?.label() ?: "默认", options.reasoning?.name.orEmpty(),
                        listOf(SettingChoice("", "默认", "由模型决定")) + ReasoningEffort.entries.map { SettingChoice(it.name, it.label()) },
                        { extraJson = extraJson.withReasoningKey(it.takeIf(String::isNotEmpty)?.let(ReasoningEffort::valueOf)) }, icon = Icons.Outlined.Psychology)
                }
                if (dialect == ApiDialect.CLAUDE) MoReadBlock {
                    SettingChoiceField("提示词缓存", when (options.cacheTtl) { null -> "关闭"; PromptCacheTtl.ONE_HOUR -> "1 小时"; else -> "5 分钟" },
                        options.cacheTtl?.name.orEmpty(), listOf(SettingChoice("", "关闭"), SettingChoice(PromptCacheTtl.FIVE_MINUTES.name, "5 分钟"), SettingChoice(PromptCacheTtl.ONE_HOUR.name, "1 小时")),
                        { extraJson = extraJson.withCacheKeys(it.takeIf(String::isNotEmpty)?.let(PromptCacheTtl::valueOf)) }, icon = Icons.Outlined.Cached)
                }
            }
        }
        item {
            MoReadSection(title = "高级设置") {
                MoReadRow(title = "附加请求参数", subtitle = "请求头与厂商专用 JSON", icon = Icons.Outlined.Code, onClick = { advanced = !advanced },
                    trailing = { Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) })
                if (advanced) MoReadBlock {
                    OutlinedTextField(extraJson, { extraJson = it }, label = { Text("高级参数 JSON") }, isError = !validJson,
                        minLines = 3, maxLines = 10, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (state.isNew) item { Text("保存后可以拉取模型列表，也可以手动添加模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DialectField(selected: ApiDialect, options: List<ApiDialect>, onSelect: (ApiDialect) -> Unit) {
    SettingChoiceField("默认聊天协议", selected.label(), selected.name,
        options.map { SettingChoice(it.name, it.label(), icon = Icons.Outlined.Api) },
        { onSelect(ApiDialect.valueOf(it)) }, icon = Icons.Outlined.Api)
}

@Composable
private fun ModelTypeField(selected: AiModelType, onSelect: (AiModelType) -> Unit) {
    SettingChoiceField("模型能力", selected.label(), selected.name,
        AiModelType.entries.map { SettingChoice(it.name, it.label(), icon = it.icon()) },
        { onSelect(AiModelType.valueOf(it)) }, icon = selected.icon())
}

@Composable
private fun ModelsCard(models: List<AiModelEntity>, canFetch: Boolean, onFetchModels: () -> Unit,
    onAddModel: () -> Unit, onEditModel: (AiModelEntity) -> Unit) {
    Column {
        models.forEachIndexed { index, model ->
            if (index > 0) MoReadRowDivider()
            Row(Modifier.fillMaxWidth().clickable { onEditModel(model) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ModelIdentityIcon(model.modelName, model.type)
                Column(Modifier.weight(1f)) {
                    Text(model.modelName, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${model.type.label()} · 点击配置参数", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Outlined.ChevronRight, "配置 ${model.modelName}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (models.isEmpty()) Text("还没有模型，拉取目录或手动添加一个。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFetchModels, enabled = canFetch, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.CloudDownload, null, Modifier.padding(end = 6.dp).size(18.dp)); Text("拉取模型")
            }
            OutlinedButton(onClick = onAddModel, shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Outlined.Add, null, Modifier.padding(end = 6.dp).size(18.dp)); Text("手动添加")
            }
        }
    }
}

/** Model editing uses the same full-page parameter layout as provider configuration. */
@Composable
internal fun ModelEditorDialog(
    provider: AiProviderEntity, model: AiModelEntity?, onDismiss: () -> Unit,
    onConfirm: (AiModelDraft) -> Unit, onDelete: (() -> Unit)?
) {
    MoReadPageDialog(onDismissRequest = onDismiss) {
        ModelEditorContent(provider, model, onDismiss, onConfirm, onDelete)
    }
}

@Composable
internal fun ModelEditorContent(
    provider: AiProviderEntity, model: AiModelEntity?, onDismiss: () -> Unit,
    onConfirm: (AiModelDraft) -> Unit, onDelete: (() -> Unit)?
) {
    var input by remember(model?.id) { mutableStateOf(model?.modelName.orEmpty()) }
    var type by remember(model?.id) { mutableStateOf(model?.type ?: AiModelType.CHAT) }
    var chatApiFormat by remember(model?.id) { mutableStateOf(model?.chatApiFormat.orEmpty()) }
    var endpointPath by remember(model?.id) { mutableStateOf(model?.endpointPath.orEmpty()) }
    var extraJson by remember(model?.id) { mutableStateOf(model?.extraJson ?: "{}") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val automaticEndpoint = defaultModelEndpoint(provider.adapter, type)
    val validJson = remember(extraJson) { runCatching { com.mozhi.reader.ai.client.AiJson.parseToJsonElement(extraJson.ifBlank { "{}" }) is kotlinx.serialization.json.JsonObject }.getOrDefault(false) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        MoReadSecondaryPage(title = if (model == null) "添加模型" else "模型设置", subtitle = "${provider.name} · 模型能力与生成参数",
            modifier = Modifier.imePadding(), onBack = onDismiss,
            actions = { if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "删除模型", tint = MaterialTheme.colorScheme.error) } },
            bottomBar = {
                Button(onClick = { onConfirm(AiModelDraft(id = model?.id ?: 0,
                    modelName = input, type = type, chatApiFormat = chatApiFormat, endpointPath = endpointPath, extraJson = extraJson.ifBlank { "{}" })) },
                    enabled = input.isNotBlank() && validJson, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).navigationBarsPadding().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.padding(end = 8.dp).size(18.dp)); Text("保存模型")
                }
            }) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ModelIdentityIcon(input, type, size = 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(input.ifBlank { "新模型" }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(provider.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                MoReadSection(title = "基本信息") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedTextField(input, { input = it }, label = { Text("模型名称") },
                            supportingText = { Text(if (model == null) "使用服务端模型 ID，可用逗号或换行批量添加" else "服务端模型 ID") },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                        ModelTypeField(type) { type = it }
                        if (type == AiModelType.CHAT) ModelChatDialectField(provider, chatApiFormat) { chatApiFormat = it }
                    }
                }
            }
            if (type == AiModelType.CHAT) item {
                val dialect = chatApiFormat.takeIf(String::isNotBlank)?.let(ApiDialect::valueOf) ?: ProviderProtocolPolicy.providerChatDialect(provider)
                MoReadSection(title = "生成参数", icon = Icons.Outlined.Tune, footer = "未单独设置的参数跟随供应商；修改只影响这个模型。") {
                    ModelParameterRows(extraJson, { extraJson = it }, inheritedJson = provider.extraJson,
                        temperatureMax = if (dialect == ApiDialect.CLAUDE) 1.0 else 2.0)
                }
            }
            item {
                MoReadSection(title = "高级设置") {
                    MoReadRow(title = "专用端点与附加参数", icon = Icons.Outlined.Code,
                        subtitle = if (endpointPath.isBlank()) "跟随供应商默认接口" else endpointPath,
                        onClick = { advanced = !advanced }, trailing = { Icon(if (advanced) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) })
                    if (advanced) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OutlinedTextField(endpointPath, { endpointPath = it }, label = { Text("专用端点（可选）") }, singleLine = true,
                            supportingText = { Text(if (automaticEndpoint.isBlank()) "留空使用默认对话端点" else "留空使用 $automaticEndpoint") },
                            shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(extraJson, { extraJson = it }, label = { Text("模型专用参数 JSON") },
                            supportingText = { Text("覆盖供应商同名参数，可设置 body 与 headers") }, isError = !validJson,
                            minLines = 3, maxLines = 10, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelChatDialectField(provider: AiProviderEntity, selectedWire: String, onSelect: (String) -> Unit) {
    val inherited = ProviderProtocolPolicy.providerChatDialect(provider)
    val choices = listOf(SettingChoice("", "跟随供应商", inherited.label(), Icons.Outlined.Link)) +
        ProviderProtocolPolicy.supportedChatDialects(provider.adapter).map { SettingChoice(it.name, it.label(), icon = Icons.Outlined.Api) }
    SettingChoiceField("聊天协议", choices.firstOrNull { it.key == selectedWire }?.title ?: "跟随供应商",
        selectedWire, choices, onSelect, icon = Icons.Outlined.Api)
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
                            ModelIdentityIcon(model.modelName, model.type, size = 34.dp)
                            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
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
    AiModelType.RERANK -> "/rerank"
    AiModelType.TTS -> if (adapter == AiProviderAdapter.MINIMAX) "/t2a_v2" else "/audio/speech"
    AiModelType.IMAGE -> if (adapter == AiProviderAdapter.OPENROUTER) {
        "/images"
    } else {
        "/images/generations"
    }
}
