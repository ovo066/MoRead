package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import com.mozhi.reader.core.importer.convert.mobi.MobiBinaryTest.Companion.bytes
import com.mozhi.reader.core.importer.convert.mobi.MobiBinaryTest.Companion.expect
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.magic
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.put16
import com.mozhi.reader.core.importer.convert.mobi.MobiTestData.put32
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MobiIndexTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun tagxUsesMultipleControlsAndReadsExtendedByteCountsBeforeValues() {
        val row = bytes(1, 120, 3, 1, 0x83, 1, 0x82, 0x87, 0x89, 0x8a)
        val entry = read(row).entries.single()
        assertEquals("x", entry.label)
        assertEquals(listOf(130, 7), entry.tags[1])
        assertEquals(listOf(9, 10), entry.tags[6])
    }

    @Test fun absentControlBitsDoNotConsumeValues() {
        val entry = read(bytes(1, 120, 1, 0, 0x85)).entries.single()
        assertEquals(mapOf(1 to listOf(5)), entry.tags)
    }

    @Test fun extendedLengthCannotReadIntoAnotherTagOrIdxt() {
        expect(Reason.CORRUPT_FILE) { read(bytes(1, 120, 3, 1, 0x81, 1, 0x82, 0x87, 0x89, 0x8a)) }
        expect(Reason.CORRUPT_FILE) { read(bytes(1, 120, 3, 0, 0xff)) }
    }

    private fun read(row: ByteArray): MobiIndex {
        val descriptors = bytes(1, 1, 3, 0, 0, 0, 0, 1, 6, 2, 1, 0, 0, 0, 0, 1)
        val head = ByteArray(192 + 12 + descriptors.size).apply {
            magic(0, "INDX"); put32(4, 192); put32(24, 1); put32(28, 65001); put32(36, 1)
            magic(192, "TAGX"); put32(196, 12 + descriptors.size); put32(200, 2)
            descriptors.copyInto(this, 204)
        }
        val data = ByteArray(192 + row.size + 6).apply {
            magic(0, "INDX"); put32(4, 192); put32(20, 192 + row.size); put32(24, 1)
            row.copyInto(this, 192); magic(192 + row.size, "IDXT"); put16(196 + row.size, 192)
        }
        val file = temporary.newFile().apply { writeBytes(MobiTestData.pdb(listOf(head, data))) }
        return PalmDatabase(file).use { MobiIndexReader(it, 0) {}.read(0) }
    }
}
