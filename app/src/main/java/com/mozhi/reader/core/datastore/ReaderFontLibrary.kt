package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class ReaderFontAsset(
    val id: String,
    val displayName: String,
    val filePath: String,
    val originalFileName: String = "",
    val importedAt: Long = 0L
)

object ReaderFontLibraryCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ReaderFontAsset.serializer())

    fun encode(fonts: List<ReaderFontAsset>): String = json.encodeToString(serializer, fonts)

    fun decode(raw: String?): List<ReaderFontAsset> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }
            .getOrDefault(emptyList())
            .filter { it.id.isNotBlank() && it.filePath.isNotBlank() }
            .distinctBy(ReaderFontAsset::id)
    }

    /** 将旧版单字体无损映射进字体库；稳定 ID 保证每次读取不会产生重复项。 */
    fun includeLegacy(
        fonts: List<ReaderFontAsset>,
        legacyPath: String?,
        legacyName: String?
    ): List<ReaderFontAsset> {
        val path = legacyPath?.takeIf(String::isNotBlank) ?: return fonts
        if (fonts.any { it.filePath == path }) return fonts
        return fonts + ReaderFontAsset(
            id = legacyId(path),
            displayName = legacyName?.takeIf(String::isNotBlank) ?: "已导入字体",
            filePath = path,
            originalFileName = "",
            importedAt = 0L
        )
    }

    fun legacyId(path: String): String = "legacy-${path.hashCode().toUInt().toString(16)}"
}
