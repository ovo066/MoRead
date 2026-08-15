package com.mozhi.reader.core.database.entity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 气泡形状语汇；每种都影响圆角、描边与底色透明度三件事，不是单纯换个圆角值。 */
enum class ChatBubbleShape(val wire: String, val label: String) {
    /** 默认：大圆角实底，与阅读页强调色同源。 */
    ROUNDED("ROUNDED", "圆角"),
    /** 描边：透明底 + 一圈细线，背景图能透出来。 */
    OUTLINED("OUTLINED", "描边"),
    /** 纸片：小圆角、几乎不透明，像贴在页面上的便签。 */
    PAPER("PAPER", "纸片"),
    /** 玻璃：半透明磨砂，配背景图最好看，但纯色背景下对比度最弱。 */
    GLASS("GLASS", "玻璃");

    companion object {
        fun fromWire(value: String?): ChatBubbleShape =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: ROUNDED
    }
}

/**
 * 一个角色的聊天外观。整体存进 `personas.chatAppearanceJson` 单列
 * （与 worldBookJson 同一先例）：这些字段会随设计继续长，一个个加列迟早要连开迁移。
 *
 * 全部字段都可以「不设」——空值一律表示跟随阅读主题，这样默认状态下聊天页与阅读页
 * 是同一套配色，用户不必先配一遍才能用。
 */
@Serializable
data class PersonaChatAppearance(
    /** 引用共享图片库（ReaderImageAsset.id）；null = 不铺背景图。 */
    @SerialName("background_image_id") val backgroundImageId: String? = null,
    /** 背景图之上的蒙版强度：0 = 原图直出，1 = 完全被主题底色盖住。 */
    @SerialName("background_dim") val backgroundDim: Float = DEFAULT_DIM,
    /** 引用共享字体库（ReaderFontAsset.id）；null = 跟随应用字体。 */
    @SerialName("font_id") val fontId: String? = null,
    /** 相对默认正文字号的倍率。 */
    @SerialName("font_scale") val fontScale: Float = 1f,
    @SerialName("bubble_shape") val bubbleShape: String = ChatBubbleShape.ROUNDED.wire,
    /** 角色气泡底色 ARGB；null = 跟随主题。 */
    @SerialName("assistant_color") val assistantColorArgb: Int? = null,
    /** 用户气泡底色 ARGB；null = 跟随主题强调色。 */
    @SerialName("user_color") val userColorArgb: Int? = null
) {
    val shape: ChatBubbleShape get() = ChatBubbleShape.fromWire(bubbleShape)

    /** 是否与默认外观完全一致；界面据此决定要不要显示「已自定义」。 */
    val isDefault: Boolean get() = this == DEFAULT

    fun sanitized(): PersonaChatAppearance = copy(
        backgroundDim = backgroundDim.coerceIn(0f, 1f),
        fontScale = fontScale.coerceIn(MIN_FONT_SCALE, MAX_FONT_SCALE),
        bubbleShape = shape.wire
    )

    companion object {
        val DEFAULT = PersonaChatAppearance()
        const val DEFAULT_DIM = 0.55f
        const val MIN_FONT_SCALE = 0.8f
        const val MAX_FONT_SCALE = 1.6f
    }
}

/** 编解码器；坏 JSON 一律降级为默认外观——聊天页不该因为一段脏配置打不开。 */
object PersonaChatAppearanceCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(appearance: PersonaChatAppearance): String =
        json.encodeToString(PersonaChatAppearance.serializer(), appearance.sanitized())

    fun decode(raw: String?): PersonaChatAppearance {
        if (raw.isNullOrBlank()) return PersonaChatAppearance.DEFAULT
        return runCatching {
            json.decodeFromString(PersonaChatAppearance.serializer(), raw).sanitized()
        }.getOrDefault(PersonaChatAppearance.DEFAULT)
    }
}

fun PersonaEntity.chatAppearance(): PersonaChatAppearance =
    PersonaChatAppearanceCodec.decode(chatAppearanceJson)
