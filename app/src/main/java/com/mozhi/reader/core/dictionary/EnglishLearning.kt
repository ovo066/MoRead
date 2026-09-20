package com.mozhi.reader.core.dictionary

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlinx.serialization.encodeToString

@Serializable
data class VocabularyWord(
    val word: String, val definition: String = "", val context: String = "", val bookId: Long = 0,
    val chapterIndex: Int = 0, val offset: Int = 0, val learned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(), val gloss: String = "", val phonetic: String = ""
)

/** Preference changes unrelated to vocabulary reuse the decoded list rather than parsing it again. */
object VocabularyCodec {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var previousRaw: String? = null
    private var previousWords = emptyList<VocabularyWord>()
    @Synchronized fun decode(raw: String?): List<VocabularyWord> {
        if (raw == previousRaw) return previousWords
        val decoded = if (raw.isNullOrBlank()) emptyList() else json.decodeFromString<List<VocabularyWord>>(raw).map { it.repairMetadataGloss() }
        previousRaw = raw; previousWords = decoded
        return decoded
    }
    fun encode(words: List<VocabularyWord>): String = json.encodeToString(words)
}

enum class WordAnnotationMode(val label: String) { INLINE("直接显示"), POPUP("划线弹窗"), OFF("关闭标注") }
data class WordGloss(val meaning: String, val phonetic: String = "")

/** A selected dictionary headword/phrase, independent of the language of the book. */
data class DictionaryLookupHit(val word: String, val context: String, val chapterIndex: Int, val offset: Int)

fun dictionaryQuery(text: String): String? = text.trim().replace(Regex("\\s+"), " ")
    .takeIf { it.length in 1..80 && it.any(Char::isLetterOrDigit) && it.none(Char::isISOControl) }

object EnglishWords {
    val pattern = Regex("[A-Za-z]+(?:['’\\-][A-Za-z]+)*")
    fun normalize(word: String): String = word.trim().replace('’', '\'').lowercase(Locale.ROOT)
    fun at(body: String, offset: Int, chapter: Int): DictionaryLookupHit? {
        if (offset !in body.indices || !body[offset].isEnglishLetter()) return null
        var start = offset
        var end = offset + 1
        while (start > 0 && body[start - 1].isWordChar()) start--
        while (end < body.length && body[end].isWordChar()) end++
        val word = body.substring(start, end).trim('\'', '’', '-')
        if (word.length !in 1..80 || !pattern.matches(word)) return null
        return DictionaryLookupHit(word, body.substring((start - 120).coerceAtLeast(0), (end + 180).coerceAtMost(body.length)), chapter, start)
    }
    private fun Char.isEnglishLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isWordChar() = isEnglishLetter() || this == '\'' || this == '’' || this == '-'
}
