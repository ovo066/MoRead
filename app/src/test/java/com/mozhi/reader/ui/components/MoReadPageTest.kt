package com.mozhi.reader.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 二级页折叠抬头的淡入曲线。
 *
 * 边界写错在真机上很难发现：要么页面停在顶部时紧凑标题就已经显形（和大标题重复），
 * 要么滚很远都不出来（顶栏空着一条）。这两种都得滚半天才注意到，所以钉在单测里。
 */
class MoReadPageTest {

    @Test
    fun `停在顶部时紧凑标题完全透明`() {
        assertEquals(0f, collapsedTitleAlpha(0, 0), 1e-4f)
    }

    @Test
    fun `大标题滚出视口后紧凑标题完全显现`() {
        assertEquals(1f, collapsedTitleAlpha(1, 0), 1e-4f)
        assertEquals(1f, collapsedTitleAlpha(5, 300), 1e-4f)
    }

    @Test
    fun `首项滚动过程中线性淡入`() {
        assertEquals(0.5f, collapsedTitleAlpha(0, TITLE_COLLAPSE_DISTANCE_PX / 2), 1e-4f)
        assertEquals(1f, collapsedTitleAlpha(0, TITLE_COLLAPSE_DISTANCE_PX), 1e-4f)
    }

    @Test
    fun `超过淡入距离后不会溢出到大于一`() {
        assertEquals(1f, collapsedTitleAlpha(0, TITLE_COLLAPSE_DISTANCE_PX * 4), 1e-4f)
    }

    @Test
    fun `过冲产生的负偏移不会变成负透明度`() {
        assertEquals(0f, collapsedTitleAlpha(0, -40), 1e-4f)
    }
}
