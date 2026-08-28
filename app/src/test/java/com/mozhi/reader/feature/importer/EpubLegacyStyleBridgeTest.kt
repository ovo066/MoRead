package com.mozhi.reader.feature.importer

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubLegacyStyleBridgeTest {

    @Test
    fun `rgba backgrounds and asymmetric borders are resolved`() {
        val document = Jsoup.parse("<div id='card'>内容</div>")
        val styles = EpubLegacyStyleBridge.parse(
            listOf(
                EpubStylesheetSource(
                    href = "OEBPS/Styles/style.css",
                    css = """
                        #card {
                            background: rgba(0, 0, 0, 0.4);
                            border: 1px solid #cccccc;
                            border-left: 4px solid #3b7dd8;
                            border-radius: 10px 0 10px 10px;
                        }
                    """.trimIndent()
                )
            )
        )

        val style = styles.styleFor(document.getElementById("card")!!)

        assertEquals(0x66000000, style.backgroundColorArgb)
        assertEquals(1f / 16f, style.borderTopWidthEm!!, 0.0001f)
        assertEquals(4f / 16f, style.borderLeftWidthEm!!, 0.0001f)
        assertEquals(0xFFCCCCCC.toInt(), style.borderTopColorArgb)
        assertEquals(0xFF3B7DD8.toInt(), style.borderLeftColorArgb)
        assertEquals(10f / 16f, style.borderTopLeftRadiusEm!!, 0.0001f)
        assertEquals(0f, style.borderTopRightRadiusEm!!, 0.0001f)
        assertEquals(10f / 16f, style.borderBottomRightRadiusEm!!, 0.0001f)
        assertEquals(10f / 16f, style.borderBottomLeftRadiusEm!!, 0.0001f)
    }

    @Test
    fun `single side border does not expand to a full rectangle`() {
        val document = Jsoup.parse("<p id='note'>说明</p>")
        val styles = EpubLegacyStyleBridge.parse(
            listOf(EpubStylesheetSource("note.css", "#note { border-left: 5px solid #B5A9C8; }"))
        )

        val style = styles.styleFor(document.getElementById("note")!!)

        assertNull(style.borderWidthEm)
        assertNull(style.borderTopWidthEm)
        assertNull(style.borderRightWidthEm)
        assertNull(style.borderBottomWidthEm)
        assertEquals(5f / 16f, style.borderLeftWidthEm!!, 0.0001f)
    }

    @Test
    fun `display none hides descendants`() {
        val document = Jsoup.parse("<div id='hidden'><p id='child'>内容</p></div>")
        val styles = EpubLegacyStyleBridge.parse(
            listOf(EpubStylesheetSource("OEBPS/Styles/style.css", "#hidden { display: none; }"))
        )

        assertTrue(styles.styleFor(document.getElementById("child")!!).hidden)
    }

    @Test
    fun `multiple outer and inset box shadows are resolved`() {
        val document = Jsoup.parse("<div id='card'>内容</div>")
        val styles = EpubLegacyStyleBridge.parse(
            listOf(
                EpubStylesheetSource(
                    "OEBPS/Styles/style.css",
                    """
                        #card {
                            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.12),
                                inset 0 0 0 1px rgba(255, 255, 255, 0.2);
                        }
                    """.trimIndent()
                )
            )
        )

        val shadows = styles.styleFor(document.getElementById("card")!!).boxShadows

        assertEquals(2, shadows.size)
        assertEquals(2f / 16f, shadows[0].offsetYEm, 0.0001f)
        assertEquals(8f / 16f, shadows[0].blurRadiusEm, 0.0001f)
        assertEquals(0x1E000000, shadows[0].colorArgb)
        assertTrue(shadows[1].inset)
        assertEquals(1f / 16f, shadows[1].spreadRadiusEm, 0.0001f)
        assertEquals(0x33FFFFFF, shadows[1].colorArgb)
        assertTrue("box-shadow" !in styles.unsupportedProperties)
    }

    @Test
    fun `child and adjacent selectors preserve card layout rules`() {
        val document = Jsoup.parse(
            "<div class='card'><p id='first'>甲</p><p id='second'>乙</p><section><p id='nested'>丙</p></section></div>"
        )
        val styles = EpubLegacyStyleBridge.parse(
            listOf(
                EpubStylesheetSource(
                    "OEBPS/Styles/style.css",
                    ".card > p { color: #123456; } .card p + p { margin-top: 0; font-weight: bold; }"
                )
            )
        )

        assertEquals(0xFF123456.toInt(), styles.styleFor(document.getElementById("first")!!).colorArgb)
        val second = styles.styleFor(document.getElementById("second")!!)
        assertEquals(0xFF123456.toInt(), second.colorArgb)
        assertEquals(0f, second.marginTopEm)
        assertEquals(700, second.fontWeight)
        assertEquals(null, styles.styleFor(document.getElementById("nested")!!).colorArgb)
    }

    @Test
    fun `modern break values and widow controls are preserved`() {
        val document = Jsoup.parse("<h2 id='title'>标题</h2>")
        val styles = EpubLegacyStyleBridge.parse(
            listOf(
                EpubStylesheetSource(
                    "OEBPS/Styles/style.css",
                    "#title { break-after: avoid; orphans: 3; widows: 4; }"
                )
            )
        )

        val style = styles.styleFor(document.getElementById("title")!!)
        assertTrue(style.avoidBreakAfter)
        assertEquals(3, style.orphans)
        assertEquals(4, style.widows)
    }
}
