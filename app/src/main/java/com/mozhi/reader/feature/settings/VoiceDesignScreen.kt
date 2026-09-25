package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.ui.components.*
import kotlinx.coroutines.flow.collectLatest

data class VoiceDesignUiActions(
    val name: (String) -> Unit = {}, val description: (String) -> Unit = {},
    val gender: (String) -> Unit = {}, val language: (String) -> Unit = {}, val persona: (Long?) -> Unit = {},
    val send: (String) -> Unit = {}, val generate: () -> Unit = {}, val fetchPreview: () -> Unit = {},
    val play: () -> Unit = {}, val save: () -> Unit = {}, val stop: () -> Unit = {}
)

@Composable
fun VoiceDesignDialog(viewModel: VoiceDesignViewModel, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    fun exit() { viewModel.discard(); onDismiss() }
    fun requestExit() {
        if (state.work == VoiceDesignWork.SAVING) return
        if (state.candidate != null || state.busy || state.chats.isNotEmpty()) confirmExit = true else exit()
    }
    LaunchedEffect(state.savedId) { if (state.savedId != null) { viewModel.discard(); onSaved() } }
    MoReadPageDialog(onDismissRequest = ::requestExit) {
        VoiceDesignContent(state, ::requestExit, VoiceDesignUiActions(
            viewModel::name, viewModel::description, viewModel::gender, viewModel::language, viewModel::persona,
            viewModel::send, viewModel::generate, viewModel::fetchPreview, viewModel::togglePreview,
            viewModel::save, viewModel::stopAgent
        ))
    }
    if (confirmExit) AlertDialog(
        onDismissRequest = { confirmExit = false }, title = { Text("退出音色设计？") },
        text = { Text("未入库的试听与本次对话会被丢弃。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; exit() }) { Text("放弃并退出") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续调整") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceDesignContent(state: VoiceDesignState, onBack: () -> Unit, actions: VoiceDesignUiActions) {
    var agentMode by rememberSaveable { mutableStateOf(true) }
    var input by rememberSaveable { mutableStateOf("") }
    var pickPersona by remember { mutableStateOf(false) }
    var help by remember { mutableStateOf(false) }
    val agentList = rememberLazyListState()
    val manualList = rememberLazyListState()
    var followConversation by remember { mutableStateOf(true) }
    var followingProgrammatically by remember { mutableStateOf(false) }
    LaunchedEffect(agentList) {
        snapshotFlow { agentList.isScrollInProgress to (agentList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collectLatest { (moving, last) ->
                if (moving && !followingProgrammatically) followConversation = last >= agentList.layoutInfo.totalItemsCount - 2
            }
    }
    LaunchedEffect(agentMode, state.chats.lastOrNull()?.text, state.candidate?.voiceId, state.activity, state.busy) {
        if (agentMode && followConversation && state.chats.isNotEmpty() && !agentList.isScrollInProgress) {
            followingProgrammatically = true
            try { agentList.scrollToItem((agentList.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
            finally { followingProgrammatically = false }
        }
    }
    val selectedPersona = state.personas.firstOrNull { it.id == state.personaId }
    MoReadSecondaryPage(
        title = "设计音色", subtitle = "描述、试听，再留下喜欢的声音", onBack = onBack,
        modifier = Modifier.imePadding(),
        listState = if (agentMode) agentList else manualList,
        actions = { IconButton(onClick = { help = true }) { Icon(Icons.Outlined.Info, "音色设计说明") } },
        bottomBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars.exclude(WindowInsets.ime))
                    .padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (agentMode) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = input, onValueChange = { input = it.take(2000) },
                            placeholder = { Text("说说你的想法，或继续调整…") }, maxLines = 4,
                            modifier = Modifier.weight(1f), enabled = !state.busy
                        )
                        FilledIconButton(onClick = {
                            if (state.busy) actions.stop() else if (input.isNotBlank()) { followConversation = true; actions.send(input); input = "" }
                        }, enabled = state.work != VoiceDesignWork.SAVING && (state.busy || input.isNotBlank()), shape = CircleShape) {
                            Icon(if (state.busy) MoReadIcons.StopSquare else MoReadIcons.Send,
                                if (state.busy) "停止助手" else "发送需求",
                                Modifier.size(if (state.busy) 13.dp else 20.dp))
                        }
                    } else {
                        Button(onClick = if (state.busy) actions.stop else actions.generate,
                            enabled = state.work != VoiceDesignWork.SAVING && (state.busy || state.name.isNotBlank() && state.description.isNotBlank()),
                            colors = if (state.candidate != null && !state.changed) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(if (state.busy) Icons.Outlined.Stop else Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(if (state.busy) "停止生成" else if (state.candidate == null) "生成试听" else "重新生成试听")
                        }
                    }
                    if (state.candidate != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalIconButton(onClick = if (state.candidate.previewPath == null) actions.fetchPreview else actions.play,
                            enabled = !state.busy, shape = CircleShape) {
                            Icon(if (state.candidate.previewPath == null) Icons.Outlined.Refresh else if (state.playing) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                                if (state.candidate.previewPath == null) "获取试听" else if (state.playing) "停止试听" else "播放试听")
                        }
                        Button(onClick = actions.save, enabled = state.canSave, modifier = Modifier.weight(1f)) {
                            Text(if (state.work == VoiceDesignWork.SAVING) "正在入库…" else "满意，入库")
                        }
                    }
                }
            }
        }
    ) {
        item("mode") {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "AI 对话", false to "手动描述").forEachIndexed { index, (mode, label) ->
                    SegmentedButton(selected = agentMode == mode, onClick = { agentMode = mode }, enabled = !state.busy,
                        shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
                }
            }
        }
        if (agentMode) {
            item("persona") {
                MoReadSection {
                    MoReadRow(title = selectedPersona?.name ?: "参考角色（可选）", subtitle = selectedPersona?.subtitle?.ifBlank { "参考角色资料设计声音" } ?: "也可以直接在对话中说出角色名字",
                        onClick = { if (!state.busy) pickPersona = true },
                        trailing = { PersonaAvatarImage(selectedPersona?.name ?: "声", selectedPersona?.avatarPath, Modifier.size(40.dp)) })
                }
            }
            if (state.chats.isEmpty()) item("welcome") {
                Text("例如：给苏晚设计一个温柔、略带沙哑的普通话女声。\n\n助手会按需查找角色、调整设定并生成试听；你也可以随时切到手动编辑。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.chats.filter { it.text.isNotBlank() }, key = { "chat-${it.id}" }) { chat ->
                Surface(shape = MaterialTheme.shapes.large,
                    color = if (chat.role == ChatRole.USER) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(if (chat.role == ChatRole.USER) "你" else "音色助手", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(chat.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        } else {
            item("name") { OutlinedTextField(state.name, actions.name, label = { Text("音色名称") }, singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) }
            item("description") {
                OutlinedTextField(state.description, actions.description, label = { Text("声音描述") }, minLines = 5, maxLines = 10, enabled = !state.busy,
                    placeholder = { Text("例如：年轻的女性，声线温暖柔和，清晰自然的普通话，适合安静的文学叙事。") }, modifier = Modifier.fillMaxWidth())
            }
            item("attributes") {
                MoReadSection {
                    DesignChoice("声音类型", state.gender, listOf("female" to "女声", "male" to "男声", "neutral" to "中性"), !state.busy, actions.gender)
                    DesignChoice("语言", state.language, listOf("zh-CN" to "普通话", "yue-Hant-HK" to "粤语", "en-US" to "英语", "ja-JP" to "日语"), !state.busy, actions.language)
                }
            }
        }
        if (state.busy) item("progress") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(state.activity ?: when (state.work) {
                    VoiceDesignWork.GENERATING -> "正在生成音色与试听…"
                    VoiceDesignWork.FETCHING_PREVIEW -> "正在获取试听…"
                    VoiceDesignWork.SAVING -> "正在保存音色…"
                    else -> "助手正在处理…"
                }, style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.candidate?.let { candidate -> item("candidate") {
            MoReadSection(title = "当前试听") {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.name.ifBlank { candidate.request.name }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(candidate.request.description, style = MaterialTheme.typography.bodyMedium)
                    Text(if (state.changed) "设定已修改，请重新生成后再入库。" else if (candidate.previewPath == null) "音色已生成，点击下方按钮获取试听。" else "试听满意后，点击下方“满意，入库”。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        } }
        state.message?.let { text -> item("message") { Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) } }
        item("cost") { Text("生成音色与 AI 对话按已配置服务商的用量计费。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    if (pickPersona) MoReadPageDialog(onDismissRequest = { pickPersona = false }) {
        MoReadSecondaryPage("参考角色", { pickPersona = false }) {
            item { MoReadRow("不指定角色", onClick = { actions.persona(null); pickPersona = false }) }
            items(state.personas, key = { it.id }) { persona ->
                MoReadRow(persona.name, subtitle = persona.subtitle.ifBlank { null },
                    onClick = { actions.persona(persona.id); pickPersona = false },
                    trailing = { PersonaAvatarImage(persona.name, persona.avatarPath, Modifier.size(44.dp)) })
            }
        }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("音色设计") },
        text = { Text("手动描述使用 Gemini TTS；AI 对话使用主对话模型，并按需调用音色工具。\n\n自定义音色保存在当前 Gemini 项目，官方目前提供一年有效期。未满意时可以继续调整，确认后才写入本机音色库。") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

@Composable
private fun DesignChoice(title: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MoReadRow(title, subtitle = choices.firstOrNull { it.first == value }?.second ?: value, onClick = { if (enabled) expanded = true })
        DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onSelect(id) }) }
        }
    }
}
