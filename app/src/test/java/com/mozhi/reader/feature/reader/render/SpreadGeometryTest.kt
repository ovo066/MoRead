package com.mozhi.reader.feature.reader.render

import androidx.compose.ui.geometry.Offset
import com.mozhi.reader.feature.reader.engine.RenderPage
import com.mozhi.reader.feature.reader.engine.TextPage
import org.junit.Assert.*
import org.junit.Test

class SpreadGeometryTest {
    private val geometry = SpreadGeometry(880f, 800f, 40f)
    private fun page(index: Int) = RenderPage.Laid(102, "102", index, 3,
        TextPage(index, emptyList(), index * 100, 100, 600f))

    @Test
    fun `geometry gives equal leaves and a noninteractive spine gutter`() {
        assertEquals(420f, geometry.leafWidth, 0f)
        assertEquals(460f, geometry.rightOriginX, 0f)
        assertEquals(440f, geometry.spineX, 0f)
        assertEquals(Leaf.LEFT, geometry.leafAt(419.9f))
        assertNull(geometry.leafAt(420f))
        assertNull(geometry.leafAt(459.9f))
        assertEquals(Leaf.RIGHT, geometry.leafAt(460f))
        assertNull(geometry.leafAt(880f))
        assertNull(geometry.leafAt(-1f))
    }

    @Test
    fun `right leaf transforms and hits use the same origin as rendering`() {
        val position = Offset(500f, 50f)
        val hit = geometry.hitAt(position, page(0) to page(1))!!
        assertEquals(Leaf.RIGHT, hit.leaf)
        assertEquals(1, hit.page.pageIndex)
        assertEquals(Offset(40f, 50f), hit.local)
        assertEquals(position, hit.local + Offset(geometry.originX(hit.leaf), 0f))
        assertEquals(geometry.rightOriginX, geometry.leafSrcRect(Leaf.RIGHT).left, 0f)
    }

    @Test
    fun `blank placeholder and gutter have no text hit`() {
        val blank = RenderPage.Blank(102, "102")
        assertNull(geometry.hitAt(Offset(500f, 50f), page(2) to blank))
        assertNull(geometry.hitAt(Offset(440f, 50f), page(0) to page(1)))
        assertNull(geometry.hitAt(Offset(10f, 810f), page(0) to page(1)))
        assertNull(geometry.hitAt(Offset(500f, 50f), page(0) to RenderPage.Placeholder(102, "102")))
    }
}
