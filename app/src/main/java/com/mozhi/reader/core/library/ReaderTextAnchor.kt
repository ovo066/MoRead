package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.text.ChineseTextConverter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ReaderTextAnchor(
    val kind: String = "reader-text-anchor-v1",
    val mode: ChineseConversionMode,
    val quote: String,
    val prefix: String,
    val suffix: String,
    val ratio: Float
)

data class ResolvedTextAnchor(val start: Int, val end: Int)

object ReaderTextAnchorCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun encode(anchor: ReaderTextAnchor): String = json.encodeToString(anchor)

    fun decode(raw: String?): ReaderTextAnchor? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<ReaderTextAnchor>(raw) }
            .getOrNull()
            ?.takeIf { it.kind == "reader-text-anchor-v1" }
    }
}

object ReaderTextAnchors {
    private const val CONTEXT = 24

    fun create(
        body: String,
        start: Int,
        end: Int,
        mode: ChineseConversionMode
    ): ReaderTextAnchor {
        val from = start.coerceIn(0, body.length)
        val to = end.coerceIn(from, body.length)
        return ReaderTextAnchor(
            mode = mode,
            quote = body.substring(from, to),
            prefix = body.substring((from - CONTEXT).coerceAtLeast(0), from),
            suffix = body.substring(to, (to + CONTEXT).coerceAtMost(body.length)),
            ratio = if (body.isEmpty()) 0f else from.toFloat() / body.length
        )
    }

    fun resolve(
        body: String,
        anchor: ReaderTextAnchor,
        mode: ChineseConversionMode,
        converter: ChineseTextConverter
    ): ResolvedTextAnchor? {
        val source = anchor.prefix + anchor.quote + anchor.suffix
        val full = converter.retarget(source, anchor.mode, mode)
        val fullStart = convertedBoundary(source, full, anchor.prefix.length)
        val fullEnd = convertedBoundary(source, full, anchor.prefix.length + anchor.quote.length)
        occurrences(body, full).closest(body.length, anchor.ratio)?.let { hit ->
            return ResolvedTextAnchor(hit + fullStart, hit + fullEnd)
        }
        val quote = converter.retarget(anchor.quote, anchor.mode, mode)
        if (quote.isNotEmpty()) {
            occurrences(body, quote).closest(body.length, anchor.ratio)?.let { hit ->
                return ResolvedTextAnchor(hit, hit + quote.length)
            }
        }
        val left = anchor.prefix.takeLast(12)
        val boundarySource = left + anchor.suffix.take(12)
        val boundary = converter.retarget(boundarySource, anchor.mode, mode)
        val boundaryOffset = convertedBoundary(boundarySource, boundary, left.length)
        return occurrences(body, boundary).closest(body.length, anchor.ratio)?.let { hit ->
            val point = hit + boundaryOffset
            ResolvedTextAnchor(point, point)
        }
    }

    private fun convertedBoundary(source: String, target: String, boundary: Int): Int {
        val sourceBoundary = boundary.coerceIn(0, source.length)
        val commonPrefix = source.indices
            .takeWhile { it < target.length && source[it] == target[it] }
            .count()
        val commonSuffix = (0 until minOf(source.length, target.length) - commonPrefix)
            .takeWhile { offset ->
                source[source.lastIndex - offset] == target[target.lastIndex - offset]
            }
            .count()
        if (sourceBoundary <= commonPrefix) return sourceBoundary
        val sourceChangedEnd = source.length - commonSuffix
        if (sourceBoundary >= sourceChangedEnd) {
            return (target.length - (source.length - sourceBoundary)).coerceIn(0, target.length)
        }
        val sourceChangedLength = sourceChangedEnd - commonPrefix
        val targetChangedLength = target.length - commonPrefix - commonSuffix
        return commonPrefix + (
            (sourceBoundary - commonPrefix).toFloat() / sourceChangedLength * targetChangedLength
        ).roundToInt()
    }

    private fun occurrences(body: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        return buildList {
            var from = 0
            while (from <= body.length - needle.length) {
                val hit = body.indexOf(needle, from)
                if (hit < 0) break
                add(hit)
                from = hit + needle.length.coerceAtLeast(1)
            }
        }
    }

    private fun List<Int>.closest(bodyLength: Int, ratio: Float): Int? =
        minByOrNull { hit ->
            abs((if (bodyLength == 0) 0f else hit.toFloat() / bodyLength) - ratio)
        }
}
