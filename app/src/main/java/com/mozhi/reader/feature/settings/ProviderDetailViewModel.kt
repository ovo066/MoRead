package com.mozhi.reader.feature.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.ai.provider.AiProviderDraft
import com.mozhi.reader.ai.provider.AiModelDraft
import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.ai.provider.CatalogModel
import com.mozhi.reader.ai.provider.ConnectionTestResult
import com.mozhi.reader.ai.provider.ModelCatalogFetcher
import com.mozhi.reader.ai.provider.ModelCatalogResult
import com.mozhi.reader.ai.provider.ProviderConnectionTester
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiProviderEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ProviderDetailState(
    /** null = 新建模式（还没保存）。 */
    val provider: AiProviderEntity? = null,
    val models: List<AiModelEntity> = emptyList(),
    val connected: Boolean = false,
    val isWorking: Boolean = false
) {
    val isNew: Boolean get() = provider == null
}

/** A fetched model catalog awaiting the user's pick, shown as a multi-select dialog. */
data class ModelCatalogPick(
    val provider: AiProviderEntity,
    val models: List<CatalogModel>,
    val alreadyAdded: Set<String>
)

sealed interface ProviderDetailEvent {
    data class Message(val text: String) : ProviderDetailEvent
    data object Deleted : ProviderDetailEvent
}

/** Provider 详情二级页：基本信息编辑 + 模型管理 + 连接测试。providerId = 0 为新建。 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProviderDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val providerRepository: AiProviderRepository,
    private val connectionTester: ProviderConnectionTester,
    private val catalogFetcher: ModelCatalogFetcher
) : ViewModel() {

    /** 新建保存成功后就地切换为编辑模式，方便接着添加模型。 */
    private val providerId = MutableStateFlow(
        savedStateHandle.get<String>("providerId")?.toLongOrNull() ?: 0L
    )
    private val working = MutableStateFlow(false)
    private val connected = MutableStateFlow(false)

    private val eventChannel = Channel<ProviderDetailEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private val mutableCatalogPick = MutableStateFlow<ModelCatalogPick?>(null)
    val catalogPick = mutableCatalogPick.asStateFlow()

    val state = providerId.flatMapLatest { id ->
        combine(
            providerRepository.observeProviders(),
            providerRepository.observeModels(),
            working,
            connected
        ) { providers, models, isWorking, isConnected ->
            ProviderDetailState(
                provider = providers.find { it.id == id },
                models = models.filter { it.providerId == id },
                connected = isConnected,
                isWorking = isWorking
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ProviderDetailState()
    )

    fun save(draft: AiProviderDraft) {
        viewModelScope.launch {
            working.value = true
            runCatching { providerRepository.save(draft.copy(id = providerId.value)) }
                .onSuccess { savedId ->
                    providerId.value = savedId
                    eventChannel.send(ProviderDetailEvent.Message("已保存"))
                }
                .onFailure { error ->
                    eventChannel.send(ProviderDetailEvent.Message(error.message ?: "保存失败"))
                }
            working.value = false
        }
    }

    fun delete() {
        val provider = state.value.provider ?: return
        viewModelScope.launch {
            runCatching { providerRepository.delete(provider) }
                .onSuccess { eventChannel.send(ProviderDetailEvent.Deleted) }
                .onFailure { eventChannel.send(ProviderDetailEvent.Message("删除失败")) }
        }
    }

    fun test() {
        val provider = state.value.provider ?: return
        viewModelScope.launch {
            working.value = true
            val result = connectionTester.test(
                provider = provider,
                apiKey = providerRepository.apiKeyFor(provider)
            )
            connected.value = result == ConnectionTestResult.Success
            eventChannel.send(
                ProviderDetailEvent.Message(
                    when (result) {
                        ConnectionTestResult.Success -> "连接成功"
                        is ConnectionTestResult.Failure -> result.message
                    }
                )
            )
            working.value = false
        }
    }

    /** 手动新增或编辑一个模型；新建时模型名也可用逗号/换行批量填写。 */
    fun saveModel(draft: AiModelDraft) {
        val id = providerId.value.takeIf { it != 0L } ?: return
        val names = draft.modelName.split(',', '\n', '，').map(String::trim).filter(String::isNotBlank)
        if (names.isEmpty()) return
        viewModelScope.launch {
            val result = runCatching {
                if (draft.id == 0L && names.size > 1) {
                    providerRepository.addModels(id, names.map { draft.copy(modelName = it) })
                } else {
                    providerRepository.saveModel(id, draft.copy(modelName = names.first()))
                }
            }
            result
                .onSuccess {
                    eventChannel.send(
                        ProviderDetailEvent.Message(
                            if (draft.id == 0L) "已添加 ${names.size} 个模型" else "模型配置已保存"
                        )
                    )
                }
                .onFailure {
                    eventChannel.send(ProviderDetailEvent.Message(it.message ?: "保存失败"))
                }
        }
    }

    fun removeModel(model: AiModelEntity) {
        viewModelScope.launch {
            runCatching { providerRepository.removeModel(model.id) }
                .onFailure { eventChannel.send(ProviderDetailEvent.Message("删除失败")) }
        }
    }

    /** Fetches the provider's catalog and opens the multi-select dialog. */
    fun fetchModelCatalog() {
        val provider = state.value.provider ?: return
        viewModelScope.launch {
            working.value = true
            val result = catalogFetcher.fetch(provider, providerRepository.apiKeyFor(provider))
            working.value = false
            when (result) {
                is ModelCatalogResult.Success -> {
                    mutableCatalogPick.value = ModelCatalogPick(
                        provider = provider,
                        models = result.models,
                        alreadyAdded = state.value.models.map { "${it.type.name}:${it.modelName}" }.toSet()
                    )
                }
                is ModelCatalogResult.Failure ->
                    eventChannel.send(ProviderDetailEvent.Message(result.message))
            }
        }
    }

    fun confirmCatalogPick(selected: List<CatalogModel>) {
        mutableCatalogPick.value = null
        if (selected.isEmpty()) return
        val id = providerId.value.takeIf { it != 0L } ?: return
        viewModelScope.launch {
            runCatching { providerRepository.addModels(id, selected.map(CatalogModel::toDraft)) }
                .onSuccess {
                    eventChannel.send(ProviderDetailEvent.Message("已添加 ${selected.size} 个模型"))
                }
                .onFailure { eventChannel.send(ProviderDetailEvent.Message("添加失败")) }
        }
    }

    fun dismissCatalogPick() {
        mutableCatalogPick.value = null
    }
}
