package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CssColorTest {
    @Test
    fun `hex rgb hsl transparency and current color parse`() {
        assertEquals(0xFFAABBCC.toInt(), CssColor.parse("#abc"))
        assertEquals(0xDDAABBCC.toInt(), CssColor.parse("#abcd"))
        assertEquals(0x80FF0000.toInt(), CssColor.parse("rgb(100% 0% 0% / 50%)"))
        assertEquals(0x4000FF00, CssColor.parse("rgba(0, 255, 0, .25)"))
        assertEquals(0xFF00FFFF.toInt(), CssColor.parse("hsl(180 100% 50%)"))
        assertEquals(0, CssColor.parse("transparent"))
        assertEquals(CssColor.CURRENT_COLOR, CssColor.parse("currentColor"))
    }

    @Test
    fun `level four named color table is complete and representative names parse`() {
        listOf(
            "aliceblue", "antiquewhite", "aquamarine", "blanchedalmond", "cornflowerblue",
            "darkgoldenrod", "darkslategrey", "deeppink", "floralwhite", "gainsboro",
            "greenyellow", "lavenderblush", "lightgoldenrodyellow", "mediumaquamarine",
            "mediumvioletred", "navajowhite", "palevioletred", "rebeccapurple", "seashell", "yellowgreen"
        ).forEach { assertNotNull(it, CssColor.parse(it)) }
    }
}
