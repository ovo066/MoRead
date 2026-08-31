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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
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
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.core.datastore.ShelfLayout
import com.mozhi.reader.ui.components.MoReadBlock
import com.mozhi.reader.ui.components.MoReadRootPage
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowAction
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.components.NoteStyleColorPalette
import com.mozhi.reader.ui.theme.AccentPreset
import com.mozhi.reader.ui.theme.AppearanceSettings
import com.mozhi.reader.ui.theme.ThemeMode
import com.mozhi.reader.ui.theme.accentColor
import com.mozhi.reader.ui.theme.isDarkTheme
import com.mozhi.reader.ui.theme.onAccent
import java.util.Locale

/** 设置首页按用户目标分组，常用入口直接可达，复杂选项再进入二级页。 */
@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onOpenReading: () -> Unit,
    onOpenTts: () -> Unit,
    onOpenAiServices: () -> Unit,
    onOpenAi: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenData: () -> Unit,
    onOpenAbout: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadRootPage(title = "设置", contentPadding = contentPadding) {
        item {
            MoReadSection(title = "阅读体验", icon = Icons.AutoMirrored.Outlined.MenuBook) {
                MoReadRow(
                    icon = Icons.Outlined.Palette,
                    title = "阅读与外观",
                    subtitle = "主题、字体、背景、书架与图像",
                    onClick = onOpenReading
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "朗读与音色",
                    subtitle = "朗读引擎、参数、音色与缓存",
                    onClick = onOpenTts
                )
            }
        }
        item {
            MoReadSection(title = "智能服务", icon = Icons.Outlined.AutoAwesome) {
                MoReadRow(
                    icon = Icons.Outlined.Hub,
                    title = "AI 服务",
                    subtitle = aiServiceSummary(state.providers.size, state.models.size),
                    onClick = onOpenAiServices
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Psychology,
                    title = "伴读与联网",
                    subtitle = "记忆、多气泡、主动行为与联网",
                    onClick = onOpenAi
                )
            }
        }
        item {
            MoReadSection(title = "数据管理", icon = Icons.Outlined.Storage) {
                MoReadRow(
                    icon = Icons.Outlined.CloudSync,
                    title = "备份与恢复",
                    subtitle = "本地、WebDAV 与自动备份",
                    onClick = onOpenBackup
                )
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.Storage,
                    title = "存储与缓存",
                    subtitle = state.bookStorageBytes?.let { "书籍已占用 ${formatBytes(it)}" }
                        ?: "查看书籍与封面缓存",
                    onClick = onOpenData
                )
            }
        }
        item {
            MoReadSection(title = "应用", icon = Icons.Outlined.Info) {
                MoReadRow(
                    icon = Icons.Outlined.Info,
                    title = "关于与诊断",
                    subtitle = "版本 ${BuildConfig.VERSION_NAME} · 更新、日志与许可",
                    onClick = onOpenAbout
                )
            }
        }
    }
}
@Composable
fun AiAndCompanionSettingsScreen(
    onBack: () -> Unit,
    onOpenAiServices: () -> Unit,
    onOpenWebSearch: () -> Unit,
    onOpenGlobalPresets: () -> Unit,
    onOpenUserMasks: () -> Unit,
    onOpenTtsSettings: () -> Unit,
    onOpenVoiceLibrary: () -> Unit,
    onOpenImageGenSettings: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = "AI 与伴读", onBack = onBack) {
        item {
            MoReadSection(title = "AI 服务", icon = Icons.Outlined.AutoAwesome) {
                MoReadRow(icon = Icons.Outlined.Hub, title = "AI 服务", subtitle = aiServiceSummary(state.providers.size, state.models.size), onClick = onOpenAiServices)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Language, title = "网络搜索", subtitle = "搜索服务与兼容接口", onClick = onOpenWebSearch)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Tune, title = "全局预设", subtitle = "按场景注入自定义提示词", onClick = onOpenGlobalPresets)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.PersonOutline, title = "用户面具", subtitle = "管理对话中的用户人设", onClick = onOpenUserMasks)
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Bolt,
                    title = "AI 建议回复",
                    subtitle = "回复后生成快捷建议",
                    checked = state.suggestionRepliesEnabled,
                    onCheckedChange = viewModel::setSuggestionReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.BorderColor,
                    title = "显示 AI 批注",
                    subtitle = "阅读页显示角色划线与评论标记",
                    checked = state.showAiAnnotations,
                    onCheckedChange = viewModel::setShowAiAnnotations
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.ChatBubbleOutline,
                    title = "多气泡回复",
                    subtitle = "让角色像真人一样分条发消息",
                    checked = state.multiBubbleEnabled,
                    onCheckedChange = viewModel::setMultiBubble
                )
            }
        }
        item {
            MoReadSection(title = "语音与图像", icon = Icons.Outlined.GraphicEq) {
                MoReadRow(icon = Icons.Outlined.RecordVoiceOver, title = "朗读引擎与音色", subtitle = "系统 TTS 或云端 AI 语音", onClick = onOpenTtsSettings)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.LibraryMusic, title = "音色库", subtitle = "管理听书与有声书音色", onClick = onOpenVoiceLibrary)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.Brush, title = "生图 API", subtitle = "管理插图与封面生成服务", onClick = onOpenImageGenSettings)
            }
        }
        item {
            // 这一组专收「应用替用户掏钱」的行为：每一项都默认关，副标题写清代价。
            MoReadSection(
                title = "AI 主动行为",
                icon = Icons.Outlined.Bolt,
                footer = "以上都是伴读自己决定发起的调用，会消耗你的 API 额度。默认全部关闭，需要哪项再开哪项。"
            ) {
                MoReadSwitchRow(
                    icon = Icons.Outlined.GraphicEq,
                    title = "自主发语音",
                    subtitle = "角色可选择某句以语音发出，每段都会调用一次 TTS",
                    checked = state.autonomy.voiceRepliesEnabled,
                    onCheckedChange = viewModel::setVoiceReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Brush,
                    title = "自主生图",
                    subtitle = "角色可主动生成插图，单张成本高于一次对话",
                    checked = state.autonomy.imageRepliesEnabled,
                    onCheckedChange = viewModel::setImageReplies
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.BorderColor,
                    title = "随读段评",
                    subtitle = "读完一章后自动留下不超过 2 条批注（每日上限 10 条）",
                    checked = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotations
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.RecordVoiceOver,
                    title = "段评附语音",
                    subtitle = "批注可带一段语音，每日上限 3 条",
                    checked = state.autonomy.proactiveAnnotationVoiceEnabled,
                    enabled = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotationVoice
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Image,
                    title = "段评附插图",
                    subtitle = "批注可带一张插图，每日上限 3 张",
                    checked = state.autonomy.proactiveAnnotationImageEnabled,
                    enabled = state.autonomy.proactiveAnnotationsEnabled,
                    onCheckedChange = viewModel::setProactiveAnnotationImage
                )
            }
        }
        item {
            MoReadSection(title = "伴读记忆", icon = Icons.Outlined.Psychology) {
                MoReadSwitchRow(
                    icon = Icons.Outlined.Bookmarks,
                    title = "长期记忆",
                    subtitle = "让角色记住偏好与约定",
                    checked = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setLongTermMemory
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    title = "跨书记忆",
                    subtitle = "允许回忆其他书籍中的交流",
                    checked = state.memory.crossBookEnabled,
                    enabled = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setCrossBookMemory
                )
                MoReadRowDivider()
                MoReadSwitchRow(
                    icon = Icons.Outlined.Search,
                    title = "跨书对话检索",
                    subtitle = "主动检索其他书籍的相关记忆",
                    checked = state.memory.crossBookChatSearchEnabled,
                    enabled = state.memory.longTermEnabled,
                    onCheckedChange = viewModel::setCrossBookChatSearch
                )
            }
        }
    }
}

