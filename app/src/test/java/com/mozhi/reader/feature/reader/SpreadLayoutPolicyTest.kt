package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.WidePageLayout
import com.mozhi.reader.ui.MoReadLayoutPolicy
import org.junit.Assert.*
import org.junit.Test

class SpreadLayoutPolicyTest {
    private fun dual(pane: Float, window: Float = 1280f, mode: PageMode = PageMode.PAGINATED) =
        SpreadLayoutPolicy.resolve(WidePageLayout.DUAL, pane, window, mode)

    @Test
    fun `shared minimum pane leaves 340dp per page around the reader gutter`() {
        assertEquals(340f, (MoReadLayoutPolicy.MinReaderPaneWidthDp - SpreadLayoutPolicy.GUTTER_DP) / 2f, 0f)
        assertTrue(dual(MoReadLayoutPolicy.MinReaderPaneWidthDp, MoReadLayoutPolicy.ExpandedWidthDp))
    }

    @Test
    fun `window and actual pane thresholds both apply`() {
        assertTrue(dual(720f, 840f))
        assertFalse(dual(719.9f, 840f))
        assertFalse(dual(800f, 839.9f))
        assertFalse(dual(600f, 600f))
    }

    @Test
    fun `companion narrowing falls back without changing preference`() {
        assertTrue(dual(1280f - MoReadLayoutPolicy.CompanionPaneWidthDp))
        assertFalse(dual(1024f - MoReadLayoutPolicy.CompanionPaneWidthDp, 1024f))
        assertTrue(dual(1024f, 1024f))
    }

    @Test
    fun `single default and scroll never become spreads`() {
        assertFalse(SpreadLayoutPolicy.resolve(WidePageLayout.SINGLE, 1280f, 1280f, PageMode.PAGINATED))
        assertFalse(dual(1280f, mode = PageMode.SCROLL))
    }
}
