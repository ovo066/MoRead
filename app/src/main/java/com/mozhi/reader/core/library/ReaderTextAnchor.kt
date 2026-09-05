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
        val fullStart = convertedBoundary(
            source,
            full,
            anchor.prefix.length,
            anchor.mode,
            mode,
            converter
        )
        val fullEnd = convertedBoundary(
            source,
            full,
            anchor.prefix.length + anchor.quote.length,
            anchor.mode,
            mode,
            converter
        )
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
        val boundaryOffset = convertedBoundary(
            boundarySource,
            boundary,
            left.length,
            anchor.mode,
            mode,
            converter
        )
        return occurrences(body, boundary).closest(body.length, anchor.ratio)?.let { hit ->
            val point = hit + boundaryOffset
            ResolvedTextAnchor(point, point)
        }
    }

    fun resolveSourcePoint(
        sourceBody: String,
        displayedBody: String,
        displayedAnchor: ReaderTextAnchor,
        converter: ChineseTextConverter
    ): ResolvedTextAnchor? {
        val target = resolve(
            displayedBody,
            displayedAnchor,
            displayedAnchor.mode,
            converter
        )?.start ?: return null
        val estimate = (displayedAnchor.ratio * sourceBody.length)
            .roundToInt()
            .coerceIn(0, sourceBody.length)

        fun boundaryAt(sourceOffset: Int): Int = convertedBoundary(
            sourceBody,
            displayedBody,
            sourceOffset,
            ChineseConversionMode.OFF,
            displayedAnchor.mode,
            converter
        )

        fun firstBoundaryAtLeast(displayOffset: Int): Int {
            var low = 0
            var high = sourceBody.length
            while (low < high) {
                val middle = (low + high) ushr 1
                if (boundaryAt(middle) >= displayOffset) high = middle else low = middle + 1
            }
            return if (boundaryAt(low) >= displayOffset) low else sourceBody.length + 1
        }

        val first = firstBoundaryAtLeast(target)
        if (first > sourceBody.length) return null
        val after = firstBoundaryAtLeast(target + 1)
        val last = (after - 1).coerceIn(first, sourceBody.length)
        val candidates = buildSet {
            add(estimate.coerceIn(first, last))
            add(first)
            add(last)
            for (candidate in (first - 2)..(first + 2)) {
                if (candidate in 0..sourceBody.length) add(candidate)
            }
            for (candidate in (last - 2)..(last + 2)) {
                if (candidate in 0..sourceBody.length) add(candidate)
            }
        }.sortedBy { candidate -> abs(candidate - estimate) }
        return candidates.firstOrNull { sourceOffset ->
            val sourceAnchor = create(
                sourceBody,
                sourceOffset,
                sourceOffset,
                ChineseConversionMode.OFF
            )
            resolve(
                displayedBody,
                sourceAnchor,
                displayedAnchor.mode,
                converter
            )?.start == target
        }?.let { sourceOffset ->
            ResolvedTextAnchor(sourceOffset, sourceOffset)
        }
    }

    private fun convertedBoundary(
        source: String,
        target: String,
        boundary: Int,
        from: ChineseConversionMode,
        to: ChineseConversionMode,
        converter: ChineseTextConverter
    ): Int {
        val sourceBoundary = boundary.coerceIn(0, source.length)
        val fromLeft = converter.retarget(source.take(sourceBoundary), from, to).length
        val fromRight = target.length -
            converter.retarget(source.drop(sourceBoundary), from, to).length
        return ((fromLeft + fromRight) / 2f).roundToInt().coerceIn(0, target.length)
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
