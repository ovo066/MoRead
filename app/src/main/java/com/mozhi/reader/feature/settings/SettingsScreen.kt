package com.mozhi.reader.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.ai.embedding.EmbeddingIndexStage
import com.mozhi.reader.ai.embedding.LibraryEmbeddingProgress
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.MoReadTokens
import com.mozhi.reader.ui.theme.ThemeMode
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.onAccent
import java.util.Locale

/** 设置页（design/ui-adaptation-plan.md §6）：仅大标题 + 三个小节，无 hero、无隐私卡。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenProvider: (Long) -> Unit,
    onOpenWebSearch: () -> Unit = {},
    onOpenTtsSettings: () -> Unit = {},
    onOpenImageGenSettings: () -> Unit = {},
    onOpenGlobalPresets: () -> Unit = {},
    onOpenUserMasks: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenApiLog: () -> Unit = {},
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 124.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Text("设置", style = MaterialTheme.typography.headlineLarge)
            }

            item { SectionLabel(title = "AI 服务") }
            items(state.providers, key = AiProviderEntity::id) { provider ->
                ProviderRow(
                    provider = provider,
                    models = modelsByProvider[provider.id].orEmpty(),
                    onClick = { onOpenProvider(provider.id) }
                )
            }
            item {
                DashedAddRow(
                    label = "添加 Provider",
                    onClick = { onOpenProvider(0) }
                )
            }
            item { WebSearchEntryCard(onClick = onOpenWebSearch) }

            item { SectionLabel(title = "模型分配") }
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
            item {
                SuggestionReplyCard(
                    enabled = state.suggestionRepliesEnabled,
                    onChange = viewModel::setSuggestionReplies
                )
            }
            item {
                AiAnnotationVisibilityCard(
                    enabled = state.showAiAnnotations,
                    onChange = viewModel::setShowAiAnnotations
                )
            }
            item { GlobalPresetEntryCard(onClick = onOpenGlobalPresets) }
            item { UserMaskEntryCard(onClick = onOpenUserMasks) }

            item { SectionLabel(title = "语音与生图") }
            item {
                TtsEntryCard(onClick = onOpenTtsSettings)
            }
            item {
                ImageGenEntryCard(onClick = onOpenImageGenSettings)
            }

            item { SectionLabel(title = "外观") }
            item {
                AppearanceCard(
                    appearance = state.appearance,
                    onThemeModeChange = viewModel::setThemeMode,
                    onAccentChange = viewModel::setAccentPreset,
                    onCustomAccent = viewModel::setCustomAccent
                )
            }

            item { SectionLabel(title = "应用") }
            item {
                AppPreferenceCard(
                    shelfLayout = state.shelfLayout,
                    coverCacheBytes = state.coverCacheBytes,
                    bookStorageBytes = state.bookStorageBytes,
                    onShelfLayoutChange = viewModel::setShelfLayout,
                    onClearCoverCache = viewModel::clearCoverCache
                )
            }

            item { SectionLabel(title = "数据") }
            item { BackupEntryCard(onClick = onOpenBackup) }

            item { SectionLabel(title = "诊断") }
            item {
                ApiLogEntryCard(onClick = onOpenApiLog)
            }

            item { SectionLabel(title = "关于") }
            item { AppUpdateCard() }
            item { AboutCard() }
        }

        if (state.isWorking) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
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

/** Provider 紧凑行：logo 位 + 名称 + 类型·方言 + 模型数，点击进详情二级页管理。 */
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


/** 模型分配（§6）：一张卡 5 行，右侧胶囊下拉 —— RikkaHub 式按「模型」分配。 */
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
                    androidx.compose.foundation.BorderStroke(
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
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("不分配") },
                    onClick = {
                        expanded = false
                        onSelect(null)
                    }
                )
                models.groupBy { it.providerId }.forEach { (providerId, group) ->
                    val providerName = providersById[providerId]?.name ?: "未知 Provider"
                    Text(
                        text = providerName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp)
                    )
                    group.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.modelName) },
                            trailingIcon = {
                                if (model.id == selectedModelId) {
                                    Icon(Icons.Outlined.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                expanded = false
                                onSelect(model.id)
                            }
                        )
                    }
                }
                if (models.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "没有可用模型，先在 Provider 卡里添加",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
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
                        color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
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
                }
            }
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

/** 外观卡：主题模式三选段 + 强调色圆点色板 + 自定义取色。 */
@Composable
private fun AppearanceCard(
    appearance: AppearanceSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentPreset) -> Unit,
    onCustomAccent: (Int) -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }

    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 7.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("主题模式", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = appearance.themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                        shape = MoReadTokens.CapsuleShape,
                        label = { Text(mode.label()) }
                    )
                }
            }

            Column {
                Text("强调色", style = MaterialTheme.typography.titleSmall)
                Text(
                    "背景与文字保持中性灰，只有强调处上色",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val dark = isDarkTheme()
                AccentPreset.entries.forEach { preset ->
                    AccentSwatch(
                        color = if (dark) preset.dark else preset.light,
                        label = preset.label,
                        selected = appearance.customAccentArgb == null &&
                            appearance.accent == preset,
                        onClick = { onAccentChange(preset) }
                    )
                }
            }
            CustomAccentRow(
                color = appearance.customAccentArgb?.let { Color(it) } ?: accentColor(),
                selected = appearance.customAccentArgb != null,
                onClick = { showColorPicker = true }
            )
        }
    }

    if (showColorPicker) {
        AccentColorPickerDialog(
            initial = appearance.customAccentArgb?.let { Color(it) } ?: accentColor(),
            onDismiss = { showColorPicker = false },
            onConfirm = { argb ->
                onCustomAccent(argb)
                showColorPicker = false
            }
        )
    }
}

