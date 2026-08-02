package com.mozhi.reader.ai.client

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 只解析各类端点共用的 headers/body 透传部分。 */
internal data class RequestOverrides(
    val headers: Map<String, String> = emptyMap(),
    val body: JsonObject = JsonObject(emptyMap())
) {
    companion object {
        fun parse(extraJson: String?): RequestOverrides {
            val root = extraJson.toJsonObjectOrEmpty()
            val headers = (root["headers"] as? JsonObject)
                ?.mapNotNull { (key, value) ->
                    (value as? JsonPrimitive)?.content?.let { key to it }
                }
                ?.toMap()
                .orEmpty()
            return RequestOverrides(headers, root["body"] as? JsonObject ?: JsonObject(emptyMap()))
        }
    }
}

/** Provider 为默认、模型为覆盖；headers/body 两层做浅合并，其余键按模型覆盖。 */
internal fun mergeExtraJson(providerJson: String?, modelJson: String?): String {
    val provider = providerJson.toJsonObjectOrEmpty()
    val model = modelJson.toJsonObjectOrEmpty()
    val merged = provider.toMutableMap().apply { putAll(model) }
    listOf("headers", "body").forEach { key ->
        val providerPart = provider[key] as? JsonObject
        val modelPart = model[key] as? JsonObject
        if (providerPart != null || modelPart != null) {
            merged[key] = JsonObject(
                providerPart.orEmpty().toMutableMap().apply { putAll(modelPart.orEmpty()) }
            )
        }
    }
    return JsonObject(merged).toString()
}

private fun String?.toJsonObjectOrEmpty(): JsonObject =
    if (isNullOrBlank()) {
        JsonObject(emptyMap())
    } else {
        runCatching { AiJson.parseToJsonElement(this).jsonObject }
            .getOrDefault(JsonObject(emptyMap()))
    }
