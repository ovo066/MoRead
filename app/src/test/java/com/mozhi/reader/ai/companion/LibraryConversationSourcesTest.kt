package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.chat.AiChatRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LibraryConversationSourcesTest {
    private val chats = mockk<AiChatRepository>()
    private val guard = mockk<LibraryScopeGuard>()
    private fun scope(id: Long) = LibraryBookScope(id, "书$id", "a".repeat(64), id.toInt(), 20)

    init {
        coEvery { chats.updateLibraryContext(any(), any(), any(), any()) } just Runs
        coEvery { guard.capture(any()) } answers { firstArg<List<Long>>().map(::scope) }
        coEvery { guard.validate(any<List<LibraryBookScope>>()) } just Runs
    }

    @Test fun discoveringAnUnfocusedBookCapturesItsOwnBoundaryAndOnlyAssociatesThisTurn() = runTest {
        val sources = LibraryConversationSources(7, "user-round", listOf(scope(1)), guard, chats)
        assertEquals(scope(2), sources.authorize(2))
        assertEquals(scope(2), sources.authorize(2))
        assertEquals(listOf(scope(1), scope(2)), sources.all)
        coVerify(exactly = 1) { guard.capture(listOf(2)) }
        coVerify(exactly = 1) { chats.updateLibraryContext(7, "user-round", LibraryBookScopes.encode(sources.all), "[2]") }
    }

    @Test fun idleConversationPersistsKnownEmptyAssociationsWithoutReadingAnyBook() = runTest {
        val sources = LibraryConversationSources(7, "u", emptyList(), guard, chats)
        sources.persist()
        sources.persist()
        coVerify(exactly = 1) { chats.updateLibraryContext(7, "u", "[]", "[]") }
        coVerify(exactly = 0) { guard.capture(any()) }
    }

    @Test fun focusedTitlesAreNotAnAccessWhitelistAndDoNotConsumeTheReadBudget() = runTest {
        val focused = (1L..4L).toList()
        val sources = LibraryConversationSources(7, "u", focused.map(::scope), guard, chats, focused)
        sources.persist()
        (5L..8L).forEach { sources.authorize(it) }
        assertTrue(runCatching { sources.authorize(9) }.isFailure)
        coVerify(exactly = 1) { chats.updateLibraryContext(7, "u", any(), "[1,2,3,4,5,6,7,8]") }
        coVerify(exactly = 4) { guard.capture(any()) }
    }

    @Test fun fourBookBudgetAndConversationBudgetFailBeforeReadingAnExtraSource() = runTest {
        val sources = LibraryConversationSources(7, "u", emptyList(), guard, chats)
        (1L..4L).forEach { sources.authorize(it) }
        assertTrue(runCatching { sources.authorize(5) }.isFailure)
        assertTrue(runCatching { sources.authorize(0) }.isFailure)
        coVerify(exactly = 0) { guard.capture(listOf(5)) }
        val full = LibraryConversationSources(8, "v", (1L..32L).map(::scope), guard, chats)
        assertTrue(runCatching { full.authorize(33) }.isFailure)
        assertEquals(scope(3), full.authorize(3))
    }

    @Test fun reaccessNeverRefreshesTheBoundaryInsideATurn() = runTest {
        val old = scope(1)
        val sources = LibraryConversationSources(7, "u", listOf(old), guard, chats)
        coEvery { guard.capture(listOf(1)) } returns listOf(old.copy(maxChapterIndex = 99))
        assertEquals(old, sources.authorize(1))
        sources.validate()
        coVerify(exactly = 0) { guard.capture(listOf(1)) }
        coVerify(exactly = 1) { guard.validate(listOf(old)) }
    }

    @Test fun failedMetadataWriteRetriesBeforeReturningMaterial() = runTest {
        coEvery { chats.updateLibraryContext(any(), any(), any(), any()) } throws IllegalStateException("write failed") andThen Unit
        val sources = LibraryConversationSources(7, "u", emptyList(), guard, chats)
        assertTrue(runCatching { sources.authorize(2) }.isFailure)
        assertEquals(scope(2), sources.authorize(2))
        coVerify(exactly = 1) { guard.capture(listOf(2)) }
        coVerify(exactly = 2) { chats.updateLibraryContext(any(), any(), any(), any()) }
    }
}
