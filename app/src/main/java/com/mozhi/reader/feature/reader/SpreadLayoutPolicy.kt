package com.mozhi.reader.feature.reader

import com.mozhi.reader.core.datastore.PageMode
import com.mozhi.reader.core.datastore.WidePageLayout
import com.mozhi.reader.ui.MoReadLayoutPolicy

/** Window eligibility is not enough: a companion panel can consume the second leaf's width. */
object SpreadLayoutPolicy {
    const val GUTTER_DP = 40f
    // Design: the 40dp painted spine has a 48dp chrome target, extending 4dp into each leaf.
    const val CHROME_TOUCH_DP = 48f
    const val SINGLE_COLUMN_DP = 640f

    /** This limits typesetting, never the parent measurement used to decide dual eligibility. */
    fun singleColumnWidthDp(paneWidthDp: Float): Float = paneWidthDp.coerceAtMost(SINGLE_COLUMN_DP)

    fun resolve(
        setting: WidePageLayout,
        paneWidthDp: Float,
        windowWidthDp: Float,
        pageMode: PageMode
    ): Boolean = setting == WidePageLayout.DUAL && pageMode == PageMode.PAGINATED &&
        MoReadLayoutPolicy.allowsDualPage(windowWidthDp, paneWidthDp)
}
