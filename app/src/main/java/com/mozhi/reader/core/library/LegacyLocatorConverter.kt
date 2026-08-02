package com.mozhi.reader.core.library

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class LegacyLocator(val href: String, val progression: Double)

/**
 * Converts positions saved by the Readium-based reader into `(chapterIndex, charOffset)`.
 *
 * Parsing avoids both `Locator.fromJSON` and `org.json` so this stays usable once the navigator
 * dependency is gone and remains a plain JVM unit test. The character offset derived from
 * `progression` assumes uniform character density and lands within a page or two, which beats
 * resetting old bookmarks to the start of their chapter.
 */
object LegacyLocatorConverter {

    fun resolveChapterIndex(
        locatorHref: String,
        readingOrderHrefs: List<String>,
        fallbackIndex: Int
    ): Int {
        val normalizedLocator = locatorHref.normalizeHref()
        return readingOrderHrefs.indexOfFirst { href ->
            href.normalizeHref() == normalizedLocator
        }.takeIf { it >= 0 } ?: fallbackIndex
    }

    fun parse(locatorJson: String): LegacyLocator? = runCatching {
        val root = Json.parseToJsonElement(locatorJson).jsonObject
        val href = root["href"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return null
        val progression = root["locations"]
            ?.jsonObject
            ?.get("progression")
            ?.jsonPrimitive
            ?.doubleOrNull
            ?: 0.0
        LegacyLocator(href = href, progression = progression.coerceIn(0.0, 1.0))
    }.getOrNull()

    fun progressionToCharOffset(progression: Double, charCount: Int): Int {
        if (charCount <= 0) return 0
        return (progression * charCount).toInt().coerceIn(0, charCount)
    }

    private fun String.normalizeHref(): String = substringBefore('#').removePrefix("./")
}
