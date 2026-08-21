package com.mozhi.reader.ai.audiobook

import java.util.Locale

internal data class AudiobookAttributionBatch(
    val targetIndices: Set<Int>,
    val markedContext: String
)

internal fun buildAudiobookAttributionBatches(
    body: String,
    segments: List<DraftAudiobookSegment>,
    targetIndices: Set<Int>,
    lockedRoleNames: Map<Int, String>,
    maxTargets: Int = 18,
    surroundingChars: Int = 700,
    maxRawContextChars: Int = 9_000,
    maxWholeChapterChars: Int = 40_000
): List<AudiobookAttributionBatch> {
    if (body.isBlank() || targetIndices.isEmpty()) return emptyList()
    val validTargets = targetIndices
        .filter { index -> segments.getOrNull(index)?.kind == AudiobookSegmentKind.DIALOGUE }
        .sorted()
    if (validTargets.isEmpty()) return emptyList()

    if (body.length <= maxWholeChapterChars) {
        return listOf(
            AudiobookAttributionBatch(
                targetIndices = validTargets.toSet(),
                markedContext = markDialogueContext(
                    body = body,
                    segments = segments,
                    contextStart = 0,
                    contextEnd = body.length,
                    targetIndices = validTargets.toSet(),
                    lockedRoleNames = lockedRoleNames
                )
            )
        )
    }

    val groups = mutableListOf<List<Int>>()
    var current = mutableListOf<Int>()
    validTargets.forEach { index ->
        val tentative = current + index
        val first = segments[tentative.first()]
        val last = segments[tentative.last()]
        val rawSpan = last.endCharOffset - first.startCharOffset + surroundingChars * 2
        if (current.isNotEmpty() && (current.size >= maxTargets || rawSpan > maxRawContextChars)) {
            groups += current
            current = mutableListOf(index)
        } else {
            current += index
        }
    }
    if (current.isNotEmpty()) groups += current

    return groups.map { indices ->
        val first = segments[indices.first()]
        val last = segments[indices.last()]
        val contextStart = (first.startCharOffset - surroundingChars).coerceAtLeast(0)
        val contextEnd = (last.endCharOffset + surroundingChars).coerceAtMost(body.length)
        AudiobookAttributionBatch(
            targetIndices = indices.toSet(),
            markedContext = markDialogueContext(
                body = body,
                segments = segments,
                contextStart = contextStart,
                contextEnd = contextEnd,
                targetIndices = indices.toSet(),
                lockedRoleNames = lockedRoleNames
            )
        )
    }
}

private fun markDialogueContext(
    body: String,
    segments: List<DraftAudiobookSegment>,
    contextStart: Int,
    contextEnd: Int,
    targetIndices: Set<Int>,
    lockedRoleNames: Map<Int, String>
): String = buildString {
    var cursor = contextStart
    segments.forEachIndexed { index, segment ->
        if (segment.endCharOffset <= contextStart || segment.startCharOffset >= contextEnd) return@forEachIndexed
        val start = segment.startCharOffset.coerceAtLeast(contextStart)
        val end = segment.endCharOffset.coerceAtMost(contextEnd)
        if (cursor < start) append(body.substring(cursor, start))
        if (segment.kind == AudiobookSegmentKind.DIALOGUE) {
            append("\n<dialogue id=\"").append(index).append('"')
            append(" target=\"").append(index in targetIndices).append('"')
            append(" rule_guess=\"").append(escapeAttribute(segment.roleName)).append('"')
            append(" local_confidence=\"")
                .append(String.format(Locale.ROOT, "%.2f", segment.confidence))
                .append('"')
            lockedRoleNames[index]?.let { role ->
                append(" locked_speaker=\"").append(escapeAttribute(role)).append('"')
            }
            append('>')
            append(body.substring(start, end))
            append("</dialogue>\n")
        } else {
            append(body.substring(start, end))
        }
        cursor = maxOf(cursor, end)
    }
    if (cursor < contextEnd) append(body.substring(cursor, contextEnd))
}.trim()

private fun escapeAttribute(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
