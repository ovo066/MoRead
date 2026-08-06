package com.mozhi.reader.ai.client

import com.mozhi.reader.ai.provider.AiProviderRepository
import com.mozhi.reader.ai.provider.ModelProtocolRoute
import com.mozhi.reader.ai.provider.ProviderProtocolPolicy
import com.mozhi.reader.core.database.dao.AiProviderDao
import com.mozhi.reader.core.database.entity.AiProviderEntity
import com.mozhi.reader.core.database.entity.AiProviderType
import com.mozhi.reader.core.database.entity.AiModelEntity
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.media.ImageApiProvider
import com.mozhi.reader.core.media.ImageApiSettingsStore
import com.mozhi.reader.core.security.ApiKeyStore
import com.mozhi.reader.core.speech.TtsSettingsStore
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.OkHttpClient

/** A resolved client plus the provider's default request options. */
data class ResolvedChatClient(
    val client: ChatApiClient,
    val options: ChatOptions,
    val provider: AiProviderEntity,
    val modelName: String
)

data class ResolvedMediaClient(
    val client: OpenAiMediaClient,
    val provider: AiProviderEntity,
    val model: AiModelEntity
)

/** 生图统一出口：独立配置（OpenAI 兼容/NovelAI）或模型分配二者其一。 */
data class ResolvedImageGeneration(
    val client: ImageGenerationClient,
    val label: String
)

