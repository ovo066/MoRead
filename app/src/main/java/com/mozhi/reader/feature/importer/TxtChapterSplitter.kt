package com.mozhi.reader.feature.importer

import javax.inject.Inject
import kotlin.math.abs

class TxtChapterSplitter @Inject constructor() {
    fun chooseBest(text: String, rules: List<TxtTocRule>): TxtSplitResult {
        val candidates = rules
            .asSequence()
            .filter { it.enable && it.rule.isNotBlank() }
            .mapNotNull { splitWithRule(text, it) }
            .filter { it.chapters.size >= MIN_REASONABLE_CHAPTERS }
            .toList()

        return candidates.maxByOrNull { it.score } ?: fallback(text)
    }

    fun splitWithRule(text: String, rule: TxtTocRule): TxtSplitResult? {
        if (rule.rule.isBlank()) return null
        val regex = runCatching {
            Regex(rule.rule, setOf(RegexOption.MULTILINE))
        }.getOrNull() ?: return null

        val headings = regex.findAll(text)
            .map { match ->
                val lineStart = text.lastIndexOf('\n', match.range.first.coerceAtLeast(1) - 1) + 1
                val lineEnd = text.indexOf('\n', match.range.last + 1)
                    .let { if (it == -1) text.length else it }
                Heading(
                    start = lineStart,
                    end = lineEnd,
                    title = text.substring(lineStart, lineEnd).trim()
                )
            }
            .filter { it.title.isNotBlank() && it.title.length <= MAX_TITLE_LENGTH }
            .distinctBy { it.start }
            .toList()

        if (headings.size < 2) return null

        val chapters = buildList {
            val firstStart = headings.first().start
            if (firstStart > 0) {
                val preface = text.substring(0, firstStart).trim()
                if (preface.isNotBlank()) {
                    add(
                        TxtChapter(
                            index = size,
                            title = "序章",
                            content = preface,
                            startOffset = 0,
                            endOffset = firstStart
                        )
                    )
                }
            }

            headings.forEachIndexed { index, heading ->
                val chapterEnd = headings.getOrNull(index + 1)?.start ?: text.length
                val bodyStart = heading.end.coerceAtMost(chapterEnd)
                val body = text.substring(bodyStart, chapterEnd).trim()
                add(
                    TxtChapter(
                        index = size,
                        title = heading.title,
                        content = body,
                        startOffset = heading.start,
                        endOffset = chapterEnd
                    )
                )
            }
        }

        return TxtSplitResult(
            rule = rule,
            chapters = chapters,
            score = score(chapters)
        )
    }

    fun splitWithCustomRegex(text: String, regexText: String): TxtSplitResult? =
        splitWithRule(
            text = text,
            rule = TxtTocRule(
                id = CUSTOM_RULE_ID,
                enable = true,
                name = "自定义规则",
                rule = regexText,
                serialNumber = Int.MAX_VALUE
            )
        )

    private fun score(chapters: List<TxtChapter>): Double {
        if (chapters.isEmpty()) return Double.NEGATIVE_INFINITY
        val lengths = chapters.map { it.charCount.coerceAtLeast(1) }
        val average = lengths.average()
        val shortRatio = lengths.count { it < 80 }.toDouble() / lengths.size
        val hugeRatio = lengths.count { it > 120_000 }.toDouble() / lengths.size
        val idealDistance = abs(average - 8_000.0) / 8_000.0
        return chapters.size * 100.0 -
            shortRatio * 2_000.0 -
            hugeRatio * 5_000.0 -
            idealDistance.coerceAtMost(5.0) * 30.0
    }

    private fun fallback(text: String): TxtSplitResult {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return TxtSplitResult(null, emptyList(), 0.0, usedFallback = true)
        }

        val chapters = mutableListOf<TxtChapter>()
        var start = 0
        while (start < normalized.length) {
            val desiredEnd = (start + FALLBACK_CHAPTER_SIZE).coerceAtMost(normalized.length)
            val end = if (desiredEnd == normalized.length) {
                desiredEnd
            } else {
                normalized.indexOf("\n\n", desiredEnd)
                    .takeIf { it in (desiredEnd + 1)..(desiredEnd + 2_000) }
                    ?: desiredEnd
            }
            chapters += TxtChapter(
                index = chapters.size,
                title = if (chapters.isEmpty()) "正文" else "第 ${chapters.size + 1} 节",
                content = normalized.substring(start, end).trim(),
                startOffset = start,
                endOffset = end
            )
            start = end
        }

        return TxtSplitResult(
            rule = null,
            chapters = chapters,
            score = 0.0,
            usedFallback = true
        )
    }

    private data class Heading(
        val start: Int,
        val end: Int,
        val title: String
    )

    private companion object {
        const val MIN_REASONABLE_CHAPTERS = 4
        const val MAX_TITLE_LENGTH = 80
        const val FALLBACK_CHAPTER_SIZE = 10_000
        const val CUSTOM_RULE_ID = Long.MIN_VALUE
    }
}
