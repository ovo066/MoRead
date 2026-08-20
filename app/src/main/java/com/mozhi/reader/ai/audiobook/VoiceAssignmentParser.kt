package com.mozhi.reader.ai.audiobook

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object VoiceAssignmentParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, allowedVoiceIds: Set<String>): Map<String, String> {
        val root = runCatching { json.parseToJsonElement(raw.trim()) }.getOrNull() ?: return emptyMap()
        val pairs = when (root) {
            is JsonObject -> {
                val nested = root["voiceAssignments"]
                if (nested is JsonObject) nested.entries.mapNotNull { (role, value) ->
                    value.jsonPrimitive.contentOrNull?.let { role to it }
                } else root.entries.mapNotNull { (role, value) ->
                    value.jsonPrimitive.contentOrNull?.let { role to it }
                }
            }
            is JsonArray -> root.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val voice = obj["voiceId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                role to voice
            }
            else -> emptyList()
        }
        return pairs.filter { (role, voice) -> role.isNotBlank() && voice in allowedVoiceIds }.toMap()
    }
}
