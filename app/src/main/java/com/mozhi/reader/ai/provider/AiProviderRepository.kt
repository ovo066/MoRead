package com.mozhi.reader.ai.provider

import androidx.room.withTransaction
import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.core.database.MoReadDatabase
import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.core.database.entity.ModelAssignmentEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

data class AiProviderDraft(
    val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val type: AiProviderType,
    val apiFormat: String = "OPENAI",
    val adapter: AiProviderAdapter = AiProviderAdapter.CUSTOM,
    val extraJson: String = "{}",
    val apiKey: String = ""
)

/** 独立模型配置；同一 Provider 下可混合不同能力与专用端点。 */
data class AiModelDraft(
    val id: Long = 0,
    val modelName: String,
    val type: AiModelType = AiModelType.CHAT,
    val chatApiFormat: String = "",
    val endpointPath: String = "",
    val extraJson: String = "{}"
)

@Singleton
class AiProviderRepository @Inject constructor(
    private val database: MoReadDatabase,
    private val providerDao: AiProviderDao,
    private val apiKeyStore: ApiKeyStore,
    private val vectorStore: dagger.Lazy<BoxStore>
) {
    fun observeProviders(): Flow<List<AiProviderEntity>> = providerDao.observeProviders()

    fun observeModels(): Flow<List<AiModelEntity>> = providerDao.observeModels()

    fun observeAssignments(): Flow<List<ModelAssignmentEntity>> =
        providerDao.observeAssignments()

    suspend fun save(draft: AiProviderDraft): Long {
        require(draft.name.isNotBlank()) { "Provider 名称不能为空" }
        require(draft.baseUrl.startsWith("https://")) { "Base URL 必须使用 HTTPS" }

        val normalizedUrl = draft.baseUrl.trim().trimEnd('/')
        val existing = draft.id.takeIf { it != 0L }?.let { providerDao.getProvider(it) }
        val alias = existing?.apiKeyAlias ?: "provider-${UUID.randomUUID()}"
        if (draft.apiKey.isNotBlank()) {
            apiKeyStore.put(alias, draft.apiKey.trim())
        }

        val entity = AiProviderEntity(
            id = existing?.id ?: 0,
            name = draft.name.trim(),
            baseUrl = normalizedUrl,
            apiKeyAlias = alias,
            type = draft.type,
            apiFormat = ProviderProtocolPolicy.normalizeChatDialect(
                draft.adapter,
                ApiDialect.fromWire(draft.apiFormat)
            ).name,
            adapter = draft.adapter,
            extraJson = draft.extraJson.ifBlank { "{}" },
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

        return if (existing == null) {
            providerDao.insertProvider(entity)
        } else {
            providerDao.updateProvider(entity)
            entity.id
        }
    }

    suspend fun delete(provider: AiProviderEntity) {
        database.withTransaction {
            providerDao.clearAssignmentsForProvider(provider.id)
            providerDao.deleteProvider(provider)
        }
        apiKeyStore.remove(provider.apiKeyAlias)
    }

    /** Adds catalog/manual models, skipping an existing model with the same name and capability. */
    suspend fun addModels(providerId: Long, models: List<AiModelDraft>) {
        val provider = providerDao.getProvider(providerId)
            ?: throw IllegalArgumentException("Provider 不存在")
        val now = System.currentTimeMillis()
        models
            .map { it.copy(modelName = it.modelName.trim()) }
            .filter { it.modelName.isNotBlank() }
            .distinctBy { it.type to it.modelName }
            .forEachIndexed { index, draft ->
                if (providerDao.findModel(providerId, draft.modelName, draft.type) == null) {
                    providerDao.insertModel(
                        AiModelEntity(
                            providerId = providerId,
                            modelName = draft.modelName,
                            type = draft.type,
                            chatApiFormat = normalizeModelChatFormat(provider, draft),
                            endpointPath = normalizeEndpointPath(draft.endpointPath),
                            extraJson = draft.extraJson.ifBlank { "{}" },
                            createdAt = now + index
                        )
                    )
                }
            }
    }

    suspend fun saveModel(providerId: Long, draft: AiModelDraft): Long {
        require(draft.modelName.isNotBlank()) { "模型名称不能为空" }
        val provider = providerDao.getProvider(providerId)
            ?: throw IllegalArgumentException("Provider 不存在")
        val existing = draft.id.takeIf { it != 0L }?.let { providerDao.getModel(it) }
        require(existing == null || existing.providerId == providerId) { "模型不属于当前 Provider" }
        val duplicate = providerDao.findModel(providerId, draft.modelName.trim(), draft.type)
        require(duplicate == null || duplicate.id == existing?.id) { "同类型下已存在这个模型" }
        val entity = AiModelEntity(
            id = existing?.id ?: 0,
            providerId = providerId,
            modelName = draft.modelName.trim(),
            type = draft.type,
            chatApiFormat = normalizeModelChatFormat(provider, draft),
            endpointPath = normalizeEndpointPath(draft.endpointPath),
            extraJson = draft.extraJson.ifBlank { "{}" },
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        return if (existing == null) {
            providerDao.insertModel(entity)
        } else {
            providerDao.updateModel(entity)
            val embeddingAssignment = providerDao.getAssignment(ModelRole.EMBEDDING)?.modelId
            val embeddingSemanticsChanged = existing.type != entity.type ||
                existing.modelName != entity.modelName ||
                existing.endpointPath != entity.endpointPath ||
                existing.extraJson != entity.extraJson
            if (embeddingAssignment == entity.id && embeddingSemanticsChanged) {
                clearVectorIndex()
            }
            entity.id
        }
    }

    suspend fun removeModel(modelId: Long) {
        database.withTransaction {
            providerDao.clearAssignmentsForModel(modelId)
            providerDao.deleteModel(modelId)
        }
    }

    suspend fun assign(role: ModelRole, modelId: Long?) {
        val previousModelId = providerDao.getAssignment(role)?.modelId
        if (modelId != null) {
            val model = providerDao.getModel(modelId)
                ?: throw IllegalArgumentException("模型不存在")
            require(model.type == role.requiredModelType()) { "模型能力与分配角色不匹配" }
            val provider = providerDao.getProvider(model.providerId)
                ?: throw IllegalArgumentException("Provider 不存在")
            val route = ProviderProtocolPolicy.route(provider, model)
            if (route is ModelProtocolRoute.Unsupported) {
                throw IllegalArgumentException(route.reason)
            }
        }
        providerDao.upsertAssignment(ModelAssignmentEntity(role, modelId))
        if (role == ModelRole.EMBEDDING && modelId != null && previousModelId != modelId) {
            // 坐标系随模型变化，旧索引立即作废；重建改为首次检索时按书触发，不再全库自动跑
            clearVectorIndex()
        }
    }

    /** 清空全部书籍切片；失败不阻塞配置保存（下次检索会发现无索引再按需建）。 */
    private fun clearVectorIndex() {
        runCatching { VectorQueries.removeAllChunks(vectorStore.get()) }
    }

    fun apiKeyFor(provider: AiProviderEntity): String? =
        apiKeyStore.get(provider.apiKeyAlias)

    private fun normalizeEndpointPath(path: String): String = path.trim().let {
        if (it.isBlank()) "" else "/${it.trim('/')}"
    }

    private fun normalizeModelChatFormat(
        provider: AiProviderEntity,
        draft: AiModelDraft
    ): String {
        if (draft.type != AiModelType.CHAT || draft.chatApiFormat.isBlank()) return ""
        return ProviderProtocolPolicy.normalizeChatDialect(
            provider.adapter,
            ApiDialect.fromWire(draft.chatApiFormat)
        ).name
    }

    private fun ModelRole.requiredModelType(): AiModelType = when (this) {
        ModelRole.CHAT, ModelRole.CHEAP, ModelRole.SUGGESTION -> AiModelType.CHAT
        ModelRole.EMBEDDING -> AiModelType.EMBEDDING
        ModelRole.TTS -> AiModelType.TTS
        ModelRole.IMAGE -> AiModelType.IMAGE
    }
}
