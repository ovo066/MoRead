package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveAnnotationQuotaTest {
    @Test
    fun chapterIsReservedOnceAndMediaCapsAreApplied() {
        val state = ProactiveAnnotationQuotaState(
            epochDay = 10,
            annotationCount = 8,
            voiceCount = 2,
            imageCount = 3
        )
        val (reserved, allowance) = evaluateProactiveAnnotationQuota(
            state,
            today = 10,
            chapterKey = "7:3",
            requestVoice = true,
            requestImages = true
        )

        assertTrue(allowance.accepted)
        assertEquals(2, allowance.maxAnnotations)
        assertEquals(1, allowance.maxVoice)
        assertEquals(0, allowance.maxImages)
        assertTrue("7:3" in reserved.attemptedChapters)

        val (_, repeated) = evaluateProactiveAnnotationQuota(
            reserved,
            today = 10,
            chapterKey = "7:3",
            requestVoice = true,
            requestImages = true
        )
        assertFalse(repeated.accepted)
    }

    @Test
    fun newDayResetsCountsAndAttempts() {
        val (_, allowance) = evaluateProactiveAnnotationQuota(
            ProactiveAnnotationQuotaState(
                epochDay = 9,
                attemptedChapters = setOf("7:3"),
                annotationCount = 10,
                voiceCount = 3,
                imageCount = 3
            ),
            today = 10,
            chapterKey = "7:3",
            requestVoice = true,
            requestImages = true
        )

        assertTrue(allowance.accepted)
        assertEquals(2, allowance.maxAnnotations)
        assertEquals(3, allowance.maxVoice)
        assertEquals(3, allowance.maxImages)
    }
}
