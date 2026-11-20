package com.mozhi.reader.feature.reader

import com.mozhi.reader.feature.reader.engine.ReaderIllustrationMark
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedArtAdmissionTest {
    private fun mark(id: Long, chapter: Int, offset: Int) = ReaderIllustrationMark(id, chapter, offset, offset + 20)
    private val guard = EmbeddedArtAdmission.PAGE_GUARD_CHARS

    @Test fun picturesInOtherChaptersAreAlwaysLaidOut() {
        val admission = EmbeddedArtAdmission()
        val marks = listOf(mark(1, 2, 10), mark(2, 4, 10))
        assertEquals(setOf(1L, 2L), admission.admit(currentChapter = 3, readerOffset = 0, fresh = false, marks = marks))
    }

    @Test fun onEnteringAChapterEverythingAlreadyThereIsLaidOut() {
        val admission = EmbeddedArtAdmission()
        val marks = listOf(mark(1, 3, 10), mark(2, 3, 5_000))
        assertEquals(setOf(1L, 2L), admission.admit(3, readerOffset = 4_000, fresh = true, marks = marks))
        // 放行过的不会再收回去。
        assertEquals(setOf(1L, 2L), admission.admit(3, readerOffset = 4_000, fresh = false, marks = marks))
    }

    @Test fun whileReadingOnlyPicturesWellAheadOfThePageArrive() {
        val admission = EmbeddedArtAdmission()
        assertEquals(emptySet<Long>(), admission.admit(3, 1_000, fresh = true, marks = emptyList()))
        val behind = mark(1, 3, 400)
        val onPage = mark(2, 3, 1_200)
        val justPastGuard = mark(3, 3, 1_000 + guard + 1)
        val atGuard = mark(4, 3, 1_000 + guard)
        val marks = listOf(behind, onPage, justPastGuard, atGuard)
        assertEquals(setOf(3L), admission.admit(3, 1_000, fresh = false, marks = marks))
        // 读者往回翻，先前被按住的图也不会忽然在眼前冒出来。
        assertEquals(setOf(3L), admission.admit(3, 0, fresh = false, marks = marks))
    }

    @Test fun heldPicturesAreLaidOutOnceTheReaderLeavesTheChapter() {
        val admission = EmbeddedArtAdmission()
        admission.admit(3, 1_000, fresh = true, marks = emptyList())
        val marks = listOf(mark(1, 3, 400), mark(2, 4, 100))
        assertEquals(setOf(2L), admission.admit(3, 1_000, fresh = false, marks = marks))
        // 翻到下一章：上一章按住的图现在可以排了；新一章刚进入，已有的图同样放行。
        assertEquals(setOf(1L, 2L), admission.admit(4, 0, fresh = true, marks = marks))
        // 回到上一章时重新按「刚进入」算。
        assertEquals(setOf(1L, 2L), admission.admit(3, 0, fresh = true, marks = marks))
    }
}
