package com.mozhi.reader.ai.audiobook

enum class AudiobookSegmentKind { NARRATION, DIALOGUE }

data class DraftAudiobookSegment(
    val startCharOffset: Int,
    val endCharOffset: Int,
    val roleName: String,
    val kind: AudiobookSegmentKind,
    val confidence: Float
)

/** 本地规则线：保留原文 UTF-16 坐标，把对白和旁白拆成可人工确认的草稿。 */
object DialogueRuleSegmenter {
    private val speakerBefore = Regex(
        "([\\p{L}\\p{N}_·]{1,10}?)(?:轻声|低声|淡淡|冷冷|笑着|怒声|忽然)?(?:冷笑道|喝道|说道|说|道|问|答|喊|叫)[：:,，\\s]*$"
    )
    private val speakerAfter = Regex(
        "^[\\s，,。.!！?？]*([\\p{L}\\p{N}_·]{1,10}?)(?:轻声|低声|淡淡|冷冷|笑着|怒声|忽然)?(?:冷笑道|喝道|说道|说|道|问|答|喊|叫)"
    )
    private val invalidSpeakers = setOf("他", "她", "它", "我", "你", "旁白", "有人", "那人")

    fun segment(text: String): List<DraftAudiobookSegment> {
        if (text.isBlank()) return emptyList()
        val dialogueRanges = normalizeDialogueRanges(findQuotedRanges(text)).toMutableList()
        findDashDialogueRanges(text).forEach { candidate ->
            if (dialogueRanges.none { it.first <= candidate.last && candidate.first <= it.last }) {
                dialogueRanges += candidate
            }
        }
        val normalizedDialogueRanges = normalizeDialogueRanges(dialogueRanges)

        val output = mutableListOf<DraftAudiobookSegment>()
        var cursor = 0
        var recentSpeaker: String? = null
        var recentDialogueEnd = -1
        normalizedDialogueRanges.forEach { range ->
            addNarration(text, cursor, range.first, output)
            val inferred = inferSpeaker(text, range.first, range.last + 1)
            val bridge = if (recentDialogueEnd >= 0) text.substring(recentDialogueEnd, range.first) else ""
            val canInheritRecent = recentSpeaker != null &&
                bridge.length <= MAX_SPEAKER_INHERIT_BRIDGE_CHARS &&
                !bridge.contains("\n\n")
            val speaker = inferred ?: recentSpeaker?.takeIf { canInheritRecent } ?: "对白"
            if (inferred != null) recentSpeaker = inferred
            addTrimmed(text, range.first, range.last + 1) { start, end ->
                output += DraftAudiobookSegment(
                    startCharOffset = start,
                    endCharOffset = end,
                    roleName = speaker,
                    kind = AudiobookSegmentKind.DIALOGUE,
                    confidence = when {
                        inferred != null -> 0.92f
                        canInheritRecent -> 0.48f
                        else -> 0.35f
                    }
                )
            }
            recentDialogueEnd = range.last + 1
            cursor = maxOf(cursor, range.last + 1)
        }
        addNarration(text, cursor, text.length, output)
        return output.sortedBy(DraftAudiobookSegment::startCharOffset)
    }

    private fun findQuotedRanges(text: String): List<IntRange> {
        val pairs = mapOf('「' to '」', '『' to '』', '“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')
        val ranges = mutableListOf<IntRange>()
        val stack = mutableListOf<Pair<Char, Int>>()
        text.forEachIndexed { index, char ->
            val sameQuote = (char == '"' || char == '\'')
            if (sameQuote) {
                val top = stack.lastOrNull()
                if (top?.first == char) {
                    stack.removeAt(stack.lastIndex)
                    if (index > top.second + 1) ranges += top.second..index
                } else {
                    stack += char to index
                }
            } else if (char in pairs.keys) {
                stack += char to index
            } else {
                val openingIndex = stack.indexOfLast { pairs[it.first] == char }
                if (openingIndex >= 0) {
                    val opening = stack.removeAt(openingIndex)
                    if (index > opening.second + 1) ranges += opening.second..index
                }
            }
        }
        return ranges
    }

    /** 嵌套或畸形引号可能产生包含/交叉区间；统一成非重叠区间，避免同一句被朗读两遍。 */
    private fun normalizeDialogueRanges(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last })
        val output = mutableListOf<IntRange>()
        sorted.forEach { range ->
            val previous = output.lastOrNull()
            when {
                previous == null || range.first > previous.last -> output += range
                range.last > previous.last -> output[output.lastIndex] = previous.first..range.last
            }
        }
        return output
    }

    private fun findDashDialogueRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val newline = text.indexOf('\n', lineStart)
            val lineEnd = if (newline < 0) text.length else newline
            val contentStart = (lineStart until lineEnd).firstOrNull { !text[it].isWhitespace() }
            if (contentStart != null && text.startsWith("——", contentStart)) {
                ranges += contentStart until lineEnd
            }
            if (newline < 0) break
            lineStart = newline + 1
        }
        return ranges
    }

    private fun inferSpeaker(text: String, start: Int, end: Int): String? {
        val before = text.substring(maxOf(0, start - 36), start)
        val after = text.substring(end, minOf(text.length, end + 36))
        return sequenceOf(
            speakerBefore.find(before)?.groupValues?.getOrNull(1),
            speakerAfter.find(after)?.groupValues?.getOrNull(1)
        ).filterNotNull().map(String::trim).firstOrNull { it !in invalidSpeakers }
    }

    private fun addNarration(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        output: MutableList<DraftAudiobookSegment>
    ) = addTrimmed(text, rawStart, rawEnd) { start, end ->
        output += DraftAudiobookSegment(start, end, "旁白", AudiobookSegmentKind.NARRATION, 1f)
    }

    private const val MAX_SPEAKER_INHERIT_BRIDGE_CHARS = 120

    private inline fun addTrimmed(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        block: (Int, Int) -> Unit
    ) {
        var start = rawStart.coerceAtLeast(0)
        var end = rawEnd.coerceAtMost(text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start < end) block(start, end)
    }
}
