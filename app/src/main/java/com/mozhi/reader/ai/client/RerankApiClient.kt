package com.mozhi.reader.ai.client

import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Cohere/Jina-compatible rerank wire format, also used by self-hosted and aggregator APIs. */
class RerankApiClient(
    baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: OkHttpClient,
    endpointPath: String = "",
    extraJson: String = "{}"
) {
    private val url = "${normalizeBase(baseUrl)}/${endpointPath.ifBlank { "/rerank" }.trimStart('/')}"
    private val client = httpClient.newBuilder().callTimeout(5, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false).build()
    private val overrides = RequestOverrides.parse(extraJson)

    suspend fun rank(query: String, documents: List<String>): List<Int> {
        require(query.isNotBlank() && query.length <= 512)
        require(documents.size in 2..24 && documents.all { it.isNotBlank() && it.length <= 800 })
        require(documents.sumOf(String::length) <= 12_000)
        // User overrides may customize the API, but never replace the bounded source payload.
        val payload = buildJsonObject {
            overrides.body.forEach { (key, value) -> put(key, value) }
            put("model", model)
            put("query", query)
            put("documents", JsonArray(documents.map(::JsonPrimitive)))
            put("top_n", documents.size)
            put("return_documents", false)
        }
        val request = Request.Builder().url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply { overrides.headers.forEach { (key, value) -> header(key, value) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
        return parseRanking(execute(client, request), documents.size)
    }

    internal companion object {
        fun parseRanking(body: String, count: Int): List<Int> {
            fun malformed(): Nothing = throw AiClientException.Malformed("重排响应必须为全部候选返回唯一编号与有效分数")
            if (body.length > 64_000) malformed()
            val root = runCatching { AiJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: malformed()
            val results = root["results"] as? JsonArray ?: malformed()
            if (results.size != count) malformed()
            val scored = results.map { result ->
                val item = result as? JsonObject ?: malformed()
                val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: malformed()
                val score = (item["relevance_score"] as? JsonPrimitive)?.doubleOrNull ?: malformed()
                if (index !in 0 until count || !score.isFinite()) malformed()
                index to score
            }
            if (scored.map { it.first }.distinct().size != count) malformed()
            return scored.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first }).map { it.first }
        }
    }
}
