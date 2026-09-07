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
        resolveTextMatch(body, anchor, mode, converter)?.let { return it }
        // A context clipped through an OpenCC phrase may convert differently from the chapter.
        // Callers without source coordinates still need a position when its text no longer matches.
        val start = ratioOffset(anchor.ratio, body.length)
        val quoteLength = converter.retarget(anchor.quote, anchor.mode, mode).length
        return ResolvedTextAnchor(start, (start + quoteLength).coerceAtMost(body.length))
    }

    /** Text matches only, so persisted source coordinates can take priority over an estimate. */
    fun resolveTextMatch(
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
        occurrences(body, full).closest(body.length, anchor.ratio, fullStart)?.let { hit ->
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
        return occurrences(body, boundary).closest(body.length, anchor.ratio, boundaryOffset)?.let { hit ->
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
        val estimate = ratioOffset(displayedAnchor.ratio, sourceBody.length)

        val boundarySides = mutableMapOf<Int, Pair<Int, Int>>()
        fun sidesAt(sourceOffset: Int): Pair<Int, Int> = boundarySides.getOrPut(sourceOffset) {
            convertedBoundarySides(
                sourceBody,
                displayedBody,
                sourceOffset,
                ChineseConversionMode.OFF,
                displayedAnchor.mode,
                converter
            )
        }
        fun boundaryAt(sourceOffset: Int): Int {
            val (left, right) = sidesAt(sourceOffset)
            return ((left + right) / 2f).roundToInt().coerceIn(0, displayedBody.length)
        }

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
        if (first > sourceBody.length) return ResolvedTextAnchor(estimate, estimate)
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
        // The averaged boundary can merge a split phrase with its true end. Prefer the
        // boundary where both complete sides agree, so 主板 keeps the full source 主機板.
        val exactBoundary = candidates.firstOrNull { sourceOffset ->
            val (left, right) = sidesAt(sourceOffset)
            left == target && right == target
        }
        val sourceOffset = exactBoundary ?: candidates.firstOrNull { sourceOffset ->
            val sourceAnchor = create(
                sourceBody,
                sourceOffset,
                sourceOffset,
                ChineseConversionMode.OFF
            )
            resolveTextMatch(
                displayedBody,
                sourceAnchor,
                displayedAnchor.mode,
                converter
            )?.start == target
        } ?: estimate.coerceIn(first, last)
        return ResolvedTextAnchor(sourceOffset, sourceOffset)
    }

    internal fun convertedBoundary(
        source: String,
        target: String,
        boundary: Int,
        from: ChineseConversionMode,
        to: ChineseConversionMode,
        converter: ChineseTextConverter
    ): Int {
        val (fromLeft, fromRight) = convertedBoundarySides(source, target, boundary, from, to, converter)
        return ((fromLeft + fromRight) / 2f).roundToInt().coerceIn(0, target.length)
    }

    private fun convertedBoundarySides(
        source: String,
        target: String,
        boundary: Int,
        from: ChineseConversionMode,
        to: ChineseConversionMode,
        converter: ChineseTextConverter
    ): Pair<Int, Int> {
        val sourceBoundary = boundary.coerceIn(0, source.length)
        if (sourceBoundary == 0) return 0 to 0
        if (sourceBoundary == source.length) return target.length to target.length
        val fromLeft = converter.retarget(source.take(sourceBoundary), from, to).length
        val fromRight = target.length -
            converter.retarget(source.drop(sourceBoundary), from, to).length
        return fromLeft to fromRight
    }

    private fun occurrences(body: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        return buildList {
            var from = 0
            while (from <= body.length - needle.length) {
                val hit = body.indexOf(needle, from)
                if (hit < 0) break
                add(hit)
                from = hit + 1
            }
        }
    }

    private fun ratioOffset(ratio: Float, length: Int): Int =
        ((ratio.takeIf(Float::isFinite) ?: 0f).coerceIn(0f, 1f) * length).roundToInt()

    private fun List<Int>.closest(bodyLength: Int, ratio: Float, boundary: Int = 0): Int? =
        minByOrNull { hit ->
            abs(hit + boundary - ratioOffset(ratio, bodyLength))
        }
}
