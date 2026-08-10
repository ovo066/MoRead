package com.mozhi.reader.core.datastore

import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class SfntFontNameReaderTest {
    @Test
    fun `reads localized full name from sfnt name table`() {
        val file = File.createTempFile("font-name-", ".ttf")
        try {
            val fullName = "墨知测试字体"
            val encoded = fullName.toByteArray(Charsets.UTF_16BE)
            DataOutputStream(file.outputStream().buffered()).use { output ->
                output.writeInt(0x0001_0000) // TrueType scaler type
                output.writeShort(1) // numTables
                output.writeShort(0)
                output.writeShort(0)
                output.writeShort(0)
                output.writeInt(0x6E61_6D65) // name
                output.writeInt(0) // checksum
                output.writeInt(28) // name table offset
                output.writeInt(18 + encoded.size)
                output.writeShort(0) // name table format
                output.writeShort(1) // record count
                output.writeShort(18) // string storage offset
                output.writeShort(3) // Windows platform
                output.writeShort(1) // Unicode BMP encoding
                output.writeShort(0x0804) // zh-CN
                output.writeShort(4) // full font name
                output.writeShort(encoded.size)
                output.writeShort(0)
                output.write(encoded)
            }

            assertEquals(fullName, SfntFontNameReader.read(file))
        } finally {
            file.delete()
        }
    }
}
