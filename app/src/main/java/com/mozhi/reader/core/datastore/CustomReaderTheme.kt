package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 用户自定义阅读主题。除配色外，同时保存字体、字号、排版与背景，便于在日间/夜间方案间
 * 一次切换整套阅读体验。新增字段均有默认值，旧版本三色主题可无损读取。
 */
@Serializable
data class CustomReaderTheme(
    val id: Long,
    val name: String,
    val backgroundArgb: Int,
    val textArgb: Int,
    val accentArgb: Int,
    val isDark: Boolean? = null,
    val font: ReaderFont = ReaderFont.SYSTEM,
    val customFontId: String? = null,
    val customFontPath: String? = null,
    val customFontName: String? = null,
    val fontScale: Float = 1f,
    val fontWeight: Int = 400,
    val lineHeight: Float = 1.55f,
    val pageMarginLeft: Float = 1f,
    val pageMarginRight: Float = 1f,
    val pageMarginTop: Float = 0f,
    val pageMarginBottom: Float = 0f,
    val letterSpacingEm: Float = 0f,
    val paragraphSpacingEm: Float = 0.55f,
    val firstLineIndentEm: Float = 2f,
    val titleScale: Float = 1.35f,
    val titleTopSpacing: Float = 0.4f,
    val titleBottomSpacing: Float = 1f,
    val headerMarginTop: Float = 0f,
    val footerMarginBottom: Float = 0f,
    val textJustification: Boolean = true,
    val showHeader: Boolean = true,
    val showFooter: Boolean = true,
    val backgroundImageId: String? = null,
    val backgroundImagePath: String? = null,
    val backgroundImageOpacity: Float = 0.28f
)

object CustomReaderThemeCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(themes: List<CustomReaderTheme>): String = json.encodeToString(themes)

    /**
     * 逐项解码，避免一个损坏或未来版本的主题让整份主题列表消失；新增字段均由默认值
     * 承接旧版三色主题。
     */
    fun decode(raw: String?): List<CustomReaderTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        val elements = runCatching { json.parseToJsonElement(raw) as? JsonArray }
            .getOrNull() ?: return emptyList()
        return elements.mapNotNull { element ->
            runCatching { json.decodeFromJsonElement<CustomReaderTheme>(element) }.getOrNull()
        }.distinctBy(CustomReaderTheme::id)
    }
}
