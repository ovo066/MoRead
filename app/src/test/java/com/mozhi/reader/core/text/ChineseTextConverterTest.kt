package com.mozhi.reader.core.text

import com.mozhi.reader.core.datastore.BookChineseConversionCodec
import com.mozhi.reader.core.datastore.ChineseConversionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseTextConverterTest {
    private val converter = ChineseTextConverter()

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
