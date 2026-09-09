package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationQuotaTest {

    private val defaults = ProactiveAnnotationLimits()

    @Test
    fun ledgerOwnsRetryDedupAndDailyMediaCapsAreApplied() {
        val state = ProactiveAnnotationQuotaState(
            epochDay = 10,
            annotationCount = 8,
            voiceCount = 2,
            imageCount = 3
        )
        val (reserved, allowance) = evaluateProactiveAnnotationQuota(
            state,
            today = 10,
            limits = defaults,
            requestVoice = true,
            requestImages = true
        )

        assertTrue(allowance.accepted)
        assertEquals(2, allowance.maxAnnotations)
        assertEquals(1, allowance.maxVoice)
        assertEquals(0, allowance.maxImages)
        assertEquals(state, reserved)

        val (_, repeated) = evaluateProactiveAnnotationQuota(
            reserved,
            today = 10,
            limits = defaults,
            requestVoice = true,
            requestImages = true
        )
        assertTrue(repeated.accepted)
    }

    @Test
    fun newDayResetsAllDailyCounts() {
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(
                epochDay = 9,
                annotationCount = 10,
                voiceCount = 3,
                imageCount = 3
            ),
            today = 10,
            limits = defaults,
            requestVoice = true,
            requestImages = true
        )

        assertTrue(allowance.accepted)
        assertEquals(2, allowance.maxAnnotations)
        assertEquals(3, allowance.maxVoice)
        assertEquals(3, allowance.maxImages)
    }

    @Test
    fun customLimitsReplaceTheOldHardcodedNumbers() {
        val limits = ProactiveAnnotationLimits(
            minPerChapter = 2,
            maxPerChapter = 5,
            dailyMax = 30,
            dailyVoiceMax = 0,
            dailyImageMax = 8
        )
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(epochDay = 3, annotationCount = 12, imageCount = 1),
            today = 3,
            limits = limits,
            requestVoice = true,
            requestImages = true
        )

        assertTrue(allowance.accepted)
        assertEquals(5, allowance.maxAnnotations)
        assertEquals(2, allowance.minAnnotations)
        assertEquals(0, allowance.maxVoice)
        assertEquals(7, allowance.maxImages)
    }

    @Test
    fun dailyRemainderCapsThePerChapterCeiling() {
        val limits = ProactiveAnnotationLimits(maxPerChapter = 5, dailyMax = 10)
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(epochDay = 3, annotationCount = 8),
            today = 3,
            limits = limits,
            requestVoice = false,
            requestImages = false
        )
        assertEquals(2, allowance.maxAnnotations)
    }

    @Test
    fun unlimitedChapterAndDailyNeverBlock() {
        val limits = ProactiveAnnotationLimits(
            minPerChapter = 1,
            maxPerChapter = ProactiveAnnotationLimits.UNLIMITED,
            dailyMax = ProactiveAnnotationLimits.UNLIMITED
        )
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(epochDay = 3, annotationCount = 500),
            today = 3,
            limits = limits,
            requestVoice = false,
            requestImages = false
        )
        assertTrue(allowance.accepted)
        assertTrue(allowance.annotationsUnlimited)
    }

    @Test
    fun loweredDailyLimitBlocksImmediately() {
        // 旧版把计数夹在 10，用户把上限降到 3 后旧计数仍应判定为已用尽。
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(epochDay = 3, annotationCount = 6),
            today = 3,
            limits = ProactiveAnnotationLimits(dailyMax = 3),
            requestVoice = false,
            requestImages = false
        )
        assertFalse(allowance.accepted)
    }
}
