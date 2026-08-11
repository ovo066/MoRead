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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
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
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.NoteStyleColorPalette
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
    onOpenAiServices: () -> Unit = {},
    onOpenWebSearch: () -> Unit = {},
    onOpenTtsSettings: () -> Unit = {},
    onOpenImageGenSettings: () -> Unit = {},
    onOpenFontLibrary: () -> Unit = {},
    onOpenImageLibrary: () -> Unit = {},
    onOpenGlobalPresets: () -> Unit = {},
    onOpenUserMasks: () -> Unit = {},
    onOpenBackup: () -> Unit = {},
    onOpenApiLog: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Text("设置", style = MaterialTheme.typography.headlineLarge)
            }

            item {
                SettingsGroup(title = "AI", icon = Icons.Outlined.AutoAwesome) {
                    SettingsRow(
                        icon = Icons.Outlined.Hub,
                        title = "AI 服务",
                        subtitle = aiServiceSummary(state.providers.size, state.models.size),
                        onClick = onOpenAiServices
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Language,
                        title = "网络搜索",
                        subtitle = "Firecrawl、Exa、Tavily 与兼容接口",
                        onClick = onOpenWebSearch
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Tune,
                        title = "全局预设",
                        subtitle = "自定义提示词与注入位置",
                        onClick = onOpenGlobalPresets
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.PersonOutline,
                        title = "用户面具",
                        subtitle = "创建、选择或关闭对话中的用户人设",
                        onClick = onOpenUserMasks
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.Bolt,
                        title = "AI 建议回复",
                        subtitle = "AI 回复后生成 3 条快捷回复，走「建议回复」模型分配",
                        checked = state.suggestionRepliesEnabled,
                        onCheckedChange = viewModel::setSuggestionReplies
                    )
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        icon = Icons.Outlined.BorderColor,
                        title = "显示 AI 批注",
                        subtitle = "关闭后阅读页只显示你自己的划线，角色批注仍可在详情回顾",
                        checked = state.showAiAnnotations,
                        onCheckedChange = viewModel::setShowAiAnnotations
                    )
                }
            }

            item {
                SettingsGroup(title = "语音与图像", icon = Icons.Outlined.GraphicEq) {
                    SettingsRow(
                        icon = Icons.Outlined.RecordVoiceOver,
                        title = "朗读引擎与音色",
                        subtitle = "系统 TTS（如 Multi TTS）或云端 AI 语音，语速音调在此配置",
                        onClick = onOpenTtsSettings
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Brush,
                        title = "生图 API",
                        subtitle = "gpt-image（生图/聊天两种端点）或 NovelAI，独立于对话模型",
                        onClick = onOpenImageGenSettings
                    )
                }
            }

            item {
                SettingsGroup(title = "外观", icon = Icons.Outlined.Palette) {
                    AppearanceCard(
                        appearance = state.appearance,
                        onThemeModeChange = viewModel::setThemeMode,
                        onAccentChange = viewModel::setAccentPreset,
                        onCustomAccent = viewModel::setCustomAccent
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.FontDownload,
                        title = "字体库",
                        subtitle = "导入、重命名和删除字体，供正文与高亮规则使用",
                        onClick = onOpenFontLibrary
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.PhotoLibrary,
                        title = "图片库",
                        subtitle = "导入、重命名和删除图片，供阅读背景与书籍封面使用",
                        onClick = onOpenImageLibrary
                    )
                }
            }

            item {
                SettingsGroup(title = "书架", icon = Icons.Outlined.AutoStories) {
                    SettingsBlock(title = "默认布局") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ShelfLayout.entries.forEach { layout ->
                                FilterChip(
                                    selected = state.shelfLayout == layout,
                                    onClick = { viewModel.setShelfLayout(layout) },
                                    shape = MoReadTokens.CapsuleShape,
                                    label = { Text(layout.label()) }
                                )
                            }
                        }
                    }
                }
            }

            item {
                SettingsGroup(title = "存储与数据", icon = Icons.Outlined.Storage) {
                    SettingsRow(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "书籍存储",
                        subtitle = state.bookStorageBytes?.let { "已占用 ${formatBytes(it)}" }
                            ?: "统计中…"
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.Image,
                        title = "封面缓存",
                        subtitle = state.coverCacheBytes?.let { "已占用 ${formatBytes(it)}" }
                            ?: "统计中…",
                        trailing = {
                            OutlinedButton(
                                onClick = viewModel::clearCoverCache,
                                shape = MoReadTokens.CapsuleShape,
                                enabled = (state.coverCacheBytes ?: 0L) > 0L
                            ) {
                                Text("清理")
                            }
                        }
                    )
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.CloudSync,
                        title = "数据备份",
                        subtitle = "本地导入导出、WebDAV 与每日自动备份",
                        onClick = onOpenBackup
                    )
                }
            }

            item {
                SettingsGroup(title = "关于", icon = Icons.Outlined.Info) {
                    AppUpdateCard()
                    SettingsRowDivider()
                    SettingsRow(
                        icon = Icons.Outlined.BugReport,
                        title = "API 调用日志",
                        subtitle = "记录请求地址、状态与耗时；默认关闭，日志仅存本机",
                        onClick = onOpenApiLog
                    )
                    SettingsRowDivider()
                    AboutCard()
                }
            }
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

/** 外观卡：主题模式三选段 + 强调色圆点色板 + 自定义取色。 */
@Composable
private fun AppearanceCard(
    appearance: AppearanceSettings,
    onThemeModeChange: (ThemeMode) -> Unit,
    onAccentChange: (AccentPreset) -> Unit,
    onCustomAccent: (Int) -> Unit
) {
    var showColorPicker by remember { mutableStateOf(false) }

    // 外层玻璃卡由 SettingsGroup 提供，这里只出内容。
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
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

/** 开源仓库地址：关于卡跳转与展示共用。 */
private const val REPO_URL = "https://github.com/ovo066/MoRead"

/** 关于卡：版本、开源仓库、许可与隐私说明。 */
@Composable
private fun AboutCard() {
    val uriHandler = LocalUriHandler.current
    // 外层玻璃卡由 SettingsGroup 提供，这里只出内容。
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
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

/** 书架布局标签，设置页与书架视图菜单共用。 */
internal fun ShelfLayout.label(): String = when (this) {
    ShelfLayout.GRID -> "网格"
    ShelfLayout.LIST -> "列表"
}

/** AI 服务入口行的副标题：没配过就写引导语，配过就报数。 */
private fun aiServiceSummary(providers: Int, models: Int): String =
    if (providers == 0) "还没有供应商，点进去添加一个" else "$providers 个供应商 · $models 个模型"

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "日间"
    ThemeMode.DARK -> "夜间"
}
