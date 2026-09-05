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
            shown.indexOf("鼠标"),
            ReaderTextAnchors.resolve(
                shown,
                anchor,
                ChineseConversionMode.TW2SP,
                converter
            )!!.start
        )
    }
}
