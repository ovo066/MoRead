package com.mozhi.reader.core.dictionary

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MdictReaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture(name: String): File = temporary.newFile(name).apply {
        MdictReaderTest::class.java.getResourceAsStream("/dictionary/$name").use {
            writeBytes(requireNotNull(it).readBytes())
        }
    }
    @Test fun queryV1V2Utf16AndEncryptedIndexesWithoutUnpackingDictionary() {
        listOf("sample-v2.mdx", "sample-v1.mdx", "sample-utf16.mdx", "sample-index-encrypted.mdx", "sample-lzo.mdx").forEach { name ->
            val reader = MdictReader(fixture(name))
            assertEquals("MoRead Test", reader.title)
            assertEquals("<b>apple</b> 苹果", reader.definition("APPLE"))
            assertEquals("世界", reader.definition("world"))
            assertEquals(reader.definition("book"), reader.definition("books"))
            assertNull(reader.definition("missing"))
        }
    }
    @Test fun mddUsesBinaryRecordsAndNormalizesResourceSlashes() {
        val reader = MdictReader(fixture("sample.mdd"))
        assertEquals("body{color:blue}", reader.lookup("style.css")!!.toString(Charsets.UTF_8))
        assertTrue(reader.lookup("/image.svg")!!.toString(Charsets.UTF_8).startsWith("<svg"))
        assertNull(reader.lookup("/absent.png"))
    }
    @Test fun rejectsTruncatedAndCorruptFiles() {
        val file = fixture("sample-v2.mdx")
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOf(100))
        assertTrue(runCatching { MdictReader(file) }.isFailure)
        bytes[20] = (bytes[20].toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertTrue(runCatching { MdictReader(file) }.isFailure)
    }
}