@Composable
private fun AccentSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(color, CircleShape)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = CircleShape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = label,
                tint = color.onAccent(),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 显式入口：预设色之外，告诉用户这里可以连续取任意颜色。 */
@Composable
private fun CustomAccentRow(color: Color, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.58f)
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Colorize,
                    contentDescription = null,
                    tint = color.onAccent(),
                    modifier = Modifier.size(17.dp)
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp)
            ) {
                Text("自定义颜色", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (selected) {
                        String.format(Locale.ROOT, "#%06X · 已启用", color.toArgb() and 0x00FFFFFF)
                    } else {
                        "打开连续调色盘，可拖动选择任意颜色"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = "打开自定义调色盘",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 与批注、排版页共用连续 HSV 取色器。 */
@Composable
private fun AccentColorPickerDialog(
    initial: Color,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var preview by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义强调色") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NoteStyleColorPalette(
                    color = preview,
                    onColorChange = { preview = it }
                )
                Text(
                    "过暗或过亮的颜色会被自动调整，以保证在当前底色上可读。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(preview.toArgb()) }) { Text("使用") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/** 应用卡：书架布局 + 存储占用与封面缓存清理。 */
@Composable
private fun AppPreferenceCard(
    shelfLayout: ShelfLayout,
    coverCacheBytes: Long?,
    bookStorageBytes: Long?,
    onShelfLayoutChange: (ShelfLayout) -> Unit,
    onClearCoverCache: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 7.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("书架布局", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShelfLayout.entries.forEach { layout ->
                    FilterChip(
                        selected = shelfLayout == layout,
                        onClick = { onShelfLayoutChange(layout) },
                        shape = MoReadTokens.CapsuleShape,
                        label = { Text(layout.label()) }
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("书籍存储", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = bookStorageBytes?.let { "已占用 ${formatBytes(it)}" } ?: "统计中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("封面缓存", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = coverCacheBytes?.let { "已占用 ${formatBytes(it)}" } ?: "统计中…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = onClearCoverCache,
                    shape = MoReadTokens.CapsuleShape,
                    enabled = (coverCacheBytes ?: 0L) > 0L
                ) {
                    Text("清理")
                }
            }
        }
    }
}

/** 开源仓库地址：关于卡跳转与展示共用。 */
private const val REPO_URL = "https://github.com/ovo066/MoRead"

/** 关于卡：版本、开源仓库、许可与隐私说明。 */
@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 7.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("墨知 MoRead", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "版本 ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(REPO_URL) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("开源仓库", style = MaterialTheme.typography.titleSmall)
                    Text(
                        REPO_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "打开 GitHub 仓库",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column {
                Text("隐私", style = MaterialTheme.typography.titleSmall)
                Text(
                    "阅读数据、书签与对话记录保存在本机；API Key 加密存储，仅在你主动请求时发送给所配置的服务商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Column {
                Text("开源许可", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本项目以 GPL-3.0 许可开源，基于 Readium Kotlin Toolkit、Legado 章节规则等开源成果构建，完整第三方清单可在开源仓库中查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "日间"
    ThemeMode.DARK -> "夜间"
}

/** AI 建议回复开关：关掉后 AI 回合结束不再发起建议生成请求。 */
@Composable
private fun SuggestionReplyCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("AI 建议回复", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "AI 回复后自动生成 3 条可点选的快捷回复，模型走「建议回复」分配",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

/** 显示 AI 批注开关：关掉后阅读页不再渲染角色划线与「评」标记，详情页仍可回顾。 */
@Composable
private fun AiAnnotationVisibilityCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("显示 AI 批注", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "关闭后阅读页只显示你自己的划线，角色批注仍可在书籍详情回顾",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

/** 「语音朗读」入口卡：引擎切换与音色参数在独立二级页配置。 */
@Composable
private fun TtsEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("朗读引擎与音色", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "系统 TTS（如 Multi TTS）或云端 AI 语音，音色、语速、音调在此配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ShelfLayout.label(): String = when (this) {
    ShelfLayout.GRID -> "网格"
    ShelfLayout.LIST -> "列表"
}

/** 「生图 API」入口卡：gpt-image / NovelAI 的独立配置在二级页。 */
@Composable
private fun ImageGenEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("生图 API", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "gpt-image（生图/聊天两种端点）或 NovelAI，独立于对话模型配置",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


/** 「API 调用日志」入口卡：开关与记录列表在独立二级页。 */
@Composable
private fun ApiLogEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("API 调用日志", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "记录 AI 请求的地址、状态与耗时，排查连接问题；默认关闭，日志仅存本机",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 全局提示词预设二级页入口。 */
@Composable
private fun GlobalPresetEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Tune,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("全局预设", style = MaterialTheme.typography.titleSmall)
                Text(
                    "自定义提示词与注入位置",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 用户侧人设（SillyTavern user persona）二级页入口。 */
@Composable
private fun UserMaskEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.PersonOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("用户面具", style = MaterialTheme.typography.titleSmall)
                Text(
                    "创建、选择或关闭对话中的用户人设",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Agent 网络搜索配置二级页入口。 */
@Composable
private fun WebSearchEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("网络搜索", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Firecrawl、Exa、Tavily 与兼容接口",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 本地与 WebDAV 数据备份入口。 */
@Composable
private fun BackupEntryCard(onClick: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.CloudSync,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("数据备份", style = MaterialTheme.typography.titleSmall)
                Text(
                    "本地导入导出、WebDAV 与每日自动备份",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = "打开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ModelRole.label(): String = when (this) {
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
