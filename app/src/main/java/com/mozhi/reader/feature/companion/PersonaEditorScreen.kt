package com.mozhi.reader.feature.companion

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.ui.components.MoReadFieldGroup
import com.mozhi.reader.ui.components.MoReadPill
import com.mozhi.reader.ui.components.MoReadRow
import com.mozhi.reader.ui.components.MoReadRowDivider
import com.mozhi.reader.ui.components.MoReadSecondaryPage
import com.mozhi.reader.ui.components.MoReadSection
import com.mozhi.reader.ui.components.MoReadDisclosureRow
import com.mozhi.reader.ui.components.MoReadSegmented
import com.mozhi.reader.ui.components.MoReadSwitchRow
import com.mozhi.reader.ui.components.MoReadTextField
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.theme.MoReadRadius
import com.mozhi.reader.ui.theme.fieldContainerColor

/**
 * 角色编辑二级页（personaId = 0 为新建）。
 * 支持相册自定义头像与 SillyTavern 角色卡导入（PNG 内嵌 / JSON）。
 *
 * 版式：一个分区 = 一张 [MoReadSection] 卡，抬头是 [MoReadDisclosureRow]，展开的内容
 * **就在同一张卡里**长出来。改造前抬头是裸行浮在渐变背景上、内容却是另一张玻璃卡，
 * 中间还隔 14dp —— 抬头看着不属于它的内容，这是「二级页和整体不协调」的主要来源。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PersonaEditorScreen(
    onBack: () -> Unit,
    onOpenMemory: (personaId: Long) -> Unit = {},
    viewModel: PersonaEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var personalityExpanded by rememberSaveable(state.isNew) { mutableStateOf(state.isNew) }
    var dialogsExpanded by rememberSaveable { mutableStateOf(false) }
    var worldBookExpanded by rememberSaveable { mutableStateOf(false) }
    var appearanceExpanded by rememberSaveable { mutableStateOf(false) }
    var voiceExpanded by rememberSaveable { mutableStateOf(false) }
    var toolsPageVisible by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = toolsPageVisible) { toolsPageVisible = false }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                PersonaEditorEvent.Saved -> onBack()
                PersonaEditorEvent.Deleted -> onBack()
                is PersonaEditorEvent.Message -> snackbarHostState.showSnackbar(event.text)
            }
        }
    }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> viewModel.pickAvatar(uri) }
    // GET_CONTENT：允许 OEM 文件管理（vivo 等，带搜索）接管；格式校验在解析器里做。
    val cardPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> viewModel.importCard(uri) }

    if (state.loading) {
        com.mozhi.reader.ui.components.MoReadBackdrop {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        return
    }
    if (toolsPageVisible) {
        ToolPermissionsPage(
            state = state,
            onBack = { toolsPageVisible = false },
            onToggle = viewModel::toggleTool
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        MoReadSecondaryPage(
            title = if (state.isNew) "新建角色" else "编辑角色",
            subtitle = state.name.takeIf(String::isNotBlank),
            onBack = onBack,
            modifier = Modifier.imePadding(),
            actions = {
                IconButton(onClick = viewModel::save) {
                    Icon(
                        Icons.Outlined.Check,
                        contentDescription = "保存",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        ) {
            item(key = "identity") {
                IdentitySection(
                    state = state,
                    onPickAvatar = {
                        avatarPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onImportCard = { cardPicker.launch("*/*") },
                    onNameChange = viewModel::setName,
                    onSubtitleChange = viewModel::setSubtitle,
                    onRoleplayChange = viewModel::setRoleplay
                )
            }

            item(key = "persona-text") {
                MoReadSection {
                    MoReadDisclosureRow(
                        title = "人设",
                        summary = listOfNotNull(
                            state.personality.takeIf(String::isNotBlank)?.let { "${it.length} 字" },
                            state.speakingStyle.takeIf(String::isNotBlank)?.let { "含说话风格" },
                            state.greeting.takeIf(String::isNotBlank)?.let { "含开场白" }
                        ).joinToString(" · ").ifBlank { "尚未填写" },
                        expanded = personalityExpanded,
                        onToggle = { personalityExpanded = !personalityExpanded }
                    ) {
                        MoReadTextField(
                            value = state.personality,
                            onValueChange = viewModel::setPersonality,
                            label = "人设描述",
                            placeholder = "身份、世界观、性格",
                            singleLine = false,
                            minLines = 5
                        )
                        MoReadTextField(
                            value = state.speakingStyle,
                            onValueChange = viewModel::setSpeakingStyle,
                            label = "说话风格",
                            singleLine = false,
                            minLines = 2
                        )
                        MoReadTextField(
                            value = state.greeting,
                            onValueChange = viewModel::setGreeting,
                            label = "开场白",
                            singleLine = false,
                            minLines = 2
                        )
                    }
                }
            }

            item(key = "dialogs") {
                MoReadSection {
                    MoReadDisclosureRow(
                        title = "示例对话",
                        summary = if (state.dialogs.isEmpty()) "尚未添加" else "${state.dialogs.size} 组",
                        expanded = dialogsExpanded,
                        onToggle = { dialogsExpanded = !dialogsExpanded },
                        // 条目多了就别做展开动画：动画每帧重量一遍整段 body，必掉帧。
                        animated = state.dialogs.size <= INLINE_ANIMATION_LIMIT
                    ) {
                        state.dialogs.forEachIndexed { index, dialog ->
                            DialogPairBlock(
                                index = index,
                                user = dialog.user,
                                assistant = dialog.assistant,
                                personaName = state.name,
                                onChange = { user, assistant ->
                                    viewModel.updateDialog(index, user, assistant)
                                },
                                onRemove = { viewModel.removeDialog(index) }
                            )
                        }
                        AddInlineButton(label = "添加一组示例", onClick = viewModel::addDialog)
                    }
                }
            }

            item(key = "world-book") {
                MoReadSection {
                    MoReadDisclosureRow(
                        title = "世界书",
                        summary = "${state.worldBook.size} 条 · " +
                            if (state.worldBookEnabled) "已启用" else "已关闭",
                        expanded = worldBookExpanded,
                        onToggle = { worldBookExpanded = !worldBookExpanded },
                        // ST 角色卡的世界书动辄上百条，展开动画会逐帧重量整段，必掉帧。
                        animated = state.worldBook.size <= INLINE_ANIMATION_LIMIT,
                        trailing = {
                            Switch(
                                checked = state.worldBookEnabled,
                                onCheckedChange = viewModel::setWorldBookEnabled,
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    ) {
                        state.worldBook.forEachIndexed { index, entry ->
                            LoreEntryBlock(
                                index = index,
                                entry = entry,
                                masterEnabled = state.worldBookEnabled,
                                onChange = { updated -> viewModel.updateLoreEntry(index, updated) },
                                onRemove = { viewModel.removeLoreEntry(index) }
                            )
                        }
                        AddInlineButton(label = "添加设定条目", onClick = viewModel::addLoreEntry)
                    }
                }
            }

            item(key = "memory") {
                MoReadSection {
                    MoReadSwitchRow(
                        icon = Icons.Outlined.Bookmarks,
                        title = "长期记忆",
                        subtitle = if (state.memoryEnabled) {
                            "对话沉淀成记忆，下次见面它还记得你"
                        } else {
                            "已关闭：只做当次问答，已有记忆保留不删"
                        },
                        checked = state.memoryEnabled,
                        onCheckedChange = viewModel::setMemoryEnabled
                    )
                    MoReadRowDivider()
                    if (state.isNew) {
                        MoReadRow(
                            icon = Icons.Outlined.Psychology,
                            title = "管理记忆与画像",
                            subtitle = "保存角色后即可查看和整理它的记忆"
                        )
                    } else {
                        MoReadRow(
                            icon = Icons.Outlined.Psychology,
                            title = "管理记忆与画像",
                            subtitle = "逐条查看、编辑与删除",
                            onClick = { onOpenMemory(state.personaId) },
                            trailing = {
                                MoReadPill(text = "${state.memoryCount} 条")
                                Icon(
                                    Icons.AutoMirrored.Outlined.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp).size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            item(key = "appearance") {
                MoReadSection {
                    MoReadDisclosureRow(
                        title = "聊天外观",
                        summary = if (state.appearance.isDefault) {
                            "跟随阅读主题"
                        } else {
                            listOfNotNull(
                                state.appearance.shape.label,
                                state.appearance.backgroundImageId?.let { "有背景图" },
                                state.appearance.fontId?.let { "自定义字体" }
                            ).joinToString(" · ")
                        },
                        expanded = appearanceExpanded,
                        onToggle = { appearanceExpanded = !appearanceExpanded }
                    ) {
                        AppearanceCard(
                            appearance = state.appearance,
                            personaName = state.name,
                            images = state.imageLibrary,
                            fonts = state.fontLibrary,
                            onChange = viewModel::updateAppearance,
                            onReset = viewModel::resetAppearance
                        )
                    }
                }
            }

            item(key = "voice") {
                MoReadSection {
                    MoReadDisclosureRow(
                        title = "声音",
                        summary = state.voices.firstOrNull { it.voiceId == state.voiceId }?.displayName
                            ?: "未绑定音色",
                        expanded = voiceExpanded,
                        onToggle = { voiceExpanded = !voiceExpanded }
                    ) {
                        PersonaVoiceBlock(
                            state = state,
                            onSearch = viewModel::setVoiceSearch,
                            onGender = viewModel::setVoiceGender,
                            onSelect = viewModel::setVoice,
                            onEmotion = viewModel::setVoiceEmotion,
                            onPreview = viewModel::previewVoice
                        )
                    }
                }
            }

            item(key = "tools") {
                MoReadSection {
                    MoReadRow(
                        icon = Icons.Outlined.Tune,
                        title = "工具权限",
                        subtitle = "${state.enabledTools.count { tool ->
                            PersonaToolOptions.any { it.first == tool }
                        }}/${PersonaToolOptions.size} 项写入能力已启用",
                        onClick = { toolsPageVisible = true }
                    )
                }
            }

            if (!state.isNew) {
                item(key = "danger") {
                    TextButton(
                        onClick = { confirmDelete = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("删除角色", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 18.dp)
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除「${state.name}」？") },
            text = { Text("角色卡会被移除；它写下的批注、笔记与会话会保留，显示为「已删除角色」。") },
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
}

/**
 * 身份区：头像、名称、副标题、类型。
 *
 * 改造前是一张居中大卡（92dp 头像居中 + 两个描边输入框 + 两颗 chip），占掉大半屏却只
 * 承载三个字段。改成左头像、右字段的横排后，同样的信息量只用一半高度，
 * 「导入角色卡」也从居中的文字按钮变成一条明确的行。
 */
@Composable
private fun IdentitySection(
    state: PersonaEditorState,
    onPickAvatar: () -> Unit,
    onImportCard: () -> Unit,
    onNameChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onRoleplayChange: (Boolean) -> Unit
) {
    MoReadSection {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    PersonaAvatarImage(
                        name = state.name,
                        avatarPath = state.avatarPath,
                        modifier = Modifier.size(72.dp)
                    )
                    Surface(
                        onClick = onPickAvatar,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                    ) {
                        Icon(
                            Icons.Outlined.PhotoCamera,
                            contentDescription = "更换头像",
                            modifier = Modifier.padding(5.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MoReadTextField(
                        value = state.name,
                        onValueChange = onNameChange,
                        placeholder = "角色名称"
                    )
                    if (state.isBuiltIn) {
                        MoReadPill(text = "内置模板")
                    }
                }
            }
            MoReadTextField(
                value = state.subtitle,
                onValueChange = onSubtitleChange,
                label = "副标题",
                placeholder = "如「共情写作者 · 情绪共读」"
            )
            MoReadSegmented(
                options = listOf(true, false),
                selected = state.isRoleplay,
                onSelect = onRoleplayChange,
                label = { if (it) "角色扮演" else "纯工具助手" }
            )
        }
        MoReadRowDivider(inset = 0.dp)
        MoReadRow(
            icon = Icons.Outlined.FileOpen,
            title = "导入 SillyTavern 角色卡",
            subtitle = "支持 PNG 内嵌与 JSON",
            onClick = onImportCard
        )
    }
}

/** 展开区里的一个次级块：填充底、圆角，与 [MoReadTextField] 同一层材质。 */
@Composable
private fun InlineBlock(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) = Column(
    modifier = modifier
        .fillMaxWidth()
        .clip(MoReadRadius.RowShape)
        .background(fieldContainerColor().copy(alpha = 0.55f))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    content = content
)

/** 「＋ 添加」行：卡内的轻动作，不再用整宽虚线胶囊（那个在展开区里太抢眼）。 */
@Composable
private fun AddInlineButton(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MoReadRadius.RowShape)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "＋ $label",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun DialogPairBlock(
    index: Int,
    user: String,
    assistant: String,
    personaName: String,
    onChange: (String, String) -> Unit,
    onRemove: () -> Unit
) {
    InlineBlock {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "示例 ${index + 1}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = "删除示例",
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        MoReadTextField(
            value = user,
            onValueChange = { onChange(it, assistant) },
            label = "用户",
            singleLine = false
        )
        MoReadTextField(
            value = assistant,
            onValueChange = { onChange(user, it) },
            label = personaName.ifBlank { "角色" },
            singleLine = false
        )
    }
}

/**
 * 世界书条目：启用开关 + 注入方式（常驻 / 关键词触发 + 触发词）+ 名称与正文。
 * 关掉的条目仅保存不注入；总开关关闭时整块降透明度提示不生效。
 */
@Composable
private fun LoreEntryBlock(
    index: Int,
    entry: PersonaLoreEntry,
    masterEnabled: Boolean,
    onChange: (PersonaLoreEntry) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by rememberSaveable(index) { mutableStateOf(false) }
    InlineBlock(modifier = Modifier.alpha(if (masterEnabled) 1f else 0.55f)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifBlank { "条目 ${index + 1}" },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (entry.constant) "常驻注入" else {
                        "关键词：${entry.keys.take(3).joinToString("、").ifBlank { "未设置" }}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = { onChange(entry.copy(enabled = it)) },
                modifier = Modifier
                    .padding(start = 5.dp)
                    .scale(0.76f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起条目" else "展开条目",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        if (!expanded) {
            Text(
                text = entry.content.ifBlank { "尚未填写设定内容" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MoReadSegmented(
                    options = listOf(true, false),
                    selected = entry.constant,
                    onSelect = { onChange(entry.copy(constant = it)) },
                    label = { if (it) "常驻注入" else "关键词触发" },
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "删除条目",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (!entry.constant) {
                MoReadTextField(
                    value = entry.keys.joinToString("、"),
                    onValueChange = { text ->
                        onChange(
                            entry.copy(
                                keys = text.split('、', ',', '，')
                                    .map(String::trim)
                                    .filter(String::isNotEmpty)
                            )
                        )
                    },
                    label = "触发词",
                    supporting = "顿号分隔，命中才注入"
                )
            }
            MoReadTextField(
                value = entry.name,
                onValueChange = { onChange(entry.copy(name = it)) },
                label = "条目名（可选）"
            )
            MoReadTextField(
                value = entry.content,
                onValueChange = { onChange(entry.copy(content = it)) },
                label = "设定内容",
                singleLine = false,
                minLines = 2
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaVoiceBlock(
    state: PersonaEditorState,
    onSearch: (String) -> Unit,
    onGender: (String?) -> Unit,
    onSelect: (String) -> Unit,
    onEmotion: (String) -> Unit,
    onPreview: (TtsVoiceEntity) -> Unit
) {
    val voices = state.voices.filter { voice ->
        (state.voiceGender == null || voice.gender == state.voiceGender) &&
            (state.voiceSearch.isBlank() || listOf(voice.displayName, voice.voiceId, voice.tags)
                .any { it.contains(state.voiceSearch.trim(), ignoreCase = true) })
    }
    MoReadFieldGroup {
        Text(
            "绑定后，开启「自主发语音」时角色可把标记为语音的句子合成为声音。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        MoReadTextField(
            value = state.voiceSearch,
            onValueChange = onSearch,
            placeholder = "搜索音色",
            leadingIcon = Icons.Outlined.Search
        )
        MoReadSegmented(
            options = listOf<String?>(null, "FEMALE", "MALE"),
            selected = state.voiceGender,
            onSelect = onGender,
            label = { value ->
                when (value) {
                    null -> "全部"
                    "FEMALE" -> "女声"
                    else -> "男声"
                }
            }
        )
        InlineBlock {
            VoiceChoiceRow(
                title = "不绑定音色",
                subtitle = "由听书设置里的默认音色接管",
                selected = state.voiceId.isBlank(),
                onClick = { onSelect("") }
            )
            voices.take(12).forEach { voice ->
                VoiceChoiceRow(
                    title = voice.displayName,
                    subtitle = listOf(voice.gender, voice.tags)
                        .filter(String::isNotBlank)
                        .joinToString(" · "),
                    selected = voice.voiceId == state.voiceId,
                    onClick = { onSelect(voice.voiceId) },
                    trailing = {
                        IconButton(onClick = { onPreview(voice) }, modifier = Modifier.size(32.dp)) {
                            if (state.previewingVoiceId == voice.id) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Outlined.RecordVoiceOver,
                                    contentDescription = "试听",
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }
                    }
                )
            }
            if (voices.isEmpty() && state.voiceSearch.isNotBlank()) {
                Text(
                    "没有匹配的音色。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        MoReadTextField(
            value = state.voiceEmotion,
            onValueChange = onEmotion,
            label = "语音情绪或风格",
            supporting = "例如：温柔、克制、兴奋；留空使用服务商默认"
        )
    }
}

@Composable
private fun VoiceChoiceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MoReadRadius.FieldShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Check,
            contentDescription = null,
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                androidx.compose.ui.graphics.Color.Transparent
            },
            modifier = Modifier.size(16.dp)
        )
        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ToolPermissionsPage(
    state: PersonaEditorState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit
) {
    MoReadSecondaryPage(
        title = "工具权限",
        subtitle = "控制这个角色可以写入哪些内容",
        onBack = onBack
    ) {
        item {
            MoReadSection(
                footer = "查看目录、原文、已有划线和笔记属于基础阅读能力，无需单独开启。" +
                    "修改会在返回角色页并保存后生效；关闭写入能力不会删除已有内容。"
            ) {
                PersonaToolOptions.forEachIndexed { index, (tool, label) ->
                    if (index > 0) MoReadRowDivider()
                    MoReadSwitchRow(
                        icon = toolIcon(tool),
                        title = label,
                        subtitle = toolDescription(tool),
                        checked = tool in state.enabledTools,
                        onCheckedChange = { onToggle(tool) }
                    )
                }
            }
        }
    }
}

private fun toolDescription(tool: String): String = when (tool) {
    "add_annotation" -> "允许角色在原文上留下批注"
    "write_note" -> "允许角色新建或更新自己的读书笔记"
    "save_plot_summary" -> "允许角色维护本书的滚动剧情梗概"
    "generate_image" -> "允许角色生成图片并保存到插图廊"
    "synthesize_speech" -> "允许角色生成并缓存语音内容"
    "create_reading_plan" -> "预留能力，阅读计划上线后生效"
    else -> "允许角色使用此写入能力"
}

private fun toolIcon(tool: String): ImageVector = when (tool) {
    "get_reading_progress" -> Icons.AutoMirrored.Outlined.MenuBook
    "search_book" -> Icons.Outlined.Search
    "read_book_section" -> Icons.AutoMirrored.Outlined.MenuBook
    "recall_memory" -> Icons.Outlined.Psychology
    "add_annotation" -> Icons.Outlined.EditNote
    "write_note" -> Icons.Outlined.Description
    "save_plot_summary" -> Icons.Outlined.Description
    "generate_image" -> Icons.Outlined.Image
    "synthesize_speech" -> Icons.Outlined.RecordVoiceOver
    "create_reading_plan" -> Icons.Outlined.CalendarMonth
    else -> Icons.Outlined.Check
}

/** 展开区超过这么多条就不做展开动画，直接出现。 */
private const val INLINE_ANIMATION_LIMIT = 12
