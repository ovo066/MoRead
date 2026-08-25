package com.mozhi.reader.feature.importer

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubCssStylesheetSetTest {

    @Test
    fun `rgba backgrounds and asymmetric borders are resolved`() {
        val document = Jsoup.parse("<div id='card'>内容</div>")
        val styles = EpubCssStylesheetSet.parse(
            listOf(
                EpubStylesheetSource(
                    href = "OEBPS/Styles/style.css",
                    css = """
                        #card {
                            background: rgba(0, 0, 0, 0.4);
                            border: 1px solid #cccccc;
                            border-left: 4px solid #3b7dd8;
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
    }

    @Test
    fun `display none hides descendants`() {
        val document = Jsoup.parse("<div id='hidden'><p id='child'>内容</p></div>")
        val styles = EpubCssStylesheetSet.parse(
            listOf(EpubStylesheetSource("OEBPS/Styles/style.css", "#hidden { display: none; }"))
        )

        assertTrue(styles.styleFor(document.getElementById("child")!!).hidden)
    }

    @Test
    fun `multiple outer and inset box shadows are resolved`() {
        val document = Jsoup.parse("<div id='card'>内容</div>")
        val styles = EpubCssStylesheetSet.parse(
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
}
