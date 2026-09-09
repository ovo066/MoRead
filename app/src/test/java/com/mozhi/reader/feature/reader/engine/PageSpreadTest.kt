package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.*
import org.junit.Test

class PageSpreadTest {
    @Test
    fun `pairs are chapter local and odd last leaf is explicit blank`() {
        assertEquals(listOf(0, 0, 2, 2, 4), (0..4).map(::spreadFor))
        assertEquals(listOf(0, 1, 1, 2, 2, 3), (0..5).map(::spreadCountOf))
        val chapter = TextChapter(102, "第102章", List(5) {
            TextPage(it, emptyList(), it * 100, 100, 100f)
        }, 500)
        assertEquals(1, rightPageOrBlank(chapter, 0).pageIndex)
        assertEquals("2 / 5 页", leafPageLabel(rightPageOrBlank(chapter, 0)))
        assertEquals("本章完", leafPageLabel(rightPageOrBlank(chapter, 4)))
        assertEquals("加载中", leafPageLabel(RenderPage.Placeholder(102, "102")))
        assertEquals(RenderPage.Blank(102, "第102章"), rightPageOrBlank(chapter, 4))
        assertEquals("3–4 / 5", spreadPageLabel(3, 5))
        assertEquals("5 / 5", spreadPageLabel(4, 5))
    }
}
