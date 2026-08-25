package com.mozhi.reader.feature.companion

import android.content.Context
import android.net.Uri
import android.media.MediaPlayer
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.persona.PersonaAvatarStore
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.ai.persona.SillyTavernCardParser
import com.mozhi.reader.core.database.entity.PersonaChatAppearance
import com.mozhi.reader.core.database.entity.PersonaChatAppearanceCodec
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaExampleDialog
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.enabledTools
import com.mozhi.reader.core.database.entity.encodeEnabledTools
import com.mozhi.reader.core.database.entity.encodeExampleDialogs
import com.mozhi.reader.core.database.entity.encodeWorldBook
import com.mozhi.reader.core.database.entity.exampleDialogs
import com.mozhi.reader.core.database.entity.chatAppearance
import com.mozhi.reader.core.database.entity.worldBook
import com.mozhi.reader.core.datastore.ReaderFontAsset
import com.mozhi.reader.core.datastore.ReaderImageAsset
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.ai.memory.PersonaMemoryRepository
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.speech.TtsVoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑器可选的工具白名单（M2 全集；未实现的工具注册时自然缺席，白名单是前向兼容的）。 */
val PersonaToolOptions = listOf(
    "get_reading_progress" to "查询阅读进度",
    "search_book" to "检索书中原文",
    "read_book_section" to "读取指定已读章节",
    "recall_memory" to "回忆过往交流",
    "add_annotation" to "添加批注",
    "write_note" to "写读书笔记",
    "save_plot_summary" to "保存剧情梗概",
    "generate_image" to "生成并保存插图",
    "synthesize_speech" to "合成并缓存语音",
    "create_reading_plan" to "制定阅读计划"
)

data class PersonaEditorState(
    val personaId: Long = 0,
    val name: String = "",
    val subtitle: String = "",
    val personality: String = "",
    val speakingStyle: String = "",
    val greeting: String = "",
    val dialogs: List<PersonaExampleDialog> = emptyList(),
    val worldBook: List<PersonaLoreEntry> = emptyList(),
    val worldBookEnabled: Boolean = true,
    val isRoleplay: Boolean = true,
    val enabledTools: Set<String> = PersonaToolOptions.map { it.first }.toSet(),
    val avatarPath: String? = null,
    val memoryEnabled: Boolean = true,
    /** 已沉淀的记忆条数；只作展示，管理入口在二级页。 */
    val memoryCount: Long = 0,
    val appearance: PersonaChatAppearance = PersonaChatAppearance.DEFAULT,
    val voiceId: String = "",
    val voiceEmotion: String = "",
    val voices: List<TtsVoiceEntity> = emptyList(),
    val voiceSearch: String = "",
    val voiceGender: String? = null,
    val previewingVoiceId: Long? = null,
    /** 共享图片库与字体库，聊天外观直接复用，不再单开一套资产管理。 */
    val imageLibrary: List<ReaderImageAsset> = emptyList(),
    val fontLibrary: List<ReaderFontAsset> = emptyList(),
    val isBuiltIn: Boolean = false,
    /** 编辑不落 UI 的透传字段。 */
    val chatModelId: Long? = null,
    val loading: Boolean = true
) {
    val isNew: Boolean get() = personaId == 0L
}

sealed interface PersonaEditorEvent {
    data object Saved : PersonaEditorEvent
    data object Deleted : PersonaEditorEvent
    data class Message(val text: String) : PersonaEditorEvent
}

