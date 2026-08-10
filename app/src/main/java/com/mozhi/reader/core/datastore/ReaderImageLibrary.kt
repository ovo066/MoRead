package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ReaderImageAsset(
    val id: String,
    val displayName: String,
    val filePath: String,
    val originalFileName: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val importedAt: Long = 0L
)

object ReaderImageLibraryCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ReaderImageAsset.serializer())

    fun encode(images: List<ReaderImageAsset>): String = json.encodeToString(serializer, images)

    fun decode(raw: String?): List<ReaderImageAsset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.filePath.isNotBlank() }
            .distinctBy(ReaderImageAsset::id)
    }

    /** 将旧版单背景无损显示在图片库中；稳定 ID 避免每次读取生成重复项。 */
    fun includeLegacyBackground(
        images: List<ReaderImageAsset>,
        legacyPath: String?
    ): List<ReaderImageAsset> {
        val path = legacyPath?.takeIf(String::isNotBlank) ?: return images
        if (images.any { it.filePath == path }) return images
        return images + ReaderImageAsset(
            id = legacyId(path),
            displayName = "原有阅读背景",
            filePath = path
        )
    }

    fun legacyId(path: String): String = "legacy-image-${path.hashCode().toUInt().toString(16)}"
}
