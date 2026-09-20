package com.mozhi.reader.core.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class AiDictionaryEntry(val definition: String, val annotation: WordGloss)

/** The inline annotation is a separate output field, not a substring of the rich explanation. */
fun parseAiDictionaryEntry(source: String): AiDictionaryEntry {
    val raw = source.trim().take(64_000)
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    val fields = if (start >= 0 && end > start) runCatching {
        Json.parseToJsonElement(raw.substring(start, end + 1)) as? JsonObject
    }.getOrNull() else null
    fun field(name: String) = (fields?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    val markdown = field("definition").ifBlank { field("markdown") }.trim().take(12_000)
    val gloss = field("gloss").replace(Regex("[\\s*`]"), "").take(16)
        .takeIf { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) && ':' !in it && '：' !in it }.orEmpty()
    val phonetic = field("phonetic").trim().take(64)
    if (markdown.isNotBlank() || fields?.containsKey("gloss") == true) {
        val definition = markdown.ifBlank { listOf(gloss, phonetic).filter(String::isNotBlank).joinToString("\n\n") }
        val fallback = briefWordGloss(definition)
        val meaning = if (fields?.containsKey("gloss") == true) gloss else fallback.meaning
        return AiDictionaryEntry(definition, WordGloss(meaning, phonetic.ifBlank { fallback.phonetic }))
    }
    // Older or nonconforming providers may still return prose/Markdown. Keep it readable.
    val definition = raw.take(12_000)
    return AiDictionaryEntry(definition, briefWordGloss(definition))
}
