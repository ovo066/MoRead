package com.mozhi.reader.feature.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.mozhi.reader.core.datastore.CustomReaderTheme
import com.mozhi.reader.core.datastore.CustomReaderThemeCodec
import org.junit.Assert.*
import org.junit.Test

class ReaderAutomaticInkTest {
    @Test fun autoInkRemainsReadableOnLightDarkAndMidtonePaper() {
        listOf(0xFFFAF3E6, 0xFF111315, 0xFF777777, 0xFF446B65, 0xFFFFCC33).forEach { paper ->
            val background = Color(paper.toInt())
            val theme = CustomReaderTheme(1, "自动", paper.toInt(), paper.toInt(), 0, textColorCustomized = false)
            val ink = customReaderPalette(theme).onBackground
            val ratio = (maxOf(background.luminance(), ink.luminance()) + .05f) /
                (minOf(background.luminance(), ink.luminance()) + .05f)
            assertTrue("Contrast for $paper was $ratio", ratio >= 4.5f)
            assertEquals(ink, customReaderPalette(CustomReaderThemeCodec.decode(CustomReaderThemeCodec.encode(listOf(theme))).single()).onBackground)
        }
    }

    @Test fun manualAndLegacyInkAreNeverOverwritten() {
        val manual = CustomReaderTheme(1, "手动", Color.Black.toArgb(), Color.DarkGray.toArgb(), 0)
        assertEquals(Color.DarkGray, customReaderPalette(manual).onBackground)
        val legacy = CustomReaderThemeCodec.decode("""[{"id":1,"name":"旧主题","backgroundArgb":-16777216,"textArgb":-123456,"accentArgb":0}]""").single()
        assertTrue(legacy.textColorCustomized)
        assertEquals(-123456, customReaderPalette(legacy).onBackground.toArgb())
        assertEquals(Color(0xFF303234), readableReaderText(Color.White, Color(0xFF303234)))
    }
}
