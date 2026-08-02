package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 用户自定义阅读主题：背景 / 正文 / 强调三色一套，存 DataStore（JSON 数组）。
 * 玻璃层、弱化色等派生色一律由调色板函数从这三色现算，不落盘。
 */
@Serializable
data class CustomReaderTheme(
    val id: Long,
    val name: String,
    val backgroundArgb: Int,
    val textArgb: Int,
    val accentArgb: Int
)

object CustomReaderThemeCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(CustomReaderTheme.serializer())

    fun encode(themes: List<CustomReaderTheme>): String =
        json.encodeToString(serializer, themes)

    /** 损坏/旧版本内容一律回落为空列表，不让阅读器因偏好数据坏掉而崩。 */
    fun decode(raw: String?): List<CustomReaderTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
