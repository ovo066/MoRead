package com.mozhi.reader.feature.companion

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.persona.PersonaRepository
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.vector.VectorQueries
import dagger.hilt.android.lifecycle.HiltViewModel
import io.objectbox.BoxStore
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CompanionUiState(
    val personas: List<PersonaEntity> = emptyList(),
    /** 当前伴读角色；未选择时回落到第一个角色。 */
    val activePersonaId: Long? = null,
    /** personaId → 长期记忆条数。 */
    val memoryCounts: Map<Long, Long> = emptyMap(),
    /** 全局长期记忆开关；关掉时每张卡上的记忆条数都只是历史存档，界面要说清楚。 */
    val longTermMemoryEnabled: Boolean = true,
    val loaded: Boolean = false
) {
    /** 伴读中的角色排在最前：它是这一页唯一「正在生效」的东西。 */
    val orderedPersonas: List<PersonaEntity>
        get() = personas.sortedByDescending { it.id == activePersonaId }
}

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val vectorStore: dagger.Lazy<BoxStore>
) : ViewModel() {

    private val personas = personaRepository.observePersonas().shareIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        replay = 1
    )

    /**
     * ObjectBox 首次打开可能需要初始化映射。计数保持在 IO 线程并单独产出，
     * 不再阻塞 personas 的首次 UI 状态：角色卡先显示，记忆数稍后补上。
     */
    private val memoryCounts = personas.mapLatest { list ->
        withContext(Dispatchers.IO) {
            list.associate { persona ->
                persona.id to runCatching {
                    VectorQueries.countMemories(vectorStore.get(), persona.id)
                }.getOrDefault(0L)
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = emptyMap()
    )

    val uiState = combine(
        personas,
        settingsRepository.activePersonaId,
        memoryCounts,
        settingsRepository.companionMemorySettings
    ) { personas, storedActiveId, counts, memory ->
        CompanionUiState(
            personas = personas,
            activePersonaId = storedActiveId.takeIf { id -> personas.any { it.id == id } }
                ?: personas.firstOrNull()?.id,
            memoryCounts = counts,
            longTermMemoryEnabled = memory.longTermEnabled,
            loaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Lazily,
        initialValue = CompanionUiState()
    )

    fun activate(personaId: Long) {
        viewModelScope.launch { settingsRepository.setActivePersonaId(personaId) }
    }
}
