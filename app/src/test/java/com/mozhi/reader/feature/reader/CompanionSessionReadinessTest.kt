package com.mozhi.reader.feature.reader

import org.junit.Assert.*
import org.junit.Test

class CompanionSessionReadinessTest {
    @Test fun unboundBookNeverExposesAnEmptyReadyConversation() {
        assertFalse(companionSessionMatches(null, null, 1, 1))
        assertFalse(companionSessionMatches(7, null, 1, 1))
        assertFalse(companionSessionMatches(7, 8, 1, 1))
    }

    @Test fun changedPersonaWaitsForMatchingHistory() {
        assertFalse(companionSessionMatches(7, 7, 2, 1))
        assertFalse(companionSessionMatches(7, 7, 2, null))
        assertTrue(companionSessionMatches(7, 7, 2, 2))
    }

    @Test fun aLoadedBookWithNoPersonaMayShowTheEmptyState() {
        assertTrue(companionSessionMatches(7, 7, null, null))
    }
}
