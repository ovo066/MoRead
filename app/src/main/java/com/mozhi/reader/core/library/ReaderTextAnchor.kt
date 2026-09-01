package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.text.ChineseTextConverter
import kotlin.math.abs
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
        val prefix = converter.retarget(anchor.prefix, anchor.mode, mode)
        val quote = converter.retarget(anchor.quote, anchor.mode, mode)
        val suffix = converter.retarget(anchor.suffix, anchor.mode, mode)
        occurrences(body, prefix + quote + suffix).closest(body.length, anchor.ratio)?.let { hit ->
            val start = hit + prefix.length
            return ResolvedTextAnchor(start, start + quote.length)
        }
        if (quote.isNotEmpty()) {
            occurrences(body, quote).closest(body.length, anchor.ratio)?.let { hit ->
                return ResolvedTextAnchor(hit, hit + quote.length)
            }
        }
        val left = prefix.takeLast(12)
        val right = suffix.take(12)
        return occurrences(body, left + right).closest(body.length, anchor.ratio)?.let { hit ->
            val point = hit + left.length
            ResolvedTextAnchor(point, point)
        }
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
