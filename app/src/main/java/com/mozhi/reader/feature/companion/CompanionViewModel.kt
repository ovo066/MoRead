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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CompanionUiState(
    val personas: List<PersonaEntity> = emptyList(),
    /** 当前伴读角色；未选择时回落到第一个角色。 */
    val activePersonaId: Long? = null,
    /** personaId → 长期记忆条数。 */
    val memoryCounts: Map<Long, Long> = emptyMap(),
    val loaded: Boolean = false
)

@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val personaRepository: PersonaRepository,
    private val settingsRepository: ReaderSettingsRepository,
    private val vectorStore: dagger.Lazy<BoxStore>
) : ViewModel() {

    val uiState = combine(
        personaRepository.observePersonas(),
        settingsRepository.activePersonaId
    ) { personas, storedActiveId ->
        val counts = withContext(Dispatchers.IO) {
            personas.associate { persona ->
                persona.id to runCatching {
                    VectorQueries.countMemories(vectorStore.get(), persona.id)
                }.getOrDefault(0L)
            }
        }
        CompanionUiState(
            personas = personas,
            activePersonaId = storedActiveId.takeIf { id -> personas.any { it.id == id } }
                ?: personas.firstOrNull()?.id,
            memoryCounts = counts,
            loaded = true
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CompanionUiState()
    )

    fun activate(personaId: Long) {
        viewModelScope.launch { settingsRepository.setActivePersonaId(personaId) }
    }
}
