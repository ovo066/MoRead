package com.mozhi.reader.feature.importer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpubCssStylesheetSetAndroidTest {
    @Test
    fun fontFaceAndRulePatternsCompileOnAndroid() {
        val styles = EpubLegacyStyleBridge.parse(
            listOf(
                EpubStylesheetSource(
                    href = "OEBPS/Styles/style.css",
                    css = """
                        @font-face {
                            font-family: sample;
                            src: url('../Fonts/sample.ttf');
                        }
                        .chapter { text-align: center; }
                    """.trimIndent()
                )
            )
        )

        assertEquals(1, styles.fontFaces.size)
        assertEquals("sample", styles.fontFaces.single().family)
    }
}
