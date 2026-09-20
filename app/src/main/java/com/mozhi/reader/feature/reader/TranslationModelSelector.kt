package com.mozhi.reader.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.entity.*
import com.mozhi.reader.feature.settings.ModelAssignmentRow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class TranslationModelState(
    val providers: Map<Long, AiProviderEntity> = emptyMap(), val eligible: List<AiModelEntity> = emptyList(),
    val assignedId: Long? = null, val fallback: AiModelEntity? = null, val loaded: Boolean = false
)

@HiltViewModel
class TranslationModelViewModel @Inject constructor(private val repository: AiProviderRepository) : ViewModel() {
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    val state = combine(repository.observeProviders(), repository.observeModels(), repository.observeAssignments()) { providers, models, assignments ->
        val byId = providers.associateBy { it.id }
        val eligible = models.filter { it.type == AiModelType.CHAT && byId[it.providerId]?.let { provider -> ProviderProtocolPolicy.isSupported(provider, it) } == true }
        TranslationModelState(byId, eligible, assignments.firstOrNull { it.role == ModelRole.TRANSLATION }?.modelId,
            eligible.firstOrNull { it.id == assignments.firstOrNull { it.role == ModelRole.CHAT }?.modelId }, true)
    }.catch { error -> mutableError.value = error.message ?: "无法读取模型配置" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranslationModelState())

    fun select(id: Long?) = viewModelScope.launch {
        try { repository.assign(ModelRole.TRANSLATION, id); mutableError.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutableError.value = error.message ?: "模型配置未保存" }
    }
}

@Composable
internal fun TranslationModelSelector(enabled: Boolean, viewModel: TranslationModelViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    Column {
        ModelAssignmentRow(ModelRole.TRANSLATION, state.eligible, state.providers, state.assignedId,
            state.fallback, enabled = enabled && state.loaded, onSelect = viewModel::select)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.loaded && state.eligible.isEmpty()) Text("先在设置 → AI 服务中添加支持对话的模型。", style = MaterialTheme.typography.bodySmall)
    }
}
