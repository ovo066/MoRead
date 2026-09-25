package com.mozhi.reader.core.speech

import java.util.Base64

/** A role keeps the engine and exact Android Voice name together, even if the default changes. */
data class SystemTtsVoiceInfo(
    val enginePackage: String,
    val name: String,
    val languageTag: String,
    val requiresNetwork: Boolean = false
) {
    val id: String get() = PREFIX + encode(enginePackage) + ":" + encode(name)
    val description: String get() = listOf(languageTag, if (requiresNetwork) "需联网" else "本地").filter(String::isNotBlank).joinToString(" · ")

    companion object {
        private const val PREFIX = "system-voice:"
        private fun encode(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        fun decode(id: String): Pair<String, String>? = runCatching {
            if (!id.startsWith(PREFIX)) return null
            val parts = id.removePrefix(PREFIX).split(':')
            if (parts.size != 2) return null
            val engine = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
            val name = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
            if (engine.isBlank() || name.isBlank()) null else engine to name
        }.getOrNull()
    }
}

fun TtsSettings.withSystemVoiceId(id: String?): TtsSettings {
    val voice = id?.let(SystemTtsVoiceInfo::decode) ?: return this
    return copy(systemEnginePackage = voice.first, systemVoiceName = voice.second)
}