@HiltViewModel
class PersonaEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val personaRepository: PersonaRepository,
    private val avatarStore: PersonaAvatarStore,
    private val memoryRepository: PersonaMemoryRepository,
    private val settingsRepository: ReaderSettingsRepository,
    voiceRepository: TtsVoiceRepository,
    private val mediaService: AiMediaGenerationService
) : ViewModel() {

    private val personaId: Long = savedStateHandle.get<String>("personaId")?.toLongOrNull() ?: 0L

    private val mutableState = MutableStateFlow(PersonaEditorState(personaId = personaId))
    val state = mutableState.asStateFlow()

    private val eventChannel = Channel<PersonaEditorEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /** 落库前的头像路径基线：换头像后旧的临时文件按它判定是否可删。 */
    private var persistedAvatarPath: String? = null
    private var voicePlayer: MediaPlayer? = null

    init {
        viewModelScope.launch {
            val existing = personaId.takeIf { it != 0L }?.let { personaRepository.getPersona(it) }
            persistedAvatarPath = existing?.avatarPath
            mutableState.update { state ->
                existing?.let { persona ->
                    state.copy(
                        name = persona.name,
                        subtitle = persona.subtitle,
                        personality = persona.personality,
                        speakingStyle = persona.speakingStyle,
                        greeting = persona.greeting,
                        dialogs = persona.exampleDialogs(),
                        worldBook = persona.worldBook(),
                        worldBookEnabled = persona.worldBookEnabled,
                        isRoleplay = persona.isRoleplay,
                        enabledTools = persona.enabledTools().toSet(),
                        avatarPath = persona.avatarPath,
                        memoryEnabled = persona.memoryEnabled,
                        appearance = persona.chatAppearance(),
                        voiceId = persona.voiceId,
                        voiceEmotion = persona.voiceEmotion,
                        isBuiltIn = persona.isBuiltIn,
                        chatModelId = persona.chatModelId,
                        loading = false
                    )
                } ?: state.copy(loading = false)
            }
            if (personaId != 0L) {
                mutableState.update { it.copy(memoryCount = memoryRepository.count(personaId)) }
            }
        }
        // 外观直接引用共享的图片库与字体库；它们是全局资产，不该按角色各存一份。
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableState.update {
                    it.copy(
                        imageLibrary = settings.imageLibrary,
                        fontLibrary = settings.fontLibrary
                    )
                }
            }
        }
        viewModelScope.launch {
            voiceRepository.voices.collect { voices ->
                mutableState.update { it.copy(voices = voices) }
            }
        }
    }

    fun setMemoryEnabled(value: Boolean) = mutableState.update { it.copy(memoryEnabled = value) }

    fun updateAppearance(transform: (PersonaChatAppearance) -> PersonaChatAppearance) =
        mutableState.update { it.copy(appearance = transform(it.appearance).sanitized()) }

    fun resetAppearance() =
        mutableState.update { it.copy(appearance = PersonaChatAppearance.DEFAULT) }

    fun setName(value: String) = mutableState.update { it.copy(name = value) }
    fun setSubtitle(value: String) = mutableState.update { it.copy(subtitle = value) }
    fun setPersonality(value: String) = mutableState.update { it.copy(personality = value) }
    fun setSpeakingStyle(value: String) = mutableState.update { it.copy(speakingStyle = value) }
    fun setGreeting(value: String) = mutableState.update { it.copy(greeting = value) }
    fun setRoleplay(value: Boolean) = mutableState.update { it.copy(isRoleplay = value) }
    fun setVoice(value: String) = mutableState.update { it.copy(voiceId = value) }
    fun setVoiceEmotion(value: String) = mutableState.update { it.copy(voiceEmotion = value) }
    fun setVoiceSearch(value: String) = mutableState.update { it.copy(voiceSearch = value) }
    fun setVoiceGender(value: String?) = mutableState.update { it.copy(voiceGender = value) }

    fun previewVoice(voice: TtsVoiceEntity) {
        voicePlayer?.release()
        voicePlayer = null
        viewModelScope.launch {
            mutableState.update { it.copy(previewingVoiceId = voice.id) }
            runCatching {
                mediaService.synthesizeSpeech(
                    bookId = 0,
                    text = "你好，我会陪你一起读完这本书。",
                    voiceId = voice.voiceId,
                    emotion = mutableState.value.voiceEmotion.takeIf(String::isNotBlank)
                )
            }.onSuccess { speech ->
                voicePlayer = MediaPlayer().apply {
                    setDataSource(speech.path)
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener {
                        it.release()
                        voicePlayer = null
                        mutableState.update { state -> state.copy(previewingVoiceId = null) }
                    }
                    setOnErrorListener { player, _, _ ->
                        player.release()
                        voicePlayer = null
                        mutableState.update { state -> state.copy(previewingVoiceId = null) }
                        true
                    }
                    prepareAsync()
                }
            }.onFailure { error ->
                mutableState.update { it.copy(previewingVoiceId = null) }
                eventChannel.send(PersonaEditorEvent.Message(error.message ?: "试听失败"))
            }
        }
    }

    /** 世界书总开关：关掉后所有条目都不注入，条目本身保留。 */
    fun setWorldBookEnabled(value: Boolean) =
        mutableState.update { it.copy(worldBookEnabled = value) }

    fun toggleTool(tool: String) = mutableState.update { state ->
        state.copy(
            enabledTools = if (tool in state.enabledTools) {
                state.enabledTools - tool
            } else {
                state.enabledTools + tool
            }
        )
    }

    fun addDialog() = mutableState.update {
        it.copy(dialogs = it.dialogs + PersonaExampleDialog(user = "", assistant = ""))
    }

    fun updateDialog(index: Int, user: String, assistant: String) = mutableState.update { state ->
        state.copy(
            dialogs = state.dialogs.mapIndexed { i, dialog ->
                if (i == index) PersonaExampleDialog(user = user, assistant = assistant) else dialog
            }
        )
    }

    fun removeDialog(index: Int) = mutableState.update { state ->
        state.copy(dialogs = state.dialogs.filterIndexed { i, _ -> i != index })
    }

    fun addLoreEntry() = mutableState.update {
        it.copy(worldBook = it.worldBook + PersonaLoreEntry(name = "", content = ""))
    }

    /** 整条替换：名称/内容/启用/注入方式/触发词都经这里。 */
    fun updateLoreEntry(index: Int, entry: PersonaLoreEntry) = mutableState.update { state ->
        state.copy(
            worldBook = state.worldBook.mapIndexed { i, existing ->
                if (i == index) entry else existing
            }
        )
    }

    fun removeLoreEntry(index: Int) = mutableState.update { state ->
        state.copy(worldBook = state.worldBook.filterIndexed { i, _ -> i != index })
    }

    /** 相册选头像：复制进私有目录；被替换的本次会话临时头像顺手删掉。 */
    fun pickAvatar(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val saved = avatarStore.saveFromUri(uri)
            if (saved == null) {
                eventChannel.send(PersonaEditorEvent.Message("头像读取失败"))
                return@launch
            }
            replaceAvatar(saved)
        }
    }

    /**
     * 导入 SillyTavern 角色卡（PNG / JSON），自动提取人设与内嵌世界书。
     * 全链路捕获：任何一步失败都落成可读提示（附原因，方便截图排查），绝不闪退。
     */
    fun importCard(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val card = runCatching {
                val bytes = withContext(Dispatchers.IO) { readBounded(uri) }
                    ?: error("无法读取所选文件")
                withContext(Dispatchers.Default) { SillyTavernCardParser.parse(bytes) }
                    ?: error("无法识别的角色卡格式（支持 PNG 内嵌卡与 JSON 卡）")
            }.getOrElse { error ->
                eventChannel.send(
                    PersonaEditorEvent.Message(
                        "导入失败：${error.message ?: error.javaClass.simpleName}"
                    )
                )
                return@launch
            }

            // 立绘落盘失败不阻断导入，人设照填。
            card.avatarPng?.let { png ->
                runCatching { avatarStore.saveBytes(png) }
                    .onSuccess(::replaceAvatar)
            }
            mutableState.update { state ->
                state.copy(
                    name = card.name,
                    subtitle = card.subtitle,
                    personality = card.personality,
                    greeting = card.greeting,
                    dialogs = card.exampleDialogs,
                    worldBook = card.worldBook,
                    isRoleplay = true
                )
            }
            eventChannel.send(PersonaEditorEvent.Message("已导入「${card.name}」的角色卡"))
        }
    }

    private fun replaceAvatar(newPath: String) {
        val previous = mutableState.value.avatarPath
        mutableState.update { it.copy(avatarPath = newPath) }
        if (previous != null && previous != persistedAvatarPath) {
            avatarStore.delete(previous)
        }
    }

    /**
     * 带上限的流式读取：边读边限。绝不能先 readBytes 再检查——
     * 选择器放宽到 * / * 后误选大文件（如视频）会直接 OOM 闪退。
     */
    private fun readBounded(uri: Uri): ByteArray? =
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                check(total <= MAX_CARD_BYTES) { "文件超过 20MB，不像角色卡" }
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }

    fun save() {
        val state = mutableState.value
        if (state.name.isBlank()) {
            viewModelScope.launch {
                eventChannel.send(PersonaEditorEvent.Message("角色名称不能为空"))
            }
            return
        }
        if (state.personality.isBlank()) {
            viewModelScope.launch {
                eventChannel.send(PersonaEditorEvent.Message("人设描述不能为空"))
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                personaRepository.save(
                    PersonaEntity(
                        id = state.personaId,
                        name = state.name,
                        avatarPath = state.avatarPath,
                        subtitle = state.subtitle.trim(),
                        personality = state.personality.trim(),
                        speakingStyle = state.speakingStyle.trim(),
                        greeting = state.greeting.trim(),
                        exampleDialogsJson = encodeExampleDialogs(
                            state.dialogs.filter {
                                it.user.isNotBlank() && it.assistant.isNotBlank()
                            }
                        ),
                        worldBookJson = encodeWorldBook(
                            state.worldBook.filter { it.content.isNotBlank() }
                        ),
                        worldBookEnabled = state.worldBookEnabled,
                        isRoleplay = state.isRoleplay,
                        enabledToolsJson = encodeEnabledTools(
                            PersonaToolOptions.map { it.first }
                                .filter { it in state.enabledTools }
                        ),
                        chatModelId = state.chatModelId,
                        memoryEnabled = state.memoryEnabled,
                        chatAppearanceJson = PersonaChatAppearanceCodec.encode(state.appearance),
                        voiceId = state.voiceId,
                        voiceEmotion = state.voiceEmotion.trim(),
                        isBuiltIn = state.isBuiltIn,
                        createdAt = 0 // repository 负责保留/生成
                    )
                )
            }.onSuccess {
                persistedAvatarPath = state.avatarPath
                eventChannel.send(PersonaEditorEvent.Saved)
            }.onFailure { error ->
                eventChannel.send(PersonaEditorEvent.Message(error.message ?: "保存失败"))
            }
        }
    }

    fun delete() {
        if (mutableState.value.isNew) return
        viewModelScope.launch {
            runCatching { personaRepository.delete(personaId) }
                .onSuccess { eventChannel.send(PersonaEditorEvent.Deleted) }
                .onFailure { eventChannel.send(PersonaEditorEvent.Message("删除失败")) }
        }
    }

    override fun onCleared() {
        voicePlayer?.release()
    }

    private companion object {
        const val MAX_CARD_BYTES = 20 * 1024 * 1024
    }
}
