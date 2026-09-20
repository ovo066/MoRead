package com.mozhi.reader.ai.client

import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * Adapt optional tuning only after a server rejects it. This wraps HTTP validation, before any
 * SSE/text/tool output can be consumed, so retrying cannot replay a partially generated turn.
 * Scope is one request: capabilities can depend on both model and reasoning configuration.
 */
internal class ResponsesParameterCompatibility : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        var payload = request.body?.let { body ->
            runCatching { val buffer = Buffer(); body.writeTo(buffer); AiJson.parseToJsonElement(buffer.readUtf8()).jsonObject }.getOrNull()
        } ?: return chain.proceed(request)
        repeat(MAX_REMOVALS) {
            val response = chain.proceed(request)
            val error = if (response.code == 400 || response.code == 422) {
                runCatching { response.peekBody(64 * 1024L).string() }.getOrNull()
            } else null
            val adjusted = error?.let { removeRejectedResponsesParameter(payload, it) }
            if (adjusted == null || chain.call().isCanceled()) return response
            response.close()
            payload = adjusted
            request = request.newBuilder().post(payload.toString().toRequestBody(JSON_MEDIA_TYPE)).build()
        }
        return chain.proceed(request)
    }

    private companion object { const val MAX_REMOVALS = 6 }
}

internal fun removeRejectedResponsesParameter(payload: JsonObject, errorBody: String): JsonObject? {
    val root = runCatching { AiJson.parseToJsonElement(errorBody).jsonObject }.getOrNull() ?: return null
    val error = root["error"] as? JsonObject ?: return null
    fun string(key: String) = (error[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    val message = string("message")
    val code = string("code")
    val unsupported = code in setOf("unsupported_parameter", "unknown_parameter", "unsupported_value") ||
        Regex("unsupported (parameter|value)|unknown parameter|unrecognized (request )?(argument|parameter)|not supported|does not support", RegexOption.IGNORE_CASE).containsMatchIn(message)
    if (!unsupported) return null
    val parameter = string("param").ifBlank {
        Regex("(?:parameter|argument|value)\\s*:?\\s*['\"`]([a-z_][a-z_0-9.]*)['\"`]", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.get(1).orEmpty()
    }
    // Never silently discard prompt/context, tools, structured-output schema, token budgets,
    // routing, stream mode, or store:false. Unsupported essentials remain a visible API error.
    if (parameter !in OPTIONAL_RESPONSES_TUNING) return null
    fun remove(objectValue: JsonObject, path: List<String>): JsonObject? {
        val key = path.first()
        if (key !in objectValue) return null
        val next = objectValue.toMutableMap()
        if (path.size == 1) next.remove(key)
        else {
            val child = objectValue[key] as? JsonObject ?: return null
            val adjusted = remove(child, path.drop(1)) ?: return null
            if (adjusted.isEmpty()) next.remove(key) else next[key] = adjusted
        }
        return JsonObject(next)
    }
    return remove(payload, parameter.split('.'))
}

private val OPTIONAL_RESPONSES_TUNING = setOf(
    "temperature", "top_p", "top_logprobs", "logprobs", "presence_penalty", "frequency_penalty", "seed",
    "reasoning.effort", "reasoning.summary", "text.verbosity", "verbosity"
)
