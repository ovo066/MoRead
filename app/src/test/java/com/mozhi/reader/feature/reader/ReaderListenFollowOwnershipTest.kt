package com.mozhi.reader.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderListenFollowOwnershipTest {
    @Test
    fun delayedFollowPositionIsConsumedBeforeManualSeekAfterSentenceAdvances() {
        val followA = PendingReaderListenFollow(
            chapterIndex = 1,
            displayOffset = 120
        )

        val unrelated = readerListenPositionOwnership(followA, 1, 160)
        assertEquals(followA, unrelated.pending)
        assertFalse(unrelated.isManualSeek)

        val delayedA = readerListenPositionOwnership(unrelated.pending, 1, 120)
        assertEquals(null, delayedA.pending)
        assertFalse(delayedA.isManualSeek)

        assertTrue(readerListenPositionOwnership(null, 1, 160).isManualSeek)
    }
}
