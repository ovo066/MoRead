package com.mozhi.reader.core.library

import com.mozhi.reader.core.datastore.ChineseConversionMode
import com.mozhi.reader.core.text.ChineseTextConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextAnchorTest {
    private val converter = ChineseTextConverter()

    @Test
    fun repeatedOverlappingContextKeepsEveryBoundaryInTheSameMode() {
        val body = "甲".repeat(100)
        for (point in 0..body.length) {
            val anchor = ReaderTextAnchors.create(body, point, point, ChineseConversionMode.OFF)
            assertEquals(
                "point $point",
                ResolvedTextAnchor(point, point),
                ReaderTextAnchors.resolve(body, anchor, ChineseConversionMode.OFF, converter),
            )
        }
    }

    @Test
    fun everyRegionalPhraseBoundaryHasAnInBoundsPositionInBothDirections() {
        val source = "這個程式設計師正在檢查主機板與網際網路設定，並把資料庫裡的程式碼傳送給其他使用者。".repeat(4)
        val shown = converter.convert(source, ChineseConversionMode.TW2SP)
        for (point in 0..source.length) {
            val anchor = ReaderTextAnchors.create(source, point, point, ChineseConversionMode.OFF)
            val resolved = ReaderTextAnchors.resolve(shown, anchor, ChineseConversionMode.TW2SP, converter)
            assertNotNull("source boundary $point", resolved)
            assertTrue("source boundary $point", resolved!!.start in 0..shown.length)
        }
        for (point in 0..shown.length) {
            val anchor = ReaderTextAnchors.create(shown, point, point, ChineseConversionMode.TW2SP)
            val resolved = ReaderTextAnchors.resolveSourcePoint(source, shown, anchor, converter)
            assertNotNull("display boundary $point", resolved)
            assertTrue("display boundary $point", resolved!!.start in 0..source.length)
        }
    }

    @Test
    fun missingContextFallsBackToRatioAndEmptyBodyResolvesToZero() {
        val anchor = ReaderTextAnchors.create("甲乙目標丙丁戊己", 2, 4, ChineseConversionMode.OFF)
        assertEquals(
            ResolvedTextAnchor(1, 3),
            ReaderTextAnchors.resolve("ABCDE", anchor, ChineseConversionMode.TW2SP, converter)
        )
        assertEquals(
            ResolvedTextAnchor(0, 0),
            ReaderTextAnchors.resolve("", anchor, ChineseConversionMode.TW2SP, converter)
        )
        val emptyAnchor = ReaderTextAnchors.create("", 0, 0, ChineseConversionMode.TW2SP)
        assertEquals(
            ResolvedTextAnchor(0, 0),
            ReaderTextAnchors.resolveSourcePoint("", "", emptyAnchor, converter)
        )
    }

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

        val mixedSource = "程式碼" + "甲".repeat(40) + "长目标" + "乙".repeat(40)
        val mixedShown = converter.convert(mixedSource, ChineseConversionMode.TW2SP)
        val displayOffset = mixedShown.indexOf("目标")
        val displayAnchor = ReaderTextAnchors.create(
            mixedShown,
            displayOffset,
            displayOffset,
            ChineseConversionMode.TW2SP
        )
        assertEquals(43, displayOffset)
        assertEquals(
            44,
            ReaderTextAnchors.resolveSourcePoint(
                mixedSource,
                mixedShown,
                displayAnchor,
                converter
            )!!.start
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

        val displayAnchor = ReaderTextAnchors.create(
            shown,
            resolved.start,
            resolved.end,
            ChineseConversionMode.TW2SP
        )
        val sourceResolved = ReaderTextAnchors.resolve(
            source,
            displayAnchor,
            ChineseConversionMode.OFF,
            converter
        )!!
        assertEquals("滑鼠裡面的程式碼", source.substring(sourceResolved.start, sourceResolved.end))
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
