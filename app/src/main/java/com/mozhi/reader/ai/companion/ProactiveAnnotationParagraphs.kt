package com.mozhi.reader.ai.companion

import java.security.MessageDigest

data class ProactiveAnnotationParagraph(val start: Int, val end: Int)

/** All candidate selection happens locally; no model ever sees a suffix after its target. */
object ProactiveAnnotationParagraphs {
    const val MAX_PREFIX_CHARS = 28_000
    const val MAX_TARGET_CHARS = 1_800

    private val heading = Regex("^(?:第.{1,20}[章节卷回]|序章|序言|序幕|楔子|前言|后记|尾声|chapter\\s+(?:[0-9]+|[ivxlcdm]+)\\b).*", RegexOption.IGNORE_CASE)
    internal fun isHeading(line: String): Boolean = heading.matches(line.trim())

    fun split(body: String): List<ProactiveAnnotationParagraph> {
        var offset = 0
        return body.split('\n').flatMap { line ->
            val start = offset + line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val end = offset + line.trimEnd().length
            offset += line.length + 1
            if (end - start < 40 || isHeading(line)) emptyList()
            else buildList {
                var cursor = start
                while (cursor < end) {
                    var boundary = minOf(cursor + MAX_TARGET_CHARS, end)
                    if (boundary < end) {
                        // Split abnormally long paragraphs at a sentence boundary, preserving source offsets.
                        val sentence = (boundary - 1 downTo cursor + MAX_TARGET_CHARS / 2)
                            .firstOrNull { body[it] in "。！？!?；;" }
                        if (sentence != null) boundary = sentence + 1
                        if (end - boundary in 1..5) boundary -= 6 - (end - boundary)
                        if (body[boundary - 1].isHighSurrogate() && body[boundary].isLowSurrogate()) boundary--
                    }
                    if (boundary - cursor >= 6) add(ProactiveAnnotationParagraph(cursor, boundary))
                    cursor = boundary
                }
            }
        }
    }

    fun candidates(body: String, limit: Int): List<ProactiveAnnotationParagraph> {
        val paragraphs = split(body)
        val count = minOf(limit.coerceAtLeast(0), paragraphs.size)
        if (count == 0) return emptyList()
        // Balance by source characters, not paragraph count: long prose and short dialogue mix unevenly.
        var cursor = 0
        return (0 until count).map { bucket ->
            val boundary = body.length.toLong() * (bucket + 1) / count
            val lastAllowed = paragraphs.size - (count - bucket - 1)
            var end = cursor + 1
            while (end < lastAllowed && paragraphs[end - 1].end < boundary) end++
            val group = paragraphs.subList(cursor, end)
            cursor = end
            group
                .maxBy { p ->
                    minOf(p.end - p.start, 300) + body.substring(p.start, p.end)
                        .count { it in "！!？?“”\"" } * 12
                }
        }.sortedBy { it.end }
    }

    fun prefix(body: String, paragraph: ProactiveAnnotationParagraph): String {
        var start = minOf(paragraph.start, (paragraph.end - MAX_PREFIX_CHARS).coerceAtLeast(0))
        if (start > 0 && body[start].isLowSurrogate() && body[start - 1].isHighSurrogate()) start++
        return body.substring(start, paragraph.end)
    }

    fun revision(body: String): String = MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
