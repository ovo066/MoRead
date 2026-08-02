package com.mozhi.reader.ai.provider

import com.mozhi.reader.ai.client.ApiDialect
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.core.database.entity.AiProviderAdapter
import com.mozhi.reader.core.database.entity.AiProviderEntity
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface ConnectionTestResult {
    data object Success : ConnectionTestResult
    data class Failure(val message: String) : ConnectionTestResult
}

/** Pings the provider catalog；OpenRouter 测试不受其默认聊天协议影响。 */
@Singleton
class ProviderConnectionTester @Inject constructor(
    private val client: OkHttpClient
) {
    suspend fun test(
        provider: AiProviderEntity,
        apiKey: String?
    ): ConnectionTestResult = withContext(Dispatchers.IO) {
        if (apiKey.isNullOrBlank()) {
            return@withContext ConnectionTestResult.Failure("尚未保存 API Key")
        }

        val base = provider.baseUrl.trimEnd('/')
        val requestBuilder = if (provider.adapter == AiProviderAdapter.OPENROUTER) {
            Request.Builder()
                .url("$base/models?output_modalities=all&limit=1")
                .header("Authorization", "Bearer $apiKey")
        } else when (ProviderProtocolPolicy.providerChatDialect(provider)) {
            ApiDialect.OPENAI, ApiDialect.OPENAI_RESPONSES -> Request.Builder()
                .url("$base/models")
                .header("Authorization", "Bearer $apiKey")

            ApiDialect.CLAUDE -> Request.Builder()
                .url("${base.removeSuffix("/v1")}/v1/models")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")

            ApiDialect.GEMINI -> Request.Builder()
                .url("${base.removeSuffix("/v1beta").removeSuffix("/v1")}/v1beta/models")
                .header("x-goog-api-key", apiKey)
        }
            .header("Accept", "application/json")
            .apply {
                ChatOptions.fromExtraJson(provider.extraJson).extraHeaders.forEach { (key, value) ->
                    header(key, value)
                }
            }
            .get()
        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ConnectionTestResult.Success
                    response.code == 401 || response.code == 403 ->
                        ConnectionTestResult.Failure("API Key 无效或无权限")
                    response.code == 429 -> ConnectionTestResult.Failure("请求过于频繁，请稍后再试")
                    else -> ConnectionTestResult.Failure("连接失败（HTTP ${response.code}）")
                }
            }
        } catch (_: IOException) {
            ConnectionTestResult.Failure("网络不可用或服务地址无法访问")
        } catch (_: IllegalArgumentException) {
            ConnectionTestResult.Failure("Base URL 格式不正确")
        }
    }
}
