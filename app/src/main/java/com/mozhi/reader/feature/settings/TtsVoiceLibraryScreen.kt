package com.mozhi.reader.feature.settings

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Female
import androidx.compose.material.icons.outlined.Male
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.TtsVoiceRepository
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import com.mozhi.reader.ui.components.MoReadMenuDivider
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.theme.MoReadTokens
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

data class TtsVoiceLibraryUiState(
    val voices: List<TtsVoiceEntity> = emptyList(),
    val totalCount: Int = 0,
    val allTags: List<String> = emptyList(),
    val search: String = "",
    val selectedTag: String? = null,
    val pinnedOnly: Boolean = false,
    val genderFilter: String? = null,
    val previewingVoiceId: Long? = null,
    /** 听书当前用的 AI 音色 ID，用来在列表上标出「默认」。 */
    val defaultVoiceId: String = "",
    val message: String? = null
)

private data class VoiceFilters(
    val search: String,
    val selectedTag: String?,
    val pinnedOnly: Boolean,
    val genderFilter: String?,
    val previewingVoiceId: Long?,
    val message: String?
)

@HiltViewModel
class TtsVoiceLibraryViewModel @Inject constructor(
    private val repository: TtsVoiceRepository,
    private val settingsStore: TtsSettingsStore,
    private val mediaService: AiMediaGenerationService
) : ViewModel() {
    private val search = MutableStateFlow("")
    private val selectedTag = MutableStateFlow<String?>(null)
    private val pinnedOnly = MutableStateFlow(false)
    private val genderFilter = MutableStateFlow<String?>(null)
    private val previewingVoiceId = MutableStateFlow<Long?>(null)
    private val message = MutableStateFlow<String?>(null)
    private var player: MediaPlayer? = null

    private val filters = combine(
        search,
        selectedTag,
        pinnedOnly,
        genderFilter,
        combine(previewingVoiceId, message) { previewing, note -> previewing to note }
    ) { query, tag, onlyPinned, gender, (previewing, note) ->
        VoiceFilters(query, tag, onlyPinned, gender, previewing, note)
    }

    val uiState = combine(
        repository.voices,
        filters,
        settingsStore.settings
    ) { voices, filter, settings ->
        val normalized = filter.search.trim()
        val filtered = voices.filter { voice ->
            (!filter.pinnedOnly || voice.pinned) &&
                (filter.genderFilter == null || voice.gender == filter.genderFilter) &&
                (filter.selectedTag == null ||
                    voice.tagsList().any { it.equals(filter.selectedTag, true) }) &&
                (normalized.isBlank() || listOf(voice.displayName, voice.voiceId, voice.tags)
                    .any { it.contains(normalized, true) })
        }
        TtsVoiceLibraryUiState(
            voices = filtered,
            totalCount = voices.size,
            allTags = voices.flatMap { it.tagsList() }.distinct().sorted(),
            search = filter.search,
            selectedTag = filter.selectedTag,
            pinnedOnly = filter.pinnedOnly,
            genderFilter = filter.genderFilter,
            previewingVoiceId = filter.previewingVoiceId,
            defaultVoiceId = settings.aiVoiceId,
            message = filter.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TtsVoiceLibraryUiState())

    fun setSearch(value: String) { search.value = value }
    fun setTag(value: String?) { selectedTag.value = value }
    fun setPinnedOnly(value: Boolean) { pinnedOnly.value = value }
    fun setGenderFilter(value: String?) { genderFilter.value = value }
    fun dismissMessage() { message.value = null }

    fun save(voice: TtsVoiceEntity) = viewModelScope.launch {
        runCatching { repository.save(voice) }
            .onSuccess { message.value = "音色已保存" }
            .onFailure { message.value = it.message ?: "保存失败" }
    }

    fun delete(voice: TtsVoiceEntity) = viewModelScope.launch {
        repository.delete(voice)
        message.value = "已删除「${voice.displayName}」"
    }

    fun togglePinned(voice: TtsVoiceEntity) = save(voice.copy(pinned = !voice.pinned))

    /** 把这个音色设成听书 / AI TTS 的默认发声人——音色库最核心的一步。 */
    fun setAsDefault(voice: TtsVoiceEntity) = viewModelScope.launch {
        settingsStore.update { it.copy(aiVoiceId = voice.voiceId) }
        message.value = "听书默认音色已设为「${voice.displayName}」"
    }

    fun importPresets() = viewModelScope.launch {
        repository.importMiniMaxPresets()
        message.value = "已导入 MiniMax 常用音色"
    }

    suspend fun exportJson(): String = VoiceJson.encode(repository.getVoices())

    fun importJson(raw: String) = viewModelScope.launch {
        runCatching { VoiceJson.decode(raw) }
            .onSuccess { voices ->
                voices.forEach { repository.save(it.copy(id = 0)) }
                message.value = "已导入 ${voices.size} 个音色"
            }
            .onFailure { message.value = it.message ?: "JSON 格式不正确" }
    }

    fun preview(voice: TtsVoiceEntity) {
        player?.release()
        player = null
        viewModelScope.launch {
            previewingVoiceId.value = voice.id
            try {
                val speech = mediaService.synthesizeSpeech(
                    bookId = 0,
                    text = PREVIEW_TEXT,
                    voiceId = voice.voiceId
                )
                player = MediaPlayer().apply {
                    setDataSource(speech.path)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        it.release()
                        player = null
                        previewingVoiceId.value = null
                    }
                    setOnErrorListener { failed, _, _ ->
                        failed.release()
                        player = null
                        previewingVoiceId.value = null
                        message.value = "试听失败"
                        true
                    }
                    prepareAsync()
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                previewingVoiceId.value = null
                message.value = error.message ?: "试听失败，检查「朗读引擎与音色」里的 AI TTS 配置"
            }
        }
    }

    override fun onCleared() {
        player?.release()
    }

    private companion object {
        const val PREVIEW_TEXT = "你好，这是墨知音色库的试听声音。"
    }
}

/**
 * 音色库。
 *
 * 这一页存在的理由只有一句话：**给 AI TTS 的音色 ID 起个人话名字，并挑一个当默认**。
 * 所以顶部先把这句话讲清楚，列表里标出「当前默认」，每条的主操作是试听、次操作
 * 收进三点菜单——而不是四个没有标签的图标按钮让人猜。
 */
@Composable
fun TtsVoiceLibraryScreen(
    onBack: () -> Unit,
    viewModel: TtsVoiceLibraryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<TtsVoiceEntity?>(null) }
    var pendingDelete by remember { mutableStateOf<TtsVoiceEntity?>(null) }
    var showImport by remember { mutableStateOf(false) }
    var exportText by remember { mutableStateOf<String?>(null) }
    var topMenu by remember { mutableStateOf(false) }

    MoReadBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FrostedSurface(shape = CircleShape, shadowElevation = 5.dp) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        "音色库",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "共 ${state.totalCount} 个音色",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FrostedSurface(shape = CircleShape, shadowElevation = 5.dp) {
                    IconButton(
                        onClick = { editor = TtsVoiceEntity(voiceId = "", displayName = "") }
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = "新增音色")
                    }
                }
                Spacer(Modifier.size(8.dp))
                Box {
                    FrostedSurface(shape = CircleShape, shadowElevation = 5.dp) {
                        IconButton(onClick = { topMenu = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                        }
                    }
                    MoReadStableDropdownMenu(
                        expanded = topMenu,
                        onDismissRequest = { topMenu = false },
                        width = 216.dp
                    ) {
                        MoReadMenuItem(
                            text = "导入 MiniMax 预设",
                            icon = Icons.Outlined.Download,
                            onClick = { topMenu = false; viewModel.importPresets() }
                        )
                        MoReadMenuDivider()
                        MoReadMenuItem(
                            text = "从 JSON 导入",
                            icon = Icons.Outlined.Upload,
                            onClick = { topMenu = false; showImport = true }
                        )
                        MoReadMenuItem(
                            text = "导出为 JSON",
                            icon = Icons.Outlined.ContentCopy,
                            onClick = {
                                topMenu = false
                                scope.launch { exportText = viewModel.exportJson() }
                            }
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(
                    start = 20.dp,
                    end = 20.dp,
                    top = 4.dp,
                    bottom = 28.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    FrostedSurface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        shadowElevation = 4.dp
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(
                                "音色库是干什么的",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "AI 语音服务用一串音色 ID 来指定谁在说话。这里给这些 ID 起个人话名字、" +
                                    "打上标签，之后听书的默认发声人、有声书里每个角色的音色，都直接从这份清单里挑。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val defaultName = state.voices
                                .firstOrNull { it.voiceId == state.defaultVoiceId }
                                ?.displayName
                            if (state.defaultVoiceId.isNotBlank()) {
                                Spacer(Modifier.height(10.dp))
                                Surface(
                                    shape = MoReadTokens.CapsuleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.TaskAlt,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            "听书默认：${defaultName ?: state.defaultVoiceId}",
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.totalCount == 0) {
                    item {
                        EmptyVoiceLibrary(
                            onImportPresets = viewModel::importPresets,
                            onCreate = {
                                editor = TtsVoiceEntity(voiceId = "", displayName = "")
                            }
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            value = state.search,
                            onValueChange = viewModel::setSearch,
                            placeholder = { Text("搜索名称、ID 或标签") },
                            leadingIcon = {
                                Icon(Icons.Outlined.Search, contentDescription = null)
                            },
                            singleLine = true,
                            shape = MoReadTokens.CapsuleShape,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                FilterChip(
                                    selected = state.pinnedOnly,
                                    onClick = { viewModel.setPinnedOnly(!state.pinnedOnly) },
                                    label = { Text("常用", style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Outlined.PushPin,
                                            contentDescription = null,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = state.genderFilter == "MALE",
                                    onClick = {
                                        viewModel.setGenderFilter(
                                            "MALE".takeUnless { state.genderFilter == "MALE" }
                                        )
                                    },
                                    label = { Text("男声", style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                            item {
                                FilterChip(
                                    selected = state.genderFilter == "FEMALE",
                                    onClick = {
                                        viewModel.setGenderFilter(
                                            "FEMALE".takeUnless { state.genderFilter == "FEMALE" }
                                        )
                                    },
                                    label = { Text("女声", style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                            items(state.allTags) { tag ->
                                FilterChip(
                                    selected = state.selectedTag == tag,
                                    onClick = {
                                        viewModel.setTag(tag.takeUnless { state.selectedTag == tag })
                                    },
                                    label = {
                                        Text(
                                            tag,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1
                                        )
                                    }
                                )
                            }
                        }
                    }

                    if (state.voices.isEmpty()) {
                        item {
                            Text(
                                "没有符合条件的音色，换个关键词或清掉筛选试试。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 20.dp)
                            )
                        }
                    }

                    items(state.voices, key = TtsVoiceEntity::id) { voice ->
                        VoiceCard(
                            voice = voice,
                            isDefault = voice.voiceId == state.defaultVoiceId &&
                                state.defaultVoiceId.isNotBlank(),
                            previewing = state.previewingVoiceId == voice.id,
                            onPreview = { viewModel.preview(voice) },
                            onSetDefault = { viewModel.setAsDefault(voice) },
                            onTogglePinned = { viewModel.togglePinned(voice) },
                            onEdit = { editor = voice },
                            onDelete = { pendingDelete = voice }
                        )
                    }
                }

                state.message?.let {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = viewModel::dismissMessage) { Text("知道了") }
                        }
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding()) }
            }
        }
    }

    editor?.let { voice ->
        VoiceEditorDialog(voice, { editor = null }) {
            viewModel.save(it)
            editor = null
        }
    }
    pendingDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            icon = { Icon(Icons.Outlined.Delete, contentDescription = null) },
            title = { Text("删除「${voice.displayName}」？", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "只从音色库移除这条记录，不影响已经合成好的音频。",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(voice); pendingDelete = null }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
    if (showImport) {
        JsonDialog(
            title = "导入音色 JSON",
            initial = "",
            confirmLabel = "导入",
            onDismiss = { showImport = false },
            onConfirm = { viewModel.importJson(it); showImport = false }
        )
    }
    exportText?.let { json ->
        JsonDialog(
            title = "导出音色 JSON",
            initial = json,
            confirmLabel = "复制",
            readOnly = true,
            onDismiss = { exportText = null },
            onConfirm = { clipboard.setText(AnnotatedString(it)); exportText = null }
        )
    }
}

@Composable
private fun EmptyVoiceLibrary(onImportPresets: () -> Unit, onCreate: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "还没有音色",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "用 MiniMax 的话，一键导入常用音色即可开始；别的服务商就手动添一条，填上服务商给的音色 ID。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImportPresets, shape = MoReadTokens.CapsuleShape) {
                    Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("导入 MiniMax 预设", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedButton(onClick = onCreate, shape = MoReadTokens.CapsuleShape) {
                    Text("手动添加", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
private fun VoiceCard(
    voice: TtsVoiceEntity,
    isDefault: Boolean,
    previewing: Boolean,
    onPreview: () -> Unit,
    onSetDefault: () -> Unit,
    onTogglePinned: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    FrostedSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 主操作就是试听：一枚实心圆钮，合成中原地转圈。
            Surface(
                onClick = onPreview,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (previewing) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            Icons.Outlined.PlayArrow,
                            contentDescription = "试听 ${voice.displayName}",
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        voice.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when (voice.gender) {
                        "MALE" -> GenderBadge(Icons.Outlined.Male, Color(0xFF4A6785))
                        "FEMALE" -> GenderBadge(Icons.Outlined.Female, Color(0xFFA84D55))
                    }
                    if (voice.pinned) {
                        Icon(
                            Icons.Outlined.PushPin,
                            contentDescription = "常用",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp).size(14.dp)
                        )
                    }
                    if (isDefault) {
                        Surface(
                            shape = MoReadTokens.CapsuleShape,
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                "默认",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    voice.voiceId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (voice.tags.isNotBlank()) {
                    Text(
                        voice.tagsList().joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = "更多操作")
                }
                MoReadStableDropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    width = 208.dp
                ) {
                    MoReadMenuItem(
                        text = "设为听书默认",
                        icon = Icons.Outlined.TaskAlt,
                        selected = isDefault,
                        enabled = !isDefault,
                        onClick = { menu = false; onSetDefault() }
                    )
                    MoReadMenuItem(
                        text = if (voice.pinned) "取消常用" else "标为常用",
                        icon = Icons.Outlined.PushPin,
                        onClick = { menu = false; onTogglePinned() }
                    )
                    MoReadMenuDivider()
                    MoReadMenuItem(
                        text = "编辑",
                        icon = Icons.Outlined.Edit,
                        onClick = { menu = false; onEdit() }
                    )
                    MoReadMenuItem(
                        text = "删除",
                        icon = Icons.Outlined.Delete,
                        destructive = true,
                        onClick = { menu = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun GenderBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Icon(
        icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.padding(start = 6.dp).size(14.dp)
    )
}

@Composable
private fun VoiceEditorDialog(
    initial: TtsVoiceEntity,
    onDismiss: () -> Unit,
    onSave: (TtsVoiceEntity) -> Unit
) {
    var voice by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (initial.id == 0L) "新增音色" else "编辑音色",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    voice.displayName,
                    { voice = voice.copy(displayName = it) },
                    label = { Text("显示名称") },
                    supportingText = { Text("给自己看的名字，如「沉稳男声」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    voice.voiceId,
                    { voice = voice.copy(voiceId = it) },
                    label = { Text("音色 ID") },
                    supportingText = { Text("服务商给的值，如 male-qn-jingying / alloy") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    voice.tags,
                    { voice = voice.copy(tags = it) },
                    label = { Text("标签") },
                    supportingText = { Text("逗号分隔，用来在库里筛选，如「旁白, 低沉」") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "音色性别",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("UNSPECIFIED" to "不限", "MALE" to "男声", "FEMALE" to "女声")
                        .forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = voice.gender == value,
                                onClick = { voice = voice.copy(gender = value) },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 3)
                            ) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                }
                OutlinedTextField(
                    voice.providerHint,
                    { voice = voice.copy(providerHint = it) },
                    label = { Text("供应商提示（可选）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    voice.extraJson,
                    { voice = voice.copy(extraJson = it) },
                    label = { Text("参数覆盖 JSON（可选）") },
                    supportingText = { Text("留空即可；填了会合并进这枚音色的请求体") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(voice) },
                enabled = voice.voiceId.isNotBlank() && voice.displayName.isNotBlank()
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun JsonDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    readOnly: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (!readOnly) text = it },
                readOnly = readOnly,
                minLines = 8,
                modifier = Modifier.fillMaxWidth().height(280.dp)
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun TtsVoiceEntity.tagsList(): List<String> = tags.split(',', '，')
    .map(String::trim)
    .filter(String::isNotBlank)

@Serializable
private data class VoiceJsonItem(
    val voiceId: String,
    val displayName: String,
    val tags: String = "",
    val gender: String = "UNSPECIFIED",
    val providerHint: String = "",
    val extraJson: String = "",
    val pinned: Boolean = false,
    val sortOrder: Int = 0
)

private object VoiceJson {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val serializer = ListSerializer(VoiceJsonItem.serializer())

    fun encode(voices: List<TtsVoiceEntity>): String = json.encodeToString(
        serializer,
        voices.map {
            VoiceJsonItem(
                it.voiceId,
                it.displayName,
                it.tags,
                it.gender,
                it.providerHint,
                it.extraJson,
                it.pinned,
                it.sortOrder
            )
        }
    )

    fun decode(raw: String): List<TtsVoiceEntity> = json.decodeFromString(serializer, raw).map {
        TtsVoiceEntity(
            voiceId = it.voiceId,
            displayName = it.displayName,
            tags = it.tags,
            gender = it.gender,
            providerHint = it.providerHint,
            extraJson = it.extraJson,
            pinned = it.pinned,
            sortOrder = it.sortOrder
        )
    }
}