@Composable
fun ReadingAppearanceSettingsScreen(
    onBack: () -> Unit,
    onOpenFontLibrary: () -> Unit,
    onOpenImageLibrary: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MoReadSecondaryPage(title = "阅读与外观", onBack = onBack) {
        item {
            MoReadSection(title = "应用外观", icon = Icons.Outlined.Palette) {
                AppearanceCard(
                    appearance = state.appearance,
                    onThemeModeChange = viewModel::setThemeMode,
                    onAccentChange = viewModel::setAccentPreset,
                    onCustomAccent = viewModel::setCustomAccent
                )
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.FontDownload, title = "字体库", subtitle = "阅读字体与语法样式字体", onClick = onOpenFontLibrary)
                MoReadRowDivider()
                MoReadRow(icon = Icons.Outlined.PhotoLibrary, title = "图片库", subtitle = "阅读背景与书籍封面素材", onClick = onOpenImageLibrary)
            }
        }
        item {
            MoReadSection(title = "书架", icon = Icons.Outlined.AutoStories) {
                MoReadBlock(title = "默认布局") {
                    MoReadSegmented(
                        options = ShelfLayout.entries,
                        selected = state.shelfLayout,
                        onSelect = viewModel::setShelfLayout,
                        label = { it.label() }
                    )
                }
            }
        }
    }
}

