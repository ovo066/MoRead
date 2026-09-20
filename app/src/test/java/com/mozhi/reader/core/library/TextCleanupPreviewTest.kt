package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import org.junit.Assert.*
import org.junit.Test

class TextCleanupPreviewTest {
    @Test fun literalRulesTreatRegexSymbolsAndReplacementDollarsAsText() {
        val rule = ReaderTextReplacementRule(1, "literal", "[广告]", "$1", isRegex = false)
        assertEquals("故事$1\n结束" to 1, cleanText("故事[广告]\n结束", listOf(rule)))
    }
    @Test fun disabledAndListeningOnlyRulesCannotChangeBookText() {
        val rules = listOf(ReaderTextReplacementRule(1, "off", ".+", enabled = false),
            ReaderTextReplacementRule(2, "listen", ".+", forListenOnly = true))
        assertEquals("正文" to 0, cleanText("正文", rules))
    }
    @Test fun orderedRulesAndRevisionIncludeChapterBoundaries() {
        val rules = listOf(ReaderTextReplacementRule(1, "first", "广告", "PROMO"), ReaderTextReplacementRule(2, "second", "^PROMO\\n", ""))
        assertEquals("正文" to 2, cleanText("广告\n正文", rules))
        assertNotEquals(cleanupRevision(listOf(0 to "ab", 1 to "c")), cleanupRevision(listOf(0 to "a", 1 to "bc")))
    }
    @Test(timeout = 3000) fun pathologicalRegexStopsBeforeFreezingReader() {
        assertTrue(runCatching { cleanText("a".repeat(30000) + "!", listOf(ReaderTextReplacementRule(1, "bad", "(a+)+$"))) }.isFailure)
    }
}
