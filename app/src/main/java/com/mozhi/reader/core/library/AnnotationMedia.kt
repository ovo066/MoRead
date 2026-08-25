package com.mozhi.reader.core.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AnnotationMedia(
    val audioPath: String? = null,
    val illustrationId: Long? = null
) {
    val isEmpty: Boolean get() = audioPath.isNullOrBlank() && illustrationId == null

    fun encode(): String = if (isEmpty) "{}" else codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun decode(raw: String?): AnnotationMedia = raw
            ?.takeIf(String::isNotBlank)
            ?.let { runCatching { codec.decodeFromString(serializer(), it) }.getOrNull() }
            ?: AnnotationMedia()
    }
}
