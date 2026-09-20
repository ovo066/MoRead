package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ReaderTitleAlignment { START, CENTER, END }

/** Only the reader's plain-text chapter heading uses this style; publisher EPUB boxes own theirs. */
@Serializable
data class ReaderTitleStyle(
    val font: ReaderSyntaxFont = ReaderSyntaxFont.INHERIT,
    val fontAssetId: String? = null,
    val colorArgb: Int? = null,
    val backgroundArgb: Int? = null,
    val alignment: ReaderTitleAlignment = ReaderTitleAlignment.START,
    val bold: Boolean = true,
    val borderWidthEm: Float = 0f,
    val borderColorArgb: Int? = null,
    val cornerRadiusEm: Float = 0f,
    val paddingEm: Float = 0f,
    val insetEm: Float = 0f,
    val imageAssetId: String? = null,
    val css: String = "",
    /** Stable reference to the shared style library; the remaining fields preserve a fallback. */
    val presetId: String? = null,
    @kotlinx.serialization.Transient val paint: ReaderStylePaint = ReaderStylePaint()
) {
    fun resolved(): ReaderTitleStyle {
        val declarations = ReaderStyleCss.parse(css, title = true)
        return copy(
            paint = declarations.paint,
            imageAssetId = if (declarations.paint.backgroundImageSpecified) declarations.paint.backgroundImageId else imageAssetId,
            font = declarations.font ?: font,
            fontAssetId = declarations.fontAssetId ?: fontAssetId,
            colorArgb = declarations.color ?: colorArgb,
            backgroundArgb = if (declarations.clipText) null else declarations.background ?: backgroundArgb,
            alignment = declarations.alignment ?: alignment,
            bold = declarations.bold ?: bold,
            borderWidthEm = (declarations.borderWidth ?: borderWidthEm).coerceIn(0f, 0.5f),
            borderColorArgb = declarations.borderColor ?: borderColorArgb,
            cornerRadiusEm = (declarations.radius ?: cornerRadiusEm).coerceIn(0f, 3f),
            paddingEm = (declarations.padding ?: paddingEm).coerceIn(0f, 3f),
            insetEm = (declarations.inset ?: insetEm).coerceIn(0f, 8f)
        )
    }
}

@Serializable
data class ReaderTitleStylePreset(val id: String, val name: String, val style: ReaderTitleStyle)

object ReaderTitleStylePresetCodec {
    private val json = Json { ignoreUnknownKeys = true }
    val defaults = listOf(
        ReaderTitleStylePreset("classic", "经典标题", ReaderTitleStyle()),
        ReaderTitleStylePreset("airy", "留白章首", ReaderTitleStyle(font = ReaderSyntaxFont.SERIF, bold = false,
            css = "font-size: 1.65em; margin-top: 5em; margin-bottom: 6em; text-align: left;")),
        ReaderTitleStylePreset("framed", "边框章首", ReaderTitleStyle(alignment = ReaderTitleAlignment.CENTER,
            borderWidthEm = .05f, paddingEm = .8f, cornerRadiusEm = .3f))
    )
    fun encode(values: List<ReaderTitleStylePreset>): String = json.encodeToString(values)
    fun decode(raw: String?): List<ReaderTitleStylePreset> = raw?.let {
        runCatching { json.decodeFromString<List<ReaderTitleStylePreset>>(it) }.getOrNull()
    }?.filter { it.id.isNotBlank() && it.name.isNotBlank() }?.distinctBy { it.id } ?: defaults
}

/** Resolve after the reading theme so a theme remembers its selection and sees library edits. */
internal fun ReaderSettings.resolveTitleStylePreset(): ReaderSettings {
    val id = titleStyle.presetId ?: return this
    val preset = titleStylePresets.firstOrNull { it.id == id }
    return copy(titleStyle = preset?.style?.copy(presetId = id) ?: titleStyle.copy(presetId = null))
}

object ReaderTitleStyleCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(style: ReaderTitleStyle): String = json.encodeToString(style)
    fun decode(raw: String?): ReaderTitleStyle = raw?.let {
        runCatching { json.decodeFromString<ReaderTitleStyle>(it) }.getOrNull()
    } ?: ReaderTitleStyle()
}

/** A declaration-list dialect, shared by native title and syntax rendering (no WebView or scripts). */
object ReaderStyleCss {
    data class Declarations(
        var color: Int? = null, var background: Int? = null,
        var font: ReaderSyntaxFont? = null, var fontAssetId: String? = null,
        var bold: Boolean? = null, var italic: Boolean? = null,
        var underline: Boolean? = null, var strike: Boolean? = null,
        var alignment: ReaderTitleAlignment? = null,
        var borderWidth: Float? = null, var borderColor: Int? = null,
        var radius: Float? = null, var padding: Float? = null, var inset: Float? = null,
        var sizeEm: Float? = null, var topEm: Float? = null, var bottomEm: Float? = null,
        var textGradient: ReaderLinearGradient? = null,
        var backgroundGradient: ReaderLinearGradient? = null,
        var backgroundImageId: String? = null, var backgroundImageSpecified: Boolean = false,
        var clipText: Boolean = false,
        val errors: MutableList<String> = mutableListOf()
    ) {
        val paint: ReaderStylePaint get() = ReaderStylePaint(
            textGradient = if (clipText) backgroundGradient ?: textGradient else textGradient,
            backgroundGradient = backgroundGradient.takeUnless { clipText },
            backgroundImageId = backgroundImageId, backgroundImageSpecified = backgroundImageSpecified)
    }