@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    onOpenBackup: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            if (event is SettingsEvent.ShowMessage) snackbarHostState.showSnackbar(event.message)
        }
    }
    Box(Modifier.fillMaxSize()) {
        MoReadSecondaryPage(title = "存储与数据", onBack = onBack) {
            item {
                MoReadSection(title = "本地存储", icon = Icons.Outlined.Storage) {
                    MoReadRow(
                        icon = Icons.AutoMirrored.Outlined.MenuBook,
                        title = "书籍存储",
                        subtitle = state.bookStorageBytes?.let { "已占用 ${formatBytes(it)}" } ?: "统计中…"
                    )
                    MoReadRowDivider()
                    MoReadRow(
                        icon = Icons.Outlined.Image,
                        title = "封面缓存",
                        subtitle = state.coverCacheBytes?.let { "已占用 ${formatBytes(it)}" } ?: "统计中…",
                        trailing = {
                            MoReadRowAction(
                                text = "清理",
                                onClick = viewModel::clearCoverCache,
                                enabled = (state.coverCacheBytes ?: 0L) > 0L
                            )
                        }
                    )
                    MoReadRowDivider()
                    MoReadRow(icon = Icons.Outlined.CloudSync, title = "数据备份", subtitle = "本地、WebDAV 与自动备份", onClick = onOpenBackup)
                }
            }
        }
        if (state.isWorking) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter).padding(20.dp))
    }
}

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    onOpenApiLog: () -> Unit
) {
    MoReadSecondaryPage(title = "关于与诊断", onBack = onBack) {
        item {
            MoReadSection(title = "应用", icon = Icons.Outlined.Info) {
                AppUpdateCard()
                MoReadRowDivider()
                MoReadRow(
                    icon = Icons.Outlined.BugReport,
                    title = "API 调用日志",
                    subtitle = "查看请求地址、状态与耗时",
                    onClick = onOpenApiLog
                )
                MoReadRowDivider()
                AboutCard()
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

    // 外层素面卡由 MoReadSection 提供，这里只出内容。
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
            Text("主题模式", style = MaterialTheme.typography.titleSmall)
            MoReadSegmented(
                options = ThemeMode.entries,
                selected = appearance.themeMode,
                onSelect = onThemeModeChange,
                label = { it.label() }
            )

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
                    "太深或太浅的颜色会自动调整，保证在当前底色上看得清。",
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
    // 外层素面卡由 MoReadSection 提供，这里只出内容。
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
                        "版本 ${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.VERSION_CODE}）",
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
                    "本项目以 GPL-3.0 许可开源，基于 Readium Kotlin Toolkit、Legado 章节规则等开源成果构建，完整清单见开源仓库。",
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
