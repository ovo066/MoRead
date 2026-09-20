package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Saved export styles are independent of reading themes and reading syntax rules. */
@Serializable
data class ReviewShareTemplate(
    val id: String,
    val name: String,
    val backgroundArgb: Int = 0xFFF7F5EF.toInt(),
    val textArgb: Int = 0xFF495963.toInt(),
    val accentArgb: Int = 0xFF7E9CB5.toInt(),
    val fontChoice: String = "",
    val css: String = "",
    val syntaxEnabled: Boolean = false,
    val syntaxRules: List<ReaderSyntaxRule> = emptyList()
)

object ReviewShareTemplateCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(templates: List<ReviewShareTemplate>): String = json.encodeToString(templates)
    fun decode(raw: String?): List<ReviewShareTemplate> = raw?.let {
        runCatching { json.decodeFromString<List<ReviewShareTemplate>>(it) }.getOrDefault(emptyList())
    }.orEmpty().filter { it.id.isNotBlank() && it.name.isNotBlank() }.distinctBy { it.id }
}

data class ReviewTemplateCss(
    val declarations: ReaderStyleCss.Declarations,
    val lineHeight: Float = 1.55f,
    val letterSpacing: Float = 0f
) {
    companion object {
        /** The native reader CSS declarations plus export line-height and letter-spacing. */
        fun parse(css: String): ReviewTemplateCss {
            var lineHeight = 1.55f
            var letterSpacing = 0f
            val errors = mutableListOf<String>()
            val remaining = mutableListOf<String>()
            val textStyles = mutableListOf<String>()
            if (css.length > 4000) return ReviewTemplateCss(ReaderStyleCss.parse(css, title = true))
            css.replace(Regex("/\\*[\\s\\S]*?\\*/"), "").split(';').forEach { raw ->
                val key = raw.substringBefore(':').trim().lowercase()
                val value = raw.substringAfter(':', "").trim()
                when (key) {
                    "line-height" -> {
                        val number = value.removeSuffix("em").toFloatOrNull()
                        if (number == null || !number.isFinite() || number !in 1f..2.5f) errors += "line-height：请使用 1～2.5 的行高"
                        else lineHeight = number
                    }
                    "letter-spacing" -> {
                        val number = value.removeSuffix("em").toFloatOrNull()
                        if (number == null || !number.isFinite() || number !in -0.05f..0.3f || (!value.endsWith("em") && number != 0f)) errors += "letter-spacing：请使用 -0.05em～0.3em"
                        else letterSpacing = number
                    }
                    "font-style", "text-decoration" -> textStyles += raw
                    else -> remaining += raw
                }
            }
            val parsed = ReaderStyleCss.parse(remaining.joinToString(";"), title = true)
            val text = ReaderStyleCss.parse(textStyles.joinToString(";"))
            parsed.italic = text.italic; parsed.underline = text.underline; parsed.strike = text.strike
            parsed.errors += text.errors + errors
            if (parsed.sizeEm?.let { it !in .5f..3f } == true) parsed.errors += "font-size：请使用 0.5em～3em"
            if (listOfNotNull(parsed.padding, parsed.inset, parsed.topEm, parsed.bottomEm).any { it > 6f }) parsed.errors += "边距不能超过 6em"
            if (parsed.borderWidth?.let { it > .5f } == true) parsed.errors += "边框不能超过 0.5em"
            return ReviewTemplateCss(parsed, lineHeight, letterSpacing)
        }
    }
}
