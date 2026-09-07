package com.mozhi.reader.core.retrieval

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class BookGrepTest {
    @Test fun countIsIndependentOfSamplesAndPagesAreStable() = runTest {
        val sources = listOf(GrepSource(1, "猫 猫 猫"), GrepSource(0, "猫猫猫"))
        var skip = 0
        val positions = mutableListOf<Pair<Int, Int>>()
        do {
            val result = BookGrep.scan("猫", sources, GrepOptions(maxSamples = 2, skipMatches = skip))
            assertEquals(GrepStatus.COMPLETE, result.status)
            assertEquals(6, result.count)
            assertEquals(listOf(3, 3), result.byChapter.map { it.hits })
            positions += result.samples.map { it.chapterIndex to it.matchStart }
            skip = result.nextSkip ?: -1
        } while (skip >= 0)
        assertEquals(listOf(0 to 0, 0 to 1, 0 to 2, 1 to 0, 1 to 2, 1 to 4), positions)
    }

    @Test fun normalizationRetainsOriginalUtf16Ranges() = runTest {
        val body = "😀\n　“Ａ１２３”—\n原样"
        val result = BookGrep.scan(" \"A123\"-", listOf(GrepSource(0, body)), GrepOptions(contextChars = 1))
        val hit = result.samples.single()
        assertEquals(3, hit.matchStart)
        assertEquals("　“Ａ１２３”—", hit.matchedText)
        assertEquals(hit.matchedText, body.substring(hit.matchStart, hit.matchEnd))
        assertEquals(hit.contextText, body.substring(hit.contextStart, hit.contextEnd))
        assertEquals(0, BookGrep.scan("A123", listOf(GrepSource(0, body)), GrepOptions(normalize = false)).count)
    }

    @Test fun exactDigitsDoNotFuzzAndCaseIsSignificant() = runTest {
        val source = listOf(GrepSource(0, "123 124 １２３ 一二三 abc ABC"))
        assertEquals(2, BookGrep.scan("123", source, GrepOptions()).count)
        assertEquals(1, BookGrep.scan("123", source, GrepOptions(normalize = false)).count)
        assertEquals(1, BookGrep.scan("abc", source, GrepOptions()).count)
        assertEquals(0, BookGrep.scan("125", source, GrepOptions()).count)
    }

    @Test fun nonOverlappingAndNewlinesButNeverCrossChapter() = runTest {
        assertEquals(1, BookGrep.scan("aa", listOf(GrepSource(0, "aaa")), GrepOptions()).count)
        assertEquals(2, BookGrep.scan("aba", listOf(GrepSource(0, "ababaaba")), GrepOptions()).count)
        assertEquals(1, BookGrep.scan("甲\n乙", listOf(GrepSource(0, "甲\n乙")), GrepOptions()).count)
        assertEquals(0, BookGrep.scan("甲乙", listOf(GrepSource(0, "甲"), GrepSource(1, "乙")), GrepOptions()).count)
        assertEquals(1, BookGrep.scan(" ", listOf(GrepSource(0, "甲 乙")), GrepOptions()).count)
    }

    @Test fun patternValidation() {
        for (pattern in listOf("", "a".repeat(201))) {
            try { BookGrep.validatePattern(pattern); fail("must reject") }
            catch (_: BookGrep.InvalidPattern) { }
        }
        BookGrep.validatePattern(" ")
        BookGrep.validatePattern("a".repeat(200))
    }

    @Test fun missingAndThrownReadsCannotBecomeCompleteZero() = runTest {
        val result = BookGrep.scan("缺席", listOf(0, 1, 2), { index ->
            when (index) {
                0 -> GrepSource(0, "正常正文")
                1 -> GrepSource(1, null, "INVALID_TEXT")
                else -> error("unavailable")
            }
        })
        assertEquals(GrepStatus.PARTIAL, result.status)
        assertFalse(result.countIsExact)
        assertEquals(0, result.count)
        assertEquals(listOf(1, 2), result.unreadableChapters)
    }

    @Test fun resourceLimitsAreExplicitPartialLowerBounds() = runTest {
        val result = BookGrep.scan("a", listOf(0, 1), { GrepSource(it, "aaaa") }, limits = GrepLimits(maxScanChars = 3))
        assertEquals(3, result.count)
        assertEquals(GrepStatus.PARTIAL, result.status)
        assertTrue(result.resourceLimited)
        val chapters = BookGrep.scan("a", listOf(0, 1), { GrepSource(it, "a") }, limits = GrepLimits(maxChapters = 1))
        assertEquals(1, chapters.count)
        assertFalse(chapters.countIsExact)
    }

    @Test fun cancelledScanPropagatesInsteadOfReportingZero() = runTest {
        try {
            BookGrep.scan("a", listOf(0), { throw CancellationException("stop") })
            fail("must cancel")
        } catch (_: CancellationException) { }
    }

    @Test fun outputAndChapterDistributionHaveExplicitBounds() = runTest {
        val result = BookGrep.scan("猫", (0..220).map { GrepSource(it, "猫".repeat(700)) }, GrepOptions(maxSamples = 50, contextChars = 300))
        assertEquals(221 * 700, result.count)
        assertTrue(result.countIsExact)
        assertEquals(200, result.byChapter.size)
        assertTrue(result.byChapterTruncated)
        assertTrue(result.samples.size < 50)
        assertTrue(result.samplesTruncated)
        assertNotNull(result.nextSkip)
    }

    @Test fun coverageChangesWhenMissingChapterRecovers() = runTest {
        val missing = BookGrep.scan("a", listOf(GrepSource(0, "aa"), GrepSource(1, null)), GrepOptions(maxSamples = 1))
        val recovered = BookGrep.scan("a", listOf(GrepSource(0, "aa"), GrepSource(1, "")), GrepOptions(maxSamples = 1))
        assertNotEquals(missing.coverageSignature, recovered.coverageSignature)
    }

    @Test fun contextDoesNotSplitEmoji() = runTest {
        val body = "😀猫😀"
        val hit = BookGrep.scan("猫", listOf(GrepSource(0, body)), GrepOptions(contextChars = 1)).samples.single()
        assertEquals(body, hit.contextText)
        assertEquals(2, hit.matchStart)
        assertEquals(3, hit.matchEnd)
    }

    @Test fun adversarialLiteralIsNotExecutedAsRegex() = runTest {
        val result = BookGrep.scan("(a+)+b", listOf(GrepSource(0, "a".repeat(200_000))), GrepOptions())
        assertTrue(result.countIsExact)
        assertEquals(0, result.count)
    }
}
