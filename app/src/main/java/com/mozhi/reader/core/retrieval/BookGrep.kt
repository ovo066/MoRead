package com.mozhi.reader.core.retrieval

import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Canonical chapter text, already clipped to the request's immutable reading scope. */
data class GrepSource(val chapterIndex: Int, val body: String?, val error: String? = null)

data class GrepOptions(
    val normalize: Boolean = true,
    val contextChars: Int = DEFAULT_CONTEXT_CHARS,
    val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    val skipMatches: Int = 0
) {
    companion object {
        const val DEFAULT_CONTEXT_CHARS = 80
        const val MAX_CONTEXT_CHARS = 300
        const val DEFAULT_MAX_SAMPLES = 20
        const val MAX_SAMPLES = 50
        const val MAX_PATTERN_CHARS = 200
        const val MAX_CHAPTER_ROWS = 200
        // Includes a conservative allowance for JSON escaping, coordinates and source_ref.
        const val MAX_SAMPLE_JSON_CHARS = 16_000
    }
}

data class GrepLimits(val maxScanChars: Int = 20_000_000, val maxChapters: Int = 20_000)

/** All ranges are chapter-local UTF-16 [start, end); text is always copied from the original. */
data class GrepMatch(
    val chapterIndex: Int,
    val matchStart: Int,
    val matchEnd: Int,
    val matchedText: String,
    val contextStart: Int,
    val contextEnd: Int,
    val contextText: String
)

data class GrepChapterHits(val chapterIndex: Int, val hits: Int)
enum class GrepStatus { COMPLETE, PARTIAL }

data class GrepOutcome(
    val status: GrepStatus,
    val count: Int,
    val byChapter: List<GrepChapterHits>,
    val byChapterTruncated: Boolean,
    val samples: List<GrepMatch>,
    val samplesTruncated: Boolean,
    val nextSkip: Int?,
    val unreadableChapters: List<Int>,
    val unreadableChapterCount: Int = unreadableChapters.size,
    val resourceLimited: Boolean = false,
    /** A changed read-failure/scan boundary must also invalidate pagination of partial results. */
    val coverageSignature: String = ""
) {
    val countIsExact: Boolean get() = status == GrepStatus.COMPLETE
}

/** Local, case-sensitive, non-overlapping literal matching. No regex or semantic inference. */
object BookGrep {
    const val NORMALIZATION_VERSION = "literal-v1"

    /** Explicit, length-preserving UTF-16 mapping. No whitespace removal or number conversion. */
    fun normalizeChar(c: Char): Char = when (c) {
        '　' -> ' '
        in '！'..'～' -> (c.code - 0xFEE0).toChar()
        '‘', '’', '‚', '‛' -> '\''
        '“', '”', '„', '‟' -> '"'
        '—', '―', '–' -> '-'
        else -> c
    }

    fun normalize(text: String): String = buildString(text.length) {
        text.forEach { append(normalizeChar(it)) }
    }

    class InvalidPattern(message: String) : IllegalArgumentException(message)

    fun validatePattern(pattern: String) {
        if (pattern.isEmpty()) throw InvalidPattern("pattern 不能为空")
        if (pattern.length > GrepOptions.MAX_PATTERN_CHARS) {
            throw InvalidPattern("pattern 最多 ${GrepOptions.MAX_PATTERN_CHARS} 个 UTF-16 字符")
        }
    }

    suspend fun scan(pattern: String, sources: List<GrepSource>, options: GrepOptions): GrepOutcome {
        require(sources.map { it.chapterIndex }.distinct().size == sources.size) { "章节不能重复" }
        val byIndex = sources.associateBy { it.chapterIndex }
        return scan(pattern, byIndex.keys.sorted(), { byIndex.getValue(it) }, options)
    }

