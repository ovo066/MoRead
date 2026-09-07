package com.mozhi.reader.core.text

import com.mozhi.reader.core.datastore.BookChineseConversionCodec
import com.mozhi.reader.core.datastore.ChineseConversionMode
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import openccjava.OpenCC
import openccjava.OpenccConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseTextConverterTest {
    private val converter = ChineseTextConverter()

    @Test
    fun disabledConversionDoesNotLoadDictionaries() {
        mockkStatic(OpenCC::class)
        try {
            assertEquals("原文", converter.convert("原文", ChineseConversionMode.OFF))
            verify(exactly = 0) { OpenCC.convert(any<String>(), any<OpenccConfig>()) }
        } finally {
            unmockkStatic(OpenCC::class)
        }
    }

    @Test
    fun dictionaryFailureKeepsOriginalTextReadable() {
        mockkStatic(OpenCC::class)
        try {
            every { OpenCC.convert(any<String>(), any<OpenccConfig>()) } throws
                RuntimeException("Failed to load dictionaries")
            assertEquals("主機板", converter.convert("主機板", ChineseConversionMode.TW2SP))
            converter.warmUp()
        } finally {
            unmockkStatic(OpenCC::class)
        }
    }

    @Test
    fun failedStaticInitializationKeepsSubsequentConversionsReadable() {
        mockkStatic(OpenCC::class)
        try {
            every { OpenCC.convert(any<String>(), any<OpenccConfig>()) } throws
                NoClassDefFoundError("DictionaryHolder")
            assertEquals("主板", converter.convert("主板", ChineseConversionMode.S2TWP))
            converter.warmUp()
        } finally {
            unmockkStatic(OpenCC::class)
        }
    }

    @Test
    fun tw2spUsesMainlandTerms() {
        assertEquals(
            "鼠标里面的硅二极管坏了，导致光标分辨率降低。",
            converter.convert(
                "滑鼠裡面的矽二極體壞了，導致游標解析度降低。",
                ChineseConversionMode.TW2SP
            )
        )
    }

    @Test
    fun s2twpUsesTaiwanTerms() {
        assertEquals(
            "滑鼠裡面的矽二極體壞了，導致游標解析度降低。",
            converter.convert(
                "鼠标里面的硅二极管坏了，导致光标分辨率降低。",
                ChineseConversionMode.S2TWP
            )
        )
    }

    @Test
    fun bookModeMapRoundTrips() {
        val values = mapOf(
            7L to ChineseConversionMode.TW2SP,
            9L to ChineseConversionMode.S2TWP
        )
        assertEquals(values, BookChineseConversionCodec.decode(BookChineseConversionCodec.encode(values)))
    }
}
