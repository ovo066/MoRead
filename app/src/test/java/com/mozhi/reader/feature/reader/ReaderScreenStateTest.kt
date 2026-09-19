package com.mozhi.reader.feature.reader

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.*
import org.junit.Test

class ReaderScreenStateTest {
    @Test fun typographyCardAndNavigationSheetsAreMutuallyExclusive() {
        val state = ReaderScreenState()
        repeat(3) {
            state.activeSheet = ReaderSheet.SETTINGS
            state.openTypographyCard()
            assertNull(state.activeSheet)
            assertTrue(state.typographyCardVisible)
            // A late sheet-dismiss callback must not close the newly opened card.
            state.activeSheet = null
            assertTrue(state.typographyCardVisible)
            state.returnToTypographySheet()
            assertEquals(ReaderSheet.SETTINGS, state.activeSheet)
            assertFalse(state.typographyCardVisible)
            state.typographyCardVisible = false
            assertEquals(ReaderSheet.SETTINGS, state.activeSheet)
        }
        state.activeSheet = ReaderSheet.CONTENTS
        assertFalse(state.typographyCardVisible)
    }

    @Test fun childDialogDismissalRetainsItsParentSheet() {
        val state = ReaderScreenState(chromeVisible = false)
        state.activeSheet = ReaderSheet.TEXT_REPLACEMENT_RULES
        state.aiTextRuleDialogVisible = true
        assertFalse(state.allowsPageInput(true, false))
        state.aiTextRuleDialogVisible = false
        assertEquals(ReaderSheet.TEXT_REPLACEMENT_RULES, state.activeSheet)
        assertTrue(state.blocksAutoRead(false, false))
    }

    @Test fun everyModalUsesTheSamePageInputAndAutomaticReadingGate() {
        val openers: List<(ReaderScreenState) -> Unit> = ReaderSheet.entries.map { sheet ->
            { state: ReaderScreenState -> state.activeSheet = sheet }
        } + listOf(
            { it.openTypographyCard() },
            { it.showBookDetails() },
            { it.inkFloater = AnnotationInkFloater(1, 24) },
            { it.annotationThread = AnnotationThreadKey(setOf(1)) },
            { it.ttsDraft = "原文" },
            { it.textEditDraft = TextEditDraft(0, 0..2, "原文") },
            { it.aiTextRuleDialogVisible = true },
            { it.listenTimerVisible = true },
            { it.epubImage = com.mozhi.reader.feature.reader.engine.ReaderPageImage("art.png", 2, 40) }
        )
        openers.forEach { open ->
            val state = ReaderScreenState(chromeVisible = false)
            assertTrue(state.allowsPageInput(true, false))
            assertFalse(state.blocksAutoRead(false, false))
            open(state)
            assertFalse(state.allowsPageInput(true, false))
            assertTrue(state.blocksAutoRead(false, false))
        }
    }

    @Test fun chromeAndCompanionPauseAutomaticReadingWithoutDisablingManualPageTurns() {
        val state = ReaderScreenState()
        assertTrue(state.blocksAutoRead(false, false))
        assertTrue(state.allowsPageInput(true, false))
        state.chromeVisible = false
        assertTrue(state.blocksAutoRead(true, false))
        assertTrue(state.allowsPageInput(true, false))
        assertFalse(state.allowsPageInput(false, false))
        assertFalse(state.allowsPageInput(true, true))
    }

    @Test fun restorationKeepsChromeAndDetailsButDropsTransientDialogs() {
        val state = ReaderScreenState()
        state.showBookDetails()
        state.ttsDraft = "原文"
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = true
        }
        val saved = with(ReaderScreenState.Saver) { scope.save(state) }!!
        val restored = ReaderScreenState.Saver.restore(saved)!!
        assertTrue(restored.detailsVisible)
        assertFalse(restored.chromeVisible)
        assertNull(restored.ttsDraft)
        restored.closeBookDetails()
        assertFalse(restored.detailsVisible)
        assertTrue(restored.chromeVisible)
    }
}
