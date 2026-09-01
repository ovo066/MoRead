package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.text.ChineseTextConverter
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextAnchorTest {
    private val converter = ChineseTextConverter()

    @Test
    fun pointAnchorReturnsTheSameBoundaryInTheSameMode() {
        val body = "开头。目标段落。结尾。"
        val offset = body.indexOf("目标")
        val anchor = ReaderTextAnchors.create(
            body,
            offset,
            offset,
            ChineseConversionMode.OFF
        )

        assertEquals(
            ResolvedTextAnchor(offset, offset),
            ReaderTextAnchors.resolve(body, anchor, ChineseConversionMode.OFF, converter)
        )

        val regionalSource = "程式碼。滑鼠。"
        val regionalOffset = regionalSource.indexOf('鼠')
        val regionalAnchor = ReaderTextAnchors.create(
            regionalSource,
            regionalOffset,
            regionalOffset,
            ChineseConversionMode.OFF
        )
        val regionalShown = converter.convert(regionalSource, ChineseConversionMode.TW2SP)
        assertEquals(
            ResolvedTextAnchor(
                regionalShown.indexOf('标'),
                regionalShown.indexOf('标')
            ),
            ReaderTextAnchors.resolve(
                regionalShown,
                regionalAnchor,
                ChineseConversionMode.TW2SP,
                converter
            )
        )

        val reverseSource = "代码。鼠标。鼠标鼠标。"
        val reverseOffset = reverseSource.indexOf('标')
        val reverseAnchor = ReaderTextAnchors.create(
            reverseSource,
            reverseOffset,
            reverseOffset,
            ChineseConversionMode.OFF
        )
        val reverseShown = converter.convert(reverseSource, ChineseConversionMode.S2TWP)
        assertEquals(
            ResolvedTextAnchor(
                reverseShown.indexOf('鼠'),
                reverseShown.indexOf('鼠')
            ),
            ReaderTextAnchors.resolve(
                reverseShown,
                reverseAnchor,
                ChineseConversionMode.S2TWP,
                converter
            )
        )
    }

    @Test
    fun selectionAnchorFollowsRegionalConversion() {
        val source = "前文。滑鼠裡面的程式碼。後文。"
        val start = source.indexOf("滑鼠")
        val end = source.indexOf("。後文")
        val anchor = ReaderTextAnchors.create(
            source,
            start,
            end,
            ChineseConversionMode.OFF
        )
        val shown = converter.convert(source, ChineseConversionMode.TW2SP)
        val resolved = ReaderTextAnchors.resolve(
            shown,
            anchor,
            ChineseConversionMode.TW2SP,
            converter
        )!!

        assertEquals("鼠标里面的代码", shown.substring(resolved.start, resolved.end))
    }

    @Test
    fun contextAndRatioChooseTheCorrectRepeatedQuote() {
        val body = "甲段目标文字结束。乙段目标文字结束。"
        val start = body.lastIndexOf("目标文字")
        val anchor = ReaderTextAnchors.create(
            body,
            start,
            start + 4,
            ChineseConversionMode.OFF
        )

        assertEquals(
            start,
            ReaderTextAnchors.resolve(body, anchor, ChineseConversionMode.OFF, converter)!!.start
        )
    }
}