@Singleton
class AiClientFactory @Inject constructor(
    private val providerDao: AiProviderDao,
    private val providerRepository: AiProviderRepository,
    private val ttsSettingsStore: TtsSettingsStore,
    private val imageApiSettingsStore: ImageApiSettingsStore,
    private val apiKeyStore: ApiKeyStore,
    private val httpClient: OkHttpClient
) {
    /** Resolves the model assigned to [role] or throws a guided [AiClientException]. */
    suspend fun forRole(role: ModelRole): ResolvedChatClient {
        val (provider, model) = resolve(role)
        return forModel(provider, model)
    }

    fun forModel(provider: AiProviderEntity, model: AiModelEntity): ResolvedChatClient {
        val apiKey = providerRepository.apiKeyFor(provider)
            ?.takeIf(String::isNotBlank)
            ?: throw AiClientException.MissingKey(provider.name)
        val mergedExtraJson = mergeExtraJson(provider.extraJson, model.extraJson)
        val route = ProviderProtocolPolicy.route(provider, model)
        val dialect = when (route) {
            is ModelProtocolRoute.Chat -> route.dialect
            is ModelProtocolRoute.Embedding -> route.dialect
            is ModelProtocolRoute.Unsupported -> throw AiClientException.Unsupported(route.reason)
            ModelProtocolRoute.Media -> throw AiClientException.Unsupported(
                "媒体模型请通过专用客户端调用"
            )
        }
        val chatEndpoint = model.endpointPath.takeIf { route is ModelProtocolRoute.Chat }.orEmpty()
        val embeddingEndpoint = model.endpointPath
            .takeIf { route is ModelProtocolRoute.Embedding }
            .orEmpty()
        val client = when (dialect) {
            ApiDialect.OPENAI -> OpenAiCompatClient(
                provider.baseUrl,
                apiKey,
                model.modelName,
                httpClient,
                chatEndpointPath = chatEndpoint,
                embeddingEndpointPath = embeddingEndpoint,
                extraJson = mergedExtraJson
            )
            ApiDialect.OPENAI_RESPONSES -> OpenAiResponsesClient(
                provider.baseUrl,
                apiKey,
                model.modelName,
                httpClient,
                responsesEndpointPath = chatEndpoint,
                embeddingEndpointPath = embeddingEndpoint,
                extraJson = mergedExtraJson
            )
            ApiDialect.CLAUDE -> ClaudeClient(
                provider.baseUrl,
                apiKey,
                model.modelName,
                httpClient,
                endpointPath = chatEndpoint
            )
            ApiDialect.GEMINI -> GeminiClient(provider.baseUrl, apiKey, model.modelName, httpClient)
        }
        return ResolvedChatClient(
            client = client,
            options = ChatOptions.fromExtraJson(mergedExtraJson),
            provider = provider,
            modelName = model.modelName
        )
    }

    /** OpenAI-compatible TTS / image client；独立 TTS 配置存在时优先于模型分配。 */
    suspend fun mediaForRole(role: ModelRole): ResolvedMediaClient {
        require(role == ModelRole.TTS || role == ModelRole.IMAGE) { "仅支持 TTS 或 IMAGE 角色" }
        if (role == ModelRole.TTS) {
            standaloneTtsClient()?.let { return it }
        }
        val (provider, model) = resolve(role)
        when (val route = ProviderProtocolPolicy.route(provider, model)) {
            ModelProtocolRoute.Media -> Unit
            is ModelProtocolRoute.Unsupported -> throw AiClientException.Unsupported(route.reason)
            else -> throw AiClientException.Unsupported("模型能力不是媒体类型")
        }
        val apiKey = providerRepository.apiKeyFor(provider)
            ?.takeIf(String::isNotBlank)
            ?: throw AiClientException.MissingKey(provider.name)
        return ResolvedMediaClient(
            client = OpenAiMediaClient(provider, model, apiKey, httpClient),
            provider = provider,
            model = model
        )
    }

    /** 生图出口：独立生图配置优先（含 NovelAI），否则回落到「模型分配」的生图模型。 */
    suspend fun imageGeneration(): ResolvedImageGeneration {
        val config = imageApiSettingsStore.current()
        if (config.configured) {
            val apiKey = apiKeyStore.get(ImageApiSettingsStore.API_KEY_ALIAS)
                ?.takeIf(String::isNotBlank)
                ?: throw AiClientException.MissingKey("生图 API")
            if (config.provider == ImageApiProvider.NOVELAI) {
                return ResolvedImageGeneration(
                    client = NovelAiImageClient(
                        baseUrl = config.baseUrl,
                        apiKey = apiKey,
                        model = config.model,
                        defaultSize = config.effectiveSize,
                        positivePrompt = config.positivePrompt,
                        negativePrompt = config.negativePrompt,
                        sampler = config.sampler,
                        steps = config.steps,
                        scale = config.scale,
                        httpClient = httpClient
                    ),
                    label = "NovelAI ${config.model}"
                )
            }
            val useChat = config.provider == ImageApiProvider.OPENAI_CHAT
            val provider = AiProviderEntity(
                id = STANDALONE_ENTITY_ID,
                name = "生图 API",
                baseUrl = config.baseUrl,
                apiKeyAlias = ImageApiSettingsStore.API_KEY_ALIAS,
                type = AiProviderType.IMAGE,
                apiFormat = "OPENAI",
                adapter = AiProviderAdapter.CUSTOM,
                createdAt = 0
            )
            val model = AiModelEntity(
                id = STANDALONE_ENTITY_ID,
                providerId = STANDALONE_ENTITY_ID,
                modelName = config.model,
                type = AiModelType.IMAGE,
                endpointPath = if (useChat) "/chat/completions" else "/images/generations",
                extraJson = if (useChat) {
                    "{}"
                } else {
                    """{"body":{"size":"${config.effectiveSize}"}}"""
                },
                createdAt = 0
            )
            return ResolvedImageGeneration(
                client = OpenAiMediaClient(provider, model, apiKey, httpClient),
                label = config.model
            )
        }
        val resolved = mediaForRole(ModelRole.IMAGE)
        return ResolvedImageGeneration(
            client = resolved.client,
            label = resolved.model.modelName
        )
    }

    /** 「语音朗读」二级页里的独立 TTS API；Base URL + 模型齐了才算配置生效。 */
    private suspend fun standaloneTtsClient(): ResolvedMediaClient? {
        val settings = ttsSettingsStore.current()
        if (!settings.aiApiConfigured) return null
        val apiKey = apiKeyStore.get(TtsSettingsStore.API_KEY_ALIAS)
            ?.takeIf(String::isNotBlank)
            ?: throw AiClientException.MissingKey("语音朗读 API")
        val isMiniMax = settings.aiIsMiniMax
        val provider = AiProviderEntity(
            id = STANDALONE_ENTITY_ID,
            name = "语音朗读 API",
            baseUrl = settings.aiBaseUrl,
            apiKeyAlias = TtsSettingsStore.API_KEY_ALIAS,
            type = AiProviderType.TTS,
            apiFormat = "OPENAI",
            adapter = if (isMiniMax) AiProviderAdapter.MINIMAX else AiProviderAdapter.CUSTOM,
            createdAt = 0
        )
        val model = AiModelEntity(
            id = STANDALONE_ENTITY_ID,
            providerId = STANDALONE_ENTITY_ID,
            modelName = settings.aiModel,
            type = AiModelType.TTS,
            endpointPath = if (isMiniMax) "/t2a_v2" else "/audio/speech",
            extraJson = settings.aiGroupId.takeIf(String::isNotBlank)
                ?.let { """{"body":{"group_id":"$it"}}""" }
                ?: "{}",
            createdAt = 0
        )
        return ResolvedMediaClient(
            client = OpenAiMediaClient(provider, model, apiKey, httpClient),
            provider = provider,
            model = model
        )
    }

    private suspend fun resolve(role: ModelRole): Pair<AiProviderEntity, AiModelEntity> {
        val modelId = providerDao.getAssignment(role)?.modelId
            ?: throw AiClientException.NotConfigured(role.label())
        val model = providerDao.getModel(modelId)
            ?: throw AiClientException.NotConfigured(role.label())
        if (model.type != role.requiredType()) {
            throw AiClientException.NotConfigured(role.label())
        }
        val provider = providerDao.getProvider(model.providerId)
            ?: throw AiClientException.NotConfigured(role.label())
        return provider to model
    }

    private fun ModelRole.requiredType(): AiModelType = when (this) {
        ModelRole.CHAT, ModelRole.CHEAP, ModelRole.SUGGESTION -> AiModelType.CHAT
        ModelRole.EMBEDDING -> AiModelType.EMBEDDING
        ModelRole.TTS -> AiModelType.TTS
        ModelRole.IMAGE -> AiModelType.IMAGE
    }

    private fun ModelRole.label(): String = when (this) {
        ModelRole.CHAT -> "对话模型"
        ModelRole.CHEAP -> "廉价批量模型"
        ModelRole.SUGGESTION -> "建议回复模型"
        ModelRole.EMBEDDING -> "Embedding 模型"
        ModelRole.TTS -> "TTS 模型"
        ModelRole.IMAGE -> "生图模型"
    }

    private companion object {
        /** 独立配置合成的临时实体 ID；不落库，仅用于语音缓存 key 与错误提示。 */
        const val STANDALONE_ENTITY_ID = -1L
    }
}
