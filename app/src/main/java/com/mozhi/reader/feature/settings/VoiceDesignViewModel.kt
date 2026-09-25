package com.mozhi.reader.feature.settings

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.client.*
import com.mozhi.reader.ai.agent.AgentEvent
import com.mozhi.reader.ai.media.VoiceDesignAssistant
import com.mozhi.reader.ai.media.VoiceDesignActions
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.speech.TtsVoiceRepository
import com.mozhi.reader.core.speech.VoiceDesignPreviewStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

enum class VoiceDesignWork { NONE, ASSISTING, GENERATING, FETCHING_PREVIEW, SAVING }
data class VoiceDesignCandidate(
    val voiceId: String, val request: GeminiVoiceDesignRequest, val sourceBaseUrl: String,
    val previewPath: String? = null
)
data class VoiceDesignChat(val id: Long, val role: ChatRole, val text: String)
data class VoiceDesignState(
    val name: String = "", val description: String = "", val gender: String = "female", val language: String = "zh-CN",
    val personaId: Long? = null, val personas: List<PersonaEntity> = emptyList(),
    val candidate: VoiceDesignCandidate? = null, val work: VoiceDesignWork = VoiceDesignWork.NONE,
    val playing: Boolean = false, val message: String? = null, val savedId: Long? = null,
    val chats: List<VoiceDesignChat> = emptyList(), val activity: String? = null
) {
    val busy: Boolean get() = work != VoiceDesignWork.NONE
    val changed: Boolean get() = candidate?.let {
        description.trim() != it.request.description || gender != it.request.gender || language != it.request.language
    } ?: false
    val canSave: Boolean get() = candidate?.previewPath != null && !changed && !busy && name.isNotBlank() && savedId == null
    fun request() = GeminiVoiceDesignRequest(name.trim(), description.trim(), gender, language)
}