    /** Loads one chapter at a time; sample limits never stop counting. KMP bounds CPU to O(n+m). */
    suspend fun scan(
        pattern: String,
        chapterIndexes: List<Int>,
        loadSource: suspend (Int) -> GrepSource,
        options: GrepOptions = GrepOptions(),
        limits: GrepLimits = GrepLimits()
    ): GrepOutcome {
        validatePattern(pattern)
        require(limits.maxScanChars >= 0 && limits.maxChapters >= 0)
        val needle = if (options.normalize) normalize(pattern) else pattern
        val failure = IntArray(needle.length)
        var prefix = 0
        for (i in 1 until needle.length) {
            while (prefix > 0 && needle[i] != needle[prefix]) prefix = failure[prefix - 1]
            if (needle[i] == needle[prefix]) prefix++
            failure[i] = prefix
        }
        val contextChars = options.contextChars.coerceIn(0, GrepOptions.MAX_CONTEXT_CHARS)
        val maxSamples = options.maxSamples.coerceIn(1, GrepOptions.MAX_SAMPLES)
        val skip = options.skipMatches.coerceAtLeast(0)
        val coroutine = currentCoroutineContext()
        val coverage = MessageDigest.getInstance("SHA-256")
        var total = 0
        var scanned = 0
        var limited = false
        var chapterRows = 0
        var unreadableCount = 0
        var sampleBudget = GrepOptions.MAX_SAMPLE_JSON_CHARS
        var pageFull = false
        val byChapter = ArrayList<GrepChapterHits>()
        val samples = ArrayList<GrepMatch>()
        val unreadable = ArrayList<Int>()

        for ((ordinal, chapterIndex) in chapterIndexes.sorted().withIndex()) {
            coroutine.ensureActive()
            if (ordinal >= limits.maxChapters || scanned >= limits.maxScanChars) {
                limited = true
                coverage.update("limit:$chapterIndex:$scanned".toByteArray())
                break
            }
            val source = try {
                loadSource(chapterIndex)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                GrepSource(chapterIndex, null, "正文读取失败")
            }
            val body = source.body
            if (body == null || source.error != null || source.chapterIndex != chapterIndex) {
                if (source.error == "RESOURCE_LIMIT") limited = true
                unreadableCount++
                if (unreadable.size < GrepOptions.MAX_CHAPTER_ROWS) unreadable += chapterIndex
                coverage.update("missing:$chapterIndex;".toByteArray())
                continue
            }
            val scanEnd = minOf(body.length, limits.maxScanChars - scanned)
            coverage.update("ok:$chapterIndex:$scanEnd;".toByteArray())
            var matched = 0
            var chapterHits = 0
            for (i in 0 until scanEnd) {
                if (i % 4096 == 0) coroutine.ensureActive()
                val c = if (options.normalize) normalizeChar(body[i]) else body[i]
                while (matched > 0 && c != needle[matched]) matched = failure[matched - 1]
                if (c == needle[matched]) matched++
                if (matched != needle.length) continue
                val end = i + 1
                val start = end - needle.length
                matched = 0 // deliberately non-overlapping, including e.g. "aa" in "aaa"
                chapterHits++
                val hitOrdinal = total++
                if (hitOrdinal < skip || pageFull) continue
                val sample = buildMatch(chapterIndex, body, start, end, contextChars)
                val cost = 300 + 6 * (sample.matchedText.length + sample.contextText.length)
                if (samples.size >= maxSamples || cost > sampleBudget) {
                    pageFull = true // never skip a long sample and return later short ones
                    continue
                }
                samples += sample
                sampleBudget -= cost
            }
            scanned += scanEnd
            if (chapterHits > 0) {
                chapterRows++
                if (byChapter.size < GrepOptions.MAX_CHAPTER_ROWS) {
                    byChapter += GrepChapterHits(chapterIndex, chapterHits)
                }
            }
            if (scanEnd < body.length) {
                limited = true
                break
            }
        }
        coroutine.ensureActive()
        val more = total.toLong() > skip.toLong() + samples.size
        return GrepOutcome(
            status = if (unreadableCount == 0 && !limited) GrepStatus.COMPLETE else GrepStatus.PARTIAL,
            count = total,
            byChapter = byChapter,
            byChapterTruncated = chapterRows > byChapter.size,
            samples = samples,
            samplesTruncated = more,
            nextSkip = if (more && samples.isNotEmpty()) skip + samples.size else null,
            unreadableChapters = unreadable,
            unreadableChapterCount = unreadableCount,
            resourceLimited = limited,
            coverageSignature = coverage.digest().joinToString("") { "%02x".format(it) }
        )
    }

    private fun buildMatch(chapterIndex: Int, body: String, start: Int, end: Int, contextChars: Int): GrepMatch {
        var contextStart = (start - contextChars).coerceAtLeast(0)
        var contextEnd = (end + contextChars).coerceAtMost(body.length)
        if (contextStart in 1 until body.length && body[contextStart].isLowSurrogate() &&
            body[contextStart - 1].isHighSurrogate()) contextStart--
        if (contextEnd in 1 until body.length && body[contextEnd].isLowSurrogate() &&
            body[contextEnd - 1].isHighSurrogate()) contextEnd++
        return GrepMatch(chapterIndex, start, end, body.substring(start, end),
            contextStart, contextEnd, body.substring(contextStart, contextEnd))
    }
}
