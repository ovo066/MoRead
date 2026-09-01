package com.mozhi.reader.ai.listen

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.library.ReaderTextAnchorCodec
import com.mozhi.reader.core.library.ReaderTextAnchors
import com.mozhi.reader.core.text.ChineseTextConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenProgressAnchorTest {
    @Test
    fun sourceProgressLocatorReopensAtTheConvertedSentence() {
        val source = "前面的程式碼很长。滑鼠裡面的程式碼。后文。"
        val offset = source.indexOf("滑鼠")
        val anchor = ReaderTextAnchorCodec.decode(listenProgressLocator(source, offset))!!
        val converter = ChineseTextConverter()
        val shown = converter.convert(source, ChineseConversionMode.TW2SP)

        assertEquals(ChineseConversionMode.OFF, anchor.mode)
        assertEquals(
            offset,
            resolveListenProgressOffset(source, listenProgressLocator(source, offset), 0, converter)
        )
        assertEquals(
            shown.indexOf("鼠标"),
            ReaderTextAnchors.resolve(
                shown,
                anchor,
                ChineseConversionMode.TW2SP,
                converter
            )!!.start
        )
    }

    @Test
    fun savedStartUsesAnchorAndLegacyLocatorFallsBack() {
        val source = "程式碼" + "甲".repeat(40) + "长目标" + "乙".repeat(40)
        val converter = ChineseTextConverter()
        val shown = converter.convert(source, ChineseConversionMode.TW2SP)
        val displayedOffset = shown.indexOf("目标")
        val locator = ReaderTextAnchorCodec.encode(
            ReaderTextAnchors.create(
                shown,
                displayedOffset,
                displayedOffset,
                ChineseConversionMode.TW2SP
            )
        )

        assertEquals(
            44,
            resolveListenProgressOffset(source, locator, displayedOffset, converter)
        )
        assertEquals(7, resolveListenProgressOffset(source, null, 7, converter))
        val legacyLocator =
            """{"href":"text/c1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.25}}"""
        assertEquals(7, resolveListenProgressOffset(source, legacyLocator, 7, converter))
    }
}
