package com.mozhi.reader.feature.companion

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.graphics.vector.ImageVector
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.ui.components.PersonaAvatarImage
import com.mozhi.reader.ui.components.DashedAddRow
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.SectionLabel
import com.mozhi.reader.ui.theme.MoReadTokens

/**
 * 角色编辑二级页（personaId = 0 为新建）。
 * 支持相册自定义头像与 SillyTavern 角色卡导入（PNG 内嵌 / JSON）。
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

    MoReadBackdrop {
        if (state.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@MoReadBackdrop
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                // adjustResize + 边到边下键盘不再平移窗口，滚动容器自己让位。
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding(),
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    EditorTopBar(
                        title = if (state.isNew) "新建角色" else "编辑角色",
                        onBack = onBack,
                        onSave = viewModel::save
                    )
                }
                item {
                    HeroCard(
                        state = state,
                        onPickAvatar = {
                            avatarPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        },
                        onImportCard = { cardPicker.launch("*/*") },
                        onNameChange = viewModel::setName,
                        onSubtitleChange = viewModel::setSubtitle,
                        onRoleplayChange = viewModel::setRoleplay
                    )
                }
                item {
                    ExpandableEditorSection(
                        title = "人设",
                        summary = listOfNotNull(
                            state.personality.takeIf(String::isNotBlank)?.let { "${it.length} 字" },
                            state.speakingStyle.takeIf(String::isNotBlank)?.let { "含说话风格" },
                            state.greeting.takeIf(String::isNotBlank)?.let { "含开场白" }
                        ).joinToString(" · ").ifBlank { "尚未填写" },
                        expanded = personalityExpanded,
                        onToggle = { personalityExpanded = !personalityExpanded }
                    )
                }
                if (personalityExpanded) {
                    item {
                        PersonaTextCard(
                            state = state,
                            onPersonalityChange = viewModel::setPersonality,
                            onSpeakingStyleChange = viewModel::setSpeakingStyle,
                            onGreetingChange = viewModel::setGreeting
                        )
                    }
                }
                item {
                    ExpandableEditorSection(
                        title = "示例对话",
                        summary = "${state.dialogs.size} 组",
                        expanded = dialogsExpanded,
                        onToggle = { dialogsExpanded = !dialogsExpanded }
                    )
                }
                if (dialogsExpanded) {
                    itemsIndexed(state.dialogs) { index, dialog ->
                        DialogPairCard(
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
                    item {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            DashedAddRow(label = "添加一组示例", onClick = viewModel::addDialog)
                        }
                    }
                }
                item {
                    ExpandableEditorSection(
                        title = "世界书",
                        summary = "${state.worldBook.size} 条 · " +
                            if (state.worldBookEnabled) "已启用" else "已关闭",
                        expanded = worldBookExpanded,
                        onToggle = { worldBookExpanded = !worldBookExpanded },
                        trailing = {
                            Switch(
                                checked = state.worldBookEnabled,
                                onCheckedChange = viewModel::setWorldBookEnabled,
                                modifier = Modifier.scale(0.8f)
                            )
                        }
                    )
                }
                if (worldBookExpanded) {
                    itemsIndexed(state.worldBook) { index, entry ->
                        LoreEntryCard(
                            index = index,
                            entry = entry,
                            masterEnabled = state.worldBookEnabled,
                            onChange = { updated -> viewModel.updateLoreEntry(index, updated) },
                            onRemove = { viewModel.removeLoreEntry(index) }
                        )
                    }
                    item {
                        Box(Modifier.padding(horizontal = 20.dp)) {
                            DashedAddRow(label = "添加设定条目", onClick = viewModel::addLoreEntry)
                        }
                    }
                }
                item {
                    MemoryCard(
                        enabled = state.memoryEnabled,
                        memoryCount = state.memoryCount,
                        canManage = !state.isNew,
                        onEnabledChange = viewModel::setMemoryEnabled,
                        onManage = { onOpenMemory(state.personaId) }
                    )
                }
                item {
                    ExpandableEditorSection(
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
                    )
                }
                if (appearanceExpanded) {
                    item {
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
                item {
                    ExpandableEditorSection(
                        title = "声音",
                        summary = state.voices.firstOrNull { it.voiceId == state.voiceId }?.displayName
                            ?: "未绑定音色",
                        expanded = voiceExpanded,
                        onToggle = { voiceExpanded = !voiceExpanded }
                    )
                }
                if (voiceExpanded) {
                    item {
                        PersonaVoiceCard(
                            state = state,
                            onSearch = viewModel::setVoiceSearch,
                            onGender = viewModel::setVoiceGender,
                            onSelect = viewModel::setVoice,
                            onEmotion = viewModel::setVoiceEmotion,
                            onPreview = viewModel::previewVoice
                        )
                    }
                }
                item { SectionLabel(title = "工具权限") }
                item { ToolsCard(state = state, onToggle = viewModel::toggleTool) }
                if (!state.isNew) {
                    item {
                        TextButton(
                            onClick = { confirmDelete = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonaVoiceCard(
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
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "绑定后，开启“自主发语音”时角色可把标记为语音的句子合成为声音。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = state.voiceSearch,
                onValueChange = onSearch,
                label = { Text("搜索音色") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "全部", "FEMALE" to "女声", "MALE" to "男声").forEach { (value, label) ->
                    FilterChip(
                        selected = state.voiceGender == value,
                        onClick = { onGender(value) },
                        label = { Text(label) }
                    )
                }
            }
            Surface(
                onClick = { onSelect("") },
                shape = RoundedCornerShape(14.dp),
                color = if (state.voiceId.isBlank()) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text("不绑定音色", modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
            voices.take(12).forEach { voice ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(voice.voiceId) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            voice.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (voice.voiceId == state.voiceId) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            listOf(voice.gender, voice.tags).filter(String::isNotBlank).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onPreview(voice) }) {
                        if (state.previewingVoiceId == voice.id) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Outlined.RecordVoiceOver, contentDescription = "试听")
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.voiceEmotion,
                onValueChange = onEmotion,
                label = { Text("语音情绪或风格") },
                supportingText = { Text("例如：温柔、克制、兴奋；留空使用服务商默认") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun ExpandableEditorSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        trailing?.invoke()
        Icon(
            if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
            contentDescription = if (expanded) "收起$title" else "展开$title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
private fun EditorTopBar(title: String, onBack: () -> Unit, onSave: () -> Unit) {
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
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        FrostedSurface(shape = CircleShape, shadowElevation = 6.dp) {
            IconButton(onClick = onSave) {
                Icon(
                    Icons.Outlined.Check,
                    contentDescription = "保存",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun HeroCard(
    state: PersonaEditorState,
    onPickAvatar: () -> Unit,
    onImportCard: () -> Unit,
    onNameChange: (String) -> Unit,
    onSubtitleChange: (String) -> Unit,
    onRoleplayChange: (Boolean) -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(28.dp),
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box {
                PersonaAvatarImage(
                    name = state.name,
                    avatarPath = state.avatarPath,
                    modifier = Modifier.size(92.dp)
                )
                Surface(
                    onClick = onPickAvatar,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(30.dp)
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = "更换头像",
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
            if (state.isBuiltIn) {
                Surface(
                    shape = MoReadTokens.CapsuleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text(
                        "内置模板",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
            TextButton(onClick = onImportCard, modifier = Modifier.padding(top = 4.dp)) {
                Icon(Icons.Outlined.FileOpen, contentDescription = null, Modifier.size(18.dp))
                Text("导入 SillyTavern 角色卡", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("名称") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
            OutlinedTextField(
                value = state.subtitle,
                onValueChange = onSubtitleChange,
                label = { Text("副标题（如「共情写作者 · 情绪共读」）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.isRoleplay,
                    onClick = { onRoleplayChange(true) },
                    label = { Text("角色扮演") }
                )
                FilterChip(
                    selected = !state.isRoleplay,
                    onClick = { onRoleplayChange(false) },
                    label = { Text("纯工具助手") }
                )
            }
        }
    }
}

@Composable
private fun PersonaTextCard(
    state: PersonaEditorState,
    onPersonalityChange: (String) -> Unit,
    onSpeakingStyleChange: (String) -> Unit,
    onGreetingChange: (String) -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = state.personality,
                onValueChange = onPersonalityChange,
                label = { Text("人设描述（身份、世界观、性格）") },
                minLines = 5,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = state.speakingStyle,
                onValueChange = onSpeakingStyleChange,
                label = { Text("说话风格") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
            OutlinedTextField(
                value = state.greeting,
                onValueChange = onGreetingChange,
                label = { Text("开场白") },
                minLines = 2,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun DialogPairCard(
    index: Int,
    user: String,
    assistant: String,
    personaName: String,
    onChange: (String, String) -> Unit,
    onRemove: () -> Unit
) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
            OutlinedTextField(
                value = user,
                onValueChange = { onChange(it, assistant) },
                label = { Text("用户") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = assistant,
                onValueChange = { onChange(user, it) },
                label = { Text(personaName.ifBlank { "角色" }) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsCard(state: PersonaEditorState, onToggle: (String) -> Unit) {
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "允许该角色使用的工具",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PersonaToolOptions.forEach { (tool, label) ->
                    FilterChip(
                        selected = tool in state.enabledTools,
                        onClick = { onToggle(tool) },
                        leadingIcon = {
                            Icon(
                                toolIcon(tool),
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    }
}

/**
 * 世界书条目卡：启用开关 + 注入方式（常驻 / 关键词触发 + 触发词）+ 名称与正文。
 * 关掉的条目仅保存不注入；总开关关闭时整卡降透明度提示不生效。
 */
@Composable
private fun LoreEntryCard(
    index: Int,
    entry: PersonaLoreEntry,
    masterEnabled: Boolean,
    onChange: (PersonaLoreEntry) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by rememberSaveable(index) { mutableStateOf(false) }
    FrostedSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .alpha(if (masterEnabled) 1f else 0.55f),
        shape = RoundedCornerShape(20.dp),
        shadowElevation = 3.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
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
                Text(
                    text = if (entry.enabled) "启用" else "已关",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.enabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
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
                    maxLines = 2,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = entry.constant,
                        onClick = { onChange(entry.copy(constant = true)) },
                        label = { Text("常驻注入") }
                    )
                    FilterChip(
                        selected = !entry.constant,
                        onClick = { onChange(entry.copy(constant = false)) },
                        label = { Text("关键词触发") }
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
                    OutlinedTextField(
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
                        label = { Text("触发词（顿号分隔，命中才注入）") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )
                }
                OutlinedTextField(
                    value = entry.name,
                    onValueChange = { onChange(entry.copy(name = it)) },
                    label = { Text("条目名（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = entry.content,
                    onValueChange = { onChange(entry.copy(content = it)) },
                    label = { Text("设定内容") },
                    minLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        }
    }
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
