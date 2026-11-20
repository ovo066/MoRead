package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import org.junit.Assert.*
import org.junit.Test

class MobiBinaryTest {
    @Test fun palmDocLiteralRunsAndDirectBytes() {
        assertArrayEquals(bytes(0, 65, 255, 128, 1, 2), MobiBinary.palmDoc(bytes(0, 65, 4, 255, 128, 1, 2)))
        assertArrayEquals(ByteArray(0), MobiBinary.palmDoc(ByteArray(0)))
    }

    @Test fun palmDocSpaceAndCharacter() {
        assertEquals(" A z", MobiBinary.palmDoc(bytes(0xc1, 0xfa)).toString(Charsets.US_ASCII))
    }

    @Test fun palmDocBackReferenceAndOverlappingCopy() {
        assertEquals("ABCABC", MobiBinary.palmDoc(bytes(65, 66, 67, 0x80, 0x18)).toString(Charsets.US_ASCII))
        assertEquals("A".repeat(11), MobiBinary.palmDoc(bytes(65, 0x80, 0x0f)).toString(Charsets.US_ASCII))
    }

    @Test fun badBackReferenceTruncatedInstructionsAndOutputLimitAreRejected() {
        listOf(bytes(0x80), bytes(2, 65), bytes(0x80, 0), bytes(65, 0x80, 0x10)).forEach {
            expect(Reason.CORRUPT_FILE) { MobiBinary.palmDoc(it) }
        }
        expect(Reason.TEXT_TOO_LARGE) { MobiBinary.palmDoc(bytes(65, 0x80, 0x0f), 10) }
    }

    @Test fun variableIntegersHaveDifferentTerminationDirections() {
        assertEquals(VariableInteger(130, 2), MobiBinary.forward(bytes(1, 0x82)))
        assertEquals(VariableInteger(130, 2), MobiBinary.backward(bytes(0x81, 2)))
        assertEquals(VariableInteger(0, 1), MobiBinary.forward(bytes(0x80)))
        assertEquals(VariableInteger(127, 1), MobiBinary.backward(bytes(0xff)))
        assertEquals(VariableInteger(16384, 3), MobiBinary.forward(bytes(1, 0, 0x80)))
        assertEquals(VariableInteger(16384, 3), MobiBinary.backward(bytes(0x81, 0, 0)))
        expect(Reason.CORRUPT_FILE) { MobiBinary.forward(bytes(1, 2)) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.backward(bytes(1, 2)) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.forward(bytes(127, 127, 127, 127, 0xff)) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.backward(bytes(0xff, 127, 127, 127, 127)) }
    }

    @Test fun trailingEntriesIncludeTheirOwnLengthAndUseOneEntryPerSetBit() {
        val body = "正文".toByteArray()
        assertArrayEquals(body, MobiBinary.stripTrailing(body, 0))
        assertArrayEquals(body, MobiBinary.stripTrailing(body + bytes(0xaa, 0x82), 2))
        assertArrayEquals(body, MobiBinary.stripTrailing(body + bytes(0xaa, 0x82, 0xbb, 0xcc, 0x83), 0x8002))
        assertArrayEquals(body, MobiBinary.stripTrailing(body + ByteArray(128) + bytes(0x81, 2), 4))
    }

    @Test fun multibytePaddingIsRemovedAfterOtherEntriesAndBeforeDecompression() {
        val compressed = bytes(65, 0x80, 0x0f)
        for (count in 1..4) {
            val padding = ByteArray(count).apply { this[lastIndex] = (count - 1).toByte() }
            assertArrayEquals(compressed, MobiBinary.stripTrailing(compressed + padding, 1))
            val record = compressed + padding + bytes(0x91, 0x82)
            assertEquals("A".repeat(11), MobiBinary.palmDoc(MobiBinary.stripTrailing(record, 3)).toString(Charsets.US_ASCII))
        }
        expect(Reason.CORRUPT_FILE) { MobiBinary.stripTrailing(bytes(0x80), 2) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.stripTrailing(bytes(0x85), 2) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.stripTrailing(bytes(3), 1) }
        expect(Reason.CORRUPT_FILE) { MobiBinary.stripTrailing(ByteArray(0), 1) }
    }

    @Test fun kindleBase32IsOneBasedForEmbedsAndUsesDigitsThenLetters() {
        assertEquals(0, MobiBinary.embedIndex("0001"))
        assertEquals(9, MobiBinary.embedIndex("000A"))
        assertEquals(30, MobiBinary.embedIndex("000v"))
        assertEquals(31, MobiBinary.embedIndex("0010"))
        assertEquals(1024, MobiBinary.base32("100"))
        listOf("0", "W", "-1", "", "VVVVVVV").forEach { value ->
            expect(Reason.CORRUPT_FILE) { MobiBinary.embedIndex(value) }
        }
    }

    @Test fun kf8InsertionsMayMoveBackwardAndSplitAnEarlierFragment() {
        val source = "<>ABcdZ".toByteArray()
        val pieces = MobiBytePieces(source, 0, 2)
        pieces.insert(1, 2, 2)
        pieces.insert(2, 4, 2)
        pieces.insert(1, 6, 1)
        assertEquals("<ZAcdB>", pieces.bytes().toString(Charsets.US_ASCII))
        expect(Reason.CORRUPT_FILE) { pieces.insert(100, 0, 1) }
    }

    @Test fun manyFrontAndMiddleInsertionsPreserveByteOrder() {
        val pieces = MobiBytePieces("x".toByteArray(), 0, 0)
        repeat(10_000) { pieces.insert(if (it % 2 == 0) 0 else pieces.size / 2, 0, 1) }
        assertEquals("x".repeat(10_000), pieces.bytes().toString(Charsets.US_ASCII))
    }

    companion object {
        internal fun bytes(vararg values: Int) = values.map(Int::toByte).toByteArray()
        internal fun expect(reason: Reason, action: () -> Unit) {
            try { action(); fail("Expected $reason") } catch (error: BookConversionException) {
                assertEquals(reason, error.reason)
                assertFalse(error.message.isNullOrBlank())
            }
        }
    }
}
