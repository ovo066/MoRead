package com.mozhi.reader.ai.provider

import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.core.database.entity.AiModelType
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

data class CatalogModel(
    val modelName: String,
    val type: AiModelType,
    val endpointPath: String = ""
) {
    fun toDraft(): AiModelDraft = AiModelDraft(
        modelName = modelName,
        type = type,
        endpointPath = endpointPath
    )

    val key: String get() = "${type.name}:$modelName"
}

sealed interface ModelCatalogResult {
    data class Success(val models: List<CatalogModel>) : ModelCatalogResult
    data class Failure(val message: String) : ModelCatalogResult
}

/**
 * 拉取模型目录。普通 Provider 只访问其方言标准目录并默认归为对话模型；OpenRouter
 * 内置适配额外聚合 `/models?output_modalities=all`、`/embeddings/models` 与
 * `/images/models`，按返回模态自动标注 CHAT / EMBEDDING / TTS / IMAGE。
 */
@Singleton
class ModelCatalogFetcher @Inject constructor(
    private val client: OkHttpClient
) {
    @Serializable
    private data class OpenAiCatalog(val data: List<Entry> = emptyList()) {
        @Serializable
        data class Entry(
            val id: String = "",
            val architecture: Architecture? = null
        )

        @Serializable
        data class Architecture(
            @SerialName("output_modalities")
            val outputModalities: List<String> = emptyList()
        )
    }

    @Serializable
    private data class GeminiCatalog(val models: List<Entry> = emptyList()) {
        @Serializable
        data class Entry(val name: String = "")
    }

    suspend fun fetch(
        provider: AiProviderEntity,
        apiKey: String?
    ): ModelCatalogResult = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            return@withContext ModelCatalogResult.Failure("尚未保存 API Key")
        }
        try {
            if (provider.adapter == AiProviderAdapter.OPENROUTER) {
                fetchOpenRouter(provider, apiKey)
            } else {
                fetchStandard(provider, apiKey)
            }
        } catch (_: IOException) {
            ModelCatalogResult.Failure("网络不可用或服务地址无法访问")
        } catch (_: IllegalArgumentException) {
            ModelCatalogResult.Failure("Base URL 格式不正确")
        }
    }

    private fun fetchOpenRouter(
        provider: AiProviderEntity,
        apiKey: String
    ): ModelCatalogResult {
        val base = provider.baseUrl.trimEnd('/')
        val requests = listOf(
            CatalogRequest(
                url = "$base/models?output_modalities=all&limit=500",
                forcedType = null
            ),
            CatalogRequest(
                url = "$base/embeddings/models",
                forcedType = AiModelType.EMBEDDING
            ),
            CatalogRequest(
                url = "$base/images/models",
                forcedType = AiModelType.IMAGE
            )
        )
        val merged = linkedMapOf<String, CatalogModel>()
        var firstHttpFailure: Int? = null
        requests.forEach { spec ->
            val result = fetchOpenAiCatalog(provider, apiKey, spec)
            if (result.models != null) {
                result.models.forEach { merged[it.key] = it }
            } else if (firstHttpFailure == null) {
                firstHttpFailure = result.httpCode
            }
        }
        return catalogResult(merged.values.toList(), firstHttpFailure)
    }

    private fun fetchStandard(
        provider: AiProviderEntity,
        apiKey: String
    ): ModelCatalogResult {
        val base = provider.baseUrl.trimEnd('/')
        val dialect = ApiDialect.fromWire(provider.apiFormat)
        if (dialect == ApiDialect.GEMINI) {
            val request = requestBuilder(provider)
                .url("${base.removeSuffix("/v1beta").removeSuffix("/v1")}/v1beta/models?pageSize=100")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ModelCatalogResult.Failure("拉取失败（HTTP ${response.code}）")
                }
                val names = runCatching {
                    AiJson.decodeFromString(GeminiCatalog.serializer(), response.body.string())
                }.getOrNull()?.models.orEmpty().map { it.name.removePrefix("models/") }
                return catalogResult(
                    names.map { CatalogModel(it, AiModelType.CHAT) },
                    httpFailure = null
                )
            }
        }

        val spec = when (dialect) {
            ApiDialect.CLAUDE -> CatalogRequest(
                url = "${base.removeSuffix("/v1")}/v1/models?limit=100",
                forcedType = AiModelType.CHAT,
                anthropicAuth = true
            )
            else -> CatalogRequest("$base/models", AiModelType.CHAT)
        }
        val result = fetchOpenAiCatalog(provider, apiKey, spec)
        return catalogResult(result.models.orEmpty(), result.httpCode)
    }

    private fun fetchOpenAiCatalog(
        provider: AiProviderEntity,
        apiKey: String,
        spec: CatalogRequest
    ): FetchResult {
        val builder = requestBuilder(provider).url(spec.url)
        if (spec.anthropicAuth) {
            builder.header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
        } else {
            builder.header("Authorization", "Bearer $apiKey")
        }
        client.newCall(builder.get().build()).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) return FetchResult(httpCode = response.code)
            val entries = runCatching {
                AiJson.decodeFromString(OpenAiCatalog.serializer(), body).data
            }.getOrNull() ?: return FetchResult(models = emptyList())
            return FetchResult(
                models = entries.mapNotNull { entry ->
                    entry.id.takeIf(String::isNotBlank)?.let { id ->
                        val type = spec.forcedType ?: entry.inferredType()
                        CatalogModel(id, type, endpointFor(type))
                    }
                }
            )
        }
    }

    private fun requestBuilder(provider: AiProviderEntity): Request.Builder =
        Request.Builder()
            .header("Accept", "application/json")
            .apply {
                ChatOptions.fromExtraJson(provider.extraJson).extraHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }

    private fun OpenAiCatalog.Entry.inferredType(): AiModelType {
        val outputs = architecture?.outputModalities.orEmpty().map(String::lowercase)
        return when {
            "speech" in outputs -> AiModelType.TTS
            "image" in outputs -> AiModelType.IMAGE
            else -> AiModelType.CHAT
        }
    }

    private fun endpointFor(type: AiModelType): String = when (type) {
        AiModelType.CHAT -> ""
        AiModelType.EMBEDDING -> "/embeddings"
        AiModelType.TTS -> "/audio/speech"
        AiModelType.IMAGE -> "/images"
    }

    private fun catalogResult(
        models: List<CatalogModel>,
        httpFailure: Int?
    ): ModelCatalogResult {
        val clean = models
            .filter { it.modelName.isNotBlank() }
            .distinctBy(CatalogModel::key)
            .sortedWith(compareBy(CatalogModel::type, CatalogModel::modelName))
        return when {
            clean.isNotEmpty() -> ModelCatalogResult.Success(clean)
            httpFailure != null -> ModelCatalogResult.Failure("拉取失败（HTTP $httpFailure）")
            else -> ModelCatalogResult.Failure("响应无法解析或没有模型")
        }
    }

    private data class CatalogRequest(
        val url: String,
        val forcedType: AiModelType?,
        val anthropicAuth: Boolean = false
    )

    private data class FetchResult(
        val models: List<CatalogModel>? = null,
        val httpCode: Int? = null
    )
}
