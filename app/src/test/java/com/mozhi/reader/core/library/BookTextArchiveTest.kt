package com.mozhi.reader.core.library

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookTextArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sample() = (0..5000).joinToString("\n") { "第${it}行：窗外的雨慢慢停了，书页上留下不同的注记。English text 与 emoji 🙂。" }.toByteArray()

    @Test fun oldTextCompactsWithoutChangingByteRangesOrDecodedHash() {
        val bytes = sample()
        val file = temporary.newFile("text.mz").apply { writeBytes(bytes) }
        val freed = BookTextArchive.compact(file)
        assertTrue(freed > bytes.size / 2)
        assertEquals(bytes.size.toLong() - file.length(), freed)
        BookTextArchive.Reader(file).use { reader ->
            assertTrue(reader.compressed)
            assertEquals(bytes.size.toLong(), reader.length)
            listOf(0, 7, 65533, 65536, bytes.size - 19).forEach { start ->
                val length = minOf(70001, bytes.size - start)
                assertArrayEquals(bytes.copyOfRange(start, start + length), reader.read(start.toLong(), length))
            }
            val actual = MessageDigest.getInstance("SHA-256")
            reader.forEachBlock(actual::update)
            assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(bytes), actual.digest())
            assertArrayEquals(byteArrayOf(), reader.read(bytes.size.toLong(), 0))
        }
        assertEquals(0L, BookTextArchive.compact(file))
        assertEquals(listOf(file.name), temporary.root.listFiles()!!.map(File::getName))
    }

    @Test fun interruptedCompactionLeavesTheOnlySourceIntact() {
        val bytes = sample()
        val file = temporary.newFile("text.mz").apply { writeBytes(bytes) }
        var blocks = 0
        assertThrows(IOException::class.java) {
            BookTextArchive.compact(file) { if (++blocks == 2) throw IOException("cancelled") }
        }
        assertArrayEquals(bytes, file.readBytes())
        assertEquals(1, temporary.root.listFiles()!!.size)
    }

    @Test fun concurrentEditWinsOverAnOlderCompactionEvenWithMatchingFileTimestamp() {
        val file = temporary.newFile("text.mz").apply { writeBytes(sample()) }
        val modified = file.lastModified()
        val edited = "用户刚刚保存的新正文。".repeat(7000)
        val sourceBlocks = (file.length() + BookTextArchive.BLOCK_BYTES - 1) / BookTextArchive.BLOCK_BYTES
        var checks = 0
        var saved = false
        assertThrows(IOException::class.java) {
            BookTextArchive.compact(file) {
                // Windows disallows replacing an open source file; race during verification,
                // after the source stream closes but before the compacted copy is committed.
                if (++checks > sourceBlocks && !saved) {
                    BookTextWriter().write(file, listOf(ChapterTextInput(0, edited)))
                    saved = true
                    file.setLastModified(modified)
                }
            }
        }
        assertTrue(saved)
        BookTextArchive.Reader(file).use { reader ->
            assertEquals(edited, reader.read(0, reader.length.toInt()).toString(Charsets.UTF_8))
        }
    }

    @Test fun smallLegacyFilesStayPlainAndRangesAreValidated() {
        val file = temporary.newFile().apply { writeText("中文🙂") }
        assertEquals(0L, BookTextArchive.compact(file))
        BookTextArchive.Reader(file).use { reader ->
            assertFalse(reader.compressed)
            assertEquals("中文🙂", reader.read(0, file.length().toInt()).toString(Charsets.UTF_8))
            assertThrows(IOException::class.java) { reader.read(-1, 1) }
            assertThrows(IOException::class.java) { reader.read(Long.MAX_VALUE, 1) }
            assertThrows(IOException::class.java) { reader.read(0, 100) }
        }
    }

    @Test fun zeroSavingsDistinguishesAlreadyCompressedSmallAndMissingFiles() {
        val compressed = temporary.newFile().apply { writeText("可重复压缩的正文。".repeat(10_000)) }
        val first = BookTextArchive.compactWithResult(compressed)
        assertEquals(BookTextCompactionOutcome.COMPRESSED, first.outcome)
        assertTrue(first.freedBytes > 0)
        assertTrue(compressed.length() < 4096)
        val second = BookTextArchive.compactWithResult(compressed)
        assertEquals(BookTextCompactionOutcome.ALREADY_COMPRESSED, second.outcome)
        assertEquals(0L, second.freedBytes)
        val small = temporary.newFile().apply { writeText("短文") }
        assertEquals(BookTextCompactionOutcome.TOO_SMALL, BookTextArchive.compactWithResult(small).outcome)
        assertEquals(BookTextCompactionOutcome.MISSING,
            BookTextArchive.compactWithResult(File(temporary.root, "missing.mz")).outcome)
    }

    @Test fun truncatedArchiveCannotSilentlyReturnAPartialChapter() {
        val file = temporary.newFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("moread-text-v1"))
            zip.write("65536:70000".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("text/0"))
            zip.write(ByteArray(65536))
            zip.closeEntry()
        }
        BookTextArchive.Reader(file).use { reader ->
            assertThrows(IOException::class.java) { reader.read(65000, 5000) }
        }
    }
}