@HiltViewModel
class VoiceDesignViewModel @Inject constructor(
    private val clients: AiClientFactory,
    private val assistant: VoiceDesignAssistant,
    private val voices: TtsVoiceRepository,
    private val previews: VoiceDesignPreviewStore,
    personas: PersonaRepository,
    @ApplicationScope applicationScope: CoroutineScope
) : ViewModel() {
    private val mutable = MutableStateFlow(VoiceDesignState())
    val state = mutable.asStateFlow()
    private val maintenance = CoroutineScope(applicationScope.coroutineContext + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var generation = 0
    private var closed = false
    private var player: MediaPlayer? = null
    /** Only voices created in this editor are eligible for automatic draft cleanup. */
    private val owned = mutableMapOf<String, GeminiTtsClient>()
    private var savingVoiceId: String? = null
    private var chatId = 0L

    init { viewModelScope.launch { personas.observePersonas().collect { list -> mutable.update { it.copy(personas = list) } } } }

    fun begin() {
        if (closed || mutable.value.savedId != null) mutable.update { VoiceDesignState(personas = it.personas) }
        closed = false
    }
    private fun edit(transform: (VoiceDesignState) -> VoiceDesignState) {
        if (!mutable.value.busy) mutable.update { transform(it).copy(message = null) }
    }
    fun name(value: String) = edit { it.copy(name = value.take(80)) }
    fun description(value: String) = edit { it.copy(description = value.take(2000)) }
    fun gender(value: String) = edit { it.copy(gender = value) }
    fun language(value: String) = edit { it.copy(language = value) }
    fun persona(id: Long?) = edit { state ->
        val persona = state.personas.firstOrNull { it.id == id }
        state.copy(personaId = id, name = state.name.ifBlank { persona?.name?.let { "$it 的声音" }.orEmpty().take(80) })
    }

    fun send(utterance: String) {
        if (mutable.value.busy || utterance.isBlank()) return
        stopPreview()
        val user = VoiceDesignChat(++chatId, ChatRole.USER, utterance.trim().take(2000))
        val reply = VoiceDesignChat(++chatId, ChatRole.ASSISTANT, "")
        val history = (mutable.value.chats.takeLast(18) + user).map { ChatMessage(it.role, it.text) }
        val token = ++generation
        mutable.update { it.copy(work = VoiceDesignWork.ASSISTING, message = null, activity = "正在理解需求…", chats = (it.chats + user + reply).takeLast(40)) }
        operation = viewModelScope.launch {
            try {
                val actions = object : VoiceDesignActions {
                    override fun snapshot(): JsonObject = snapshotJson()
                    override fun update(request: GeminiVoiceDesignRequest) {
                        check(token == generation && !closed)
                        mutable.update { it.copy(name = request.name, description = request.description, gender = request.gender, language = request.language) }
                    }
                    override suspend fun generatePreview(): JsonObject { generateCandidate(token); return snapshotJson() }
                    override suspend fun fetchPreview(): JsonObject { fetchCandidatePreview(token); return snapshotJson() }
                }
                assistant.run(history, actions).collect { event ->
                    if (token != generation) return@collect
                    when (event) {
                        is AgentEvent.Text -> mutable.update { state -> state.copy(activity = null, chats = state.chats.map {
                            if (it.id == reply.id) it.copy(text = (it.text + event.text).take(12000)) else it
                        }) }
                        is AgentEvent.ToolRun -> mutable.update { it.copy(activity = event.displayName) }
                        is AgentEvent.ToolFinished -> mutable.update { it.copy(activity = if (event.succeeded) "${event.displayName}完成" else "${event.displayName}未完成") }
                        else -> Unit
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) mutable.update { it.copy(message = error.message ?: "音色助手暂时无法继续") } }
            finally { if (token == generation) mutable.update { it.copy(work = VoiceDesignWork.NONE, activity = null) } }
        }
    }

    private fun snapshotJson() = buildJsonObject {
        val state = mutable.value
        put("name", state.name); put("description", state.description); put("gender", state.gender); put("language", state.language)
        state.personaId?.let { put("reference_persona_id", it) }
        state.candidate?.let {
            put("candidate_voice_id", it.voiceId); put("preview_ready", it.previewPath != null)
            put("description_changed_after_generation", state.changed)
            put("generated_description", it.request.description)
        }
        put("saved", state.savedId != null)
    }

    fun stopAgent() {
        ++generation; operation?.cancel()
        mutable.update { it.copy(work = VoiceDesignWork.NONE, activity = null, message = "已停止，当前设定与已完成的试听仍保留") }
    }

    fun generate() {
        if (mutable.value.busy) return
        val request = mutable.value.request()
        if (request.name.isBlank() || request.description.isBlank()) {
            mutable.update { it.copy(message = "请填写音色名称和声音描述") }; return
        }
        stopPreview()
        val token = ++generation
        mutable.update { it.copy(work = VoiceDesignWork.GENERATING, message = null) }
        operation = viewModelScope.launch {
            try {
                generateCandidate(token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) mutable.update { it.copy(message = error.message ?: "生成音色失败") } }
            finally { if (token == generation) mutable.update { it.copy(work = VoiceDesignWork.NONE) } }
        }
    }

    private suspend fun generateCandidate(token: Int) {
        check(token == generation && !closed)
        val request = mutable.value.request()
        val client = clients.geminiVoiceDesigner()
        val result = client.designVoice(request)
        owned[result.id] = client
        if (token != generation || closed) { clean(result.id); return }
        val previous = mutable.value.candidate
        val candidate = VoiceDesignCandidate(result.id, request, client.sourceBaseUrl)
        mutable.update { it.copy(candidate = candidate) }
        previous?.takeIf { it.voiceId != result.id }?.let { clean(it.voiceId) }
        val path = result.preview?.let { previews.save(result.id, it.bytes) }
        if (token == generation) mutable.update { it.copy(candidate = candidate.copy(previewPath = path),
            message = if (path == null) "音色已生成，点击获取试听即可继续，无需重新生成" else null) }
    }

    fun fetchPreview() {
        val candidate = mutable.value.candidate ?: return
        if (owned[candidate.voiceId] == null) return
        if (mutable.value.busy) return
        val token = ++generation
        mutable.update { it.copy(work = VoiceDesignWork.FETCHING_PREVIEW, message = null) }
        operation = viewModelScope.launch {
            try {
                fetchCandidatePreview(token)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (token == generation) mutable.update { it.copy(message = error.message ?: "获取试听失败") } }
            finally { if (token == generation) mutable.update { it.copy(work = VoiceDesignWork.NONE) } }
        }
    }

    private suspend fun fetchCandidatePreview(token: Int) {
        check(token == generation && !closed)
        val candidate = mutable.value.candidate ?: error("请先生成音色")
        val client = owned[candidate.voiceId] ?: error("请重新打开音色设计")
        val audio = client.designedVoicePreview(candidate.voiceId)
        val path = previews.save(candidate.voiceId, audio.bytes)
        if (token == generation) mutable.update { it.copy(candidate = candidate.copy(previewPath = path), message = null) }
    }

    fun save() {
        val current = mutable.value
        if (!current.canSave) return
        val candidate = current.candidate ?: return
        stopPreview()
        savingVoiceId = candidate.voiceId
        mutable.update { it.copy(work = VoiceDesignWork.SAVING, message = null) }
        // A navigation/lifecycle cancellation must not delete a cloud voice whose Room commit succeeded.
        maintenance.launch {
            try {
                val entity = TtsVoiceEntity(
                    voiceId = candidate.voiceId, displayName = current.name.trim(), providerHint = "GEMINI",
                    gender = when (candidate.request.gender) { "male" -> "MALE"; "female" -> "FEMALE"; else -> "UNSPECIFIED" },
                    tags = "Gemini,自定义,${candidate.request.language}",
                    extraJson = buildJsonObject {
                        putJsonObject("voice_design") {
                            put("description", candidate.request.description)
                            put("language", candidate.request.language)
                            put("source_base_url", candidate.sourceBaseUrl)
                            put("created_at", System.currentTimeMillis())
                        }
                    }.toString()
                )
                val id = voices.saveDesignedVoice(entity)
                check(id > 0) { "保存音色失败，请重试" }
                owned.remove(candidate.voiceId)
                mutable.update { it.copy(savedId = id, work = VoiceDesignWork.NONE) }
            } catch (error: Exception) {
                mutable.update { it.copy(work = VoiceDesignWork.NONE, message = error.message ?: "保存失败，可重试") }
                if (closed) clean(candidate.voiceId)
            } finally { savingVoiceId = null }
        }
    }

    fun togglePreview() {
        if (mutable.value.playing) { stopPreview(); return }
        val path = mutable.value.candidate?.previewPath ?: return
        stopPreview()
        try {
            val instance = MediaPlayer()
            player = instance
            instance.apply {
                setDataSource(path)
                setOnPreparedListener { if (player === it) it.start() }
                setOnCompletionListener { if (player === it) stopPreview() }
                setOnErrorListener { failed, _, _ -> if (player === failed) previewFailed(); true }
                prepareAsync()
            }
            mutable.update { it.copy(playing = true) }
        } catch (_: Exception) { previewFailed() }
    }
    private fun previewFailed() {
        stopPreview()
        mutable.update { it.copy(candidate = it.candidate?.copy(previewPath = null), message = "试听播放失败，请重新获取试听") }
    }
    fun stopPreview() { player?.release(); player = null; mutable.update { it.copy(playing = false) } }

    private fun clean(id: String) {
        val client = owned.remove(id) ?: return
        maintenance.launch {
            try {
                // An online-catalog import may have saved this candidate independently of this editor.
                if (voices.containsGeminiVoice(id)) return@launch
                withTimeout(15_000) { client.deleteDesignedVoice(id) }
            } catch (_: Exception) { /* Only our draft; server TTL is the final fallback. */ }
            previews.remove(id)
        }
    }

    fun discard() {
        closed = true
        ++generation
        operation?.cancel()
        stopPreview()
        owned.keys.toList().filter { it != savingVoiceId }.forEach(::clean)
    }
    override fun onCleared() { discard() }
}
