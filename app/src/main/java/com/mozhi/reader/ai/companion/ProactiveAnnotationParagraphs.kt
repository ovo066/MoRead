package com.mozhi.reader.ai.companion

import com.mozhi.reader.core.datastore.ProactiveAnnotationLimits
import java.security.MessageDigest

data class ProactiveAnnotationParagraph(val start: Int, val end: Int)

/** All candidate selection happens locally; no model ever sees a suffix after its target. */
object ProactiveAnnotationParagraphs {
    const val MAX_PREFIX_CHARS = 28_000

    private val heading = Regex("^(?:第.{1,20}[章节卷回]|序章|序言|序幕|楔子|前言|后记|尾声|chapter\\s+(?:[0-9]+|[ivxlcdm]+)\\b).*", RegexOption.IGNORE_CASE)
    internal fun isHeading(line: String): Boolean = heading.matches(line.trim())

    fun split(body: String): List<ProactiveAnnotationParagraph> {
        var offset = 0
        return body.split('\n').mapNotNull { line ->
            val start = offset + line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val end = offset + line.trimEnd().length
            offset += line.length + 1
            if (end - start < 40 || isHeading(line)) null
            else ProactiveAnnotationParagraph(start, end)
        }
    }

    fun candidates(body: String, limit: Int): List<ProactiveAnnotationParagraph> {
        val paragraphs = split(body)
        val count = minOf(limit.coerceIn(0, ProactiveAnnotationLimits.MAX_PER_CHAPTER), paragraphs.size)
        if (count == 0) return emptyList()
        // One salient paragraph per evenly distributed bucket; stable across retries/daily caps.
        return (0 until count).map { bucket ->
            paragraphs.subList(bucket * paragraphs.size / count, (bucket + 1) * paragraphs.size / count)
                .maxBy { p ->
                    minOf(p.end - p.start, 300) + body.substring(p.start, p.end)
                        .count { it in "！!？?“”\"" } * 12
                }
        }.sortedBy { it.end }
    }

    fun prefix(body: String, paragraph: ProactiveAnnotationParagraph): String =
        body.substring(minOf(paragraph.start, (paragraph.end - MAX_PREFIX_CHARS).coerceAtLeast(0)), paragraph.end)

    fun revision(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
