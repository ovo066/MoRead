package com.mozhi.reader.core.dictionary

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlinx.serialization.encodeToString

@Serializable
data class VocabularyWord(
    val word: String, val definition: String = "", val context: String = "", val bookId: Long = 0,
    val chapterIndex: Int = 0, val offset: Int = 0, val learned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(), val gloss: String = "", val phonetic: String = "",
    /** [LearningLanguage.code]；空 = 旧版收藏（当时只支持英文）。 */
    val language: String = ""
) {
    val languageCode: String get() = language.ifBlank { LearningLanguage.EN.code }
}

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

/** 旧名保留给已有调用方：取词早已不限英文，规则见 [ForeignWords]。 */
object EnglishWords {
    val pattern: Regex get() = ForeignWords.pattern
    fun normalize(word: String): String = ForeignWords.normalize(word)
    fun at(body: String, offset: Int, chapter: Int): DictionaryLookupHit? = ForeignWords.at(body, offset, chapter)
}