    fun parse(css: String, title: Boolean = false): Declarations {
        val result = Declarations()
        if (css.length > 4000) { result.errors += "CSS 最多 4000 字"; return result }
        css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").split(';').forEach { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@forEach
            val key = entry.substringBefore(':').trim().lowercase()
            val value = entry.substringAfter(':', "").trim().removeSuffix("!important").trim()
            val lower = value.lowercase()
            fun em(): Float = lower.removeSuffix("em").toFloatOrNull()
                ?.takeIf { it.isFinite() && it >= 0f && (lower.endsWith("em") || it == 0f) }
                ?: error("请使用非负 em 值")
            runCatching {
                when (key) {
                    "color", "-webkit-text-fill-color" -> {
                        if (lower.startsWith("linear-gradient(")) {
                            result.textGradient = ReaderLinearGradient.parse(value)
                        } else {
                            result.color = color(value) ?: error("颜色无效")
                            result.textGradient = null
                        }
                    }
                    "background-clip", "-webkit-background-clip" -> result.clipText = when (lower) {
                        "text" -> true; "border-box", "padding-box", "content-box" -> false
                        else -> error("仅支持 text 或 box 裁剪")
                    }
                    "background", "background-image" -> {
                        val gradient = if (lower.startsWith("linear-gradient(")) ReaderLinearGradient.parse(value) else null
                        val image = if (lower.startsWith("url(")) {
                            require(value.endsWith(')')) { "图片地址无效" }
                            val url = value.substringAfter('(').dropLast(1).trim().trim('\"', '\'')
                            require(url.startsWith("asset:") && Regex("[A-Za-z0-9_-]{1,128}").matches(url.removePrefix("asset:"))) { "请使用 url(\"asset:图片ID\")，从图片库插入" }
                            url.removePrefix("asset:")
                        } else null
                        val solid = if (key == "background") color(value) else null
                        require(gradient != null || image != null || solid != null || lower == "none") { "支持纯色、linear-gradient 或 url(\"asset:图片ID\")" }
                        result.backgroundGradient = gradient
                        result.backgroundImageId = image
                        result.backgroundImageSpecified = true
                        if (key == "background") result.background = solid ?: 0
                    }
                    "background-color" -> result.background = color(value) ?: error("颜色无效")
                    "font-family" -> {
                        val family = value.trim('"', '\'')
                        result.font = when (family.lowercase()) {
                            "inherit" -> ReaderSyntaxFont.INHERIT
                            "system-ui" -> ReaderSyntaxFont.SYSTEM
                            "serif" -> ReaderSyntaxFont.SERIF
                            "sans-serif" -> ReaderSyntaxFont.SANS_SERIF
                            "monospace" -> ReaderSyntaxFont.MONOSPACE
                            else -> if (family.startsWith("asset:")) ReaderSyntaxFont.CUSTOM else error("请使用通用字体族或 asset:字体ID")
                        }
                        if (result.font == ReaderSyntaxFont.CUSTOM) result.fontAssetId = family.removePrefix("asset:")
                    }
                    "font-weight" -> result.bold = when (lower) {
                        "bold", "600", "700", "800", "900" -> true
                        "normal", "100", "200", "300", "400", "500" -> false
                        else -> error("字重无效")
                    }
                    "font-style" -> { require(!title) { "标题暂不支持 font-style" }; result.italic = when (lower) { "italic" -> true; "normal" -> false; else -> error("字形无效") } }
                    "text-decoration" -> {
                        require(!title) { "标题暂不支持 text-decoration" }
                        require(lower.split(' ').all { it in setOf("none", "underline", "line-through") }) { "装饰无效" }
                        result.underline = "underline" in lower; result.strike = "line-through" in lower
                    }
                    else -> {
                        require(title) { "不支持此属性" }
                        when (key) {
                            "text-align" -> result.alignment = when (lower) { "left", "start" -> ReaderTitleAlignment.START; "center" -> ReaderTitleAlignment.CENTER; "right", "end" -> ReaderTitleAlignment.END; else -> error("对齐方式无效") }
                            "border-width" -> result.borderWidth = em()
                            "border-color" -> result.borderColor = color(value) ?: error("颜色无效")
                            "border-radius" -> result.radius = em()
                            "padding" -> result.padding = em()
                            "margin-inline" -> result.inset = em()
                            "font-size" -> result.sizeEm = em().coerceIn(0.75f, 3f)
                            "margin-top" -> result.topEm = em().coerceIn(0f, 12f)
                            "margin-bottom" -> result.bottomEm = em().coerceIn(0f, 12f)
                            else -> error("不支持此属性")
                        }
                    }
                }
            }.onFailure { result.errors += "$key：${it.message}" }
        }
        if (result.clipText && (result.backgroundGradient == null || result.backgroundImageId != null))
            result.errors += "background-clip：text 需要搭配 linear-gradient"
        return result
    }

    fun color(value: String): Int? {
        if (value.equals("transparent", true)) return 0
        if (value.startsWith("rgb(", true) || value.startsWith("rgba(", true)) {
            if (!value.endsWith(')')) return null
            val parts = value.substringAfter('(').dropLast(1).split(',').map(String::trim)
            val count = if (value.startsWith("rgba", true)) 4 else 3
            if (parts.size != count) return null
            val channels = parts.take(3).map { part ->
                val n = part.removeSuffix("%").toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
                if (part.endsWith('%')) (n * 255f / 100f).coerceIn(0f, 255f).toInt() else n.coerceIn(0f, 255f).toInt()
            }
            val alpha = if (count == 4) parts[3].toFloatOrNull()?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)?.times(255f)?.toInt() ?: return null else 255
            return (alpha shl 24) or (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
        }
        val hex = value.removePrefix("#")
        if (!value.startsWith('#')) return when (value.lowercase()) { "black" -> 0xff000000.toInt(); "white" -> -1; "red" -> 0xffff0000.toInt(); else -> null }
        return runCatching {
            when (hex.length) {
                3 -> (0xff000000L or hex.map { "$it$it" }.joinToString("").toLong(16)).toInt()
                4 -> color("#" + hex.map { "$it$it" }.joinToString(""))
                6 -> (0xff000000L or hex.toLong(16)).toInt()
                8 -> ((hex.takeLast(2).toLong(16) shl 24) or hex.take(6).toLong(16)).toInt()
                else -> null
            }
        }.getOrNull()
    }
}
