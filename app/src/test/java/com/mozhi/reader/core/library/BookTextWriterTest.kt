package com.mozhi.reader.core.library

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookTextWriterTest {

    private val writer = BookTextWriter()
    private val directory: File = Files.createTempDirectory("book-text-test").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `ranges tile the file exactly`() {
        val chapters = (0 until 600).map { index ->
            ChapterTextInput(index = index, body = "第 $index 章正文。\n又一段落，含中文标点。")
        }
        val target = File(directory, "text.mz")

        val ranges = writer.write(target, chapters)

        assertEquals(600, ranges.size)
        assertEquals(target.length(), ranges.sumOf { it.byteLength.toLong() })
        var expectedOffset = 0L
        ranges.forEach { range ->
            assertEquals("range ${range.index} is not contiguous", expectedOffset, range.byteOffset)
            expectedOffset += range.byteLength
        }
    }

    @Test
    fun `every chapter reads back byte for byte`() {
        val bodies = listOf(
            "纯中文段落。",
            "Mixed latin and 中文 in one paragraph.",
            "第一段\n第二段\n第三段",
            "带 emoji 的段落 🙂 还有代理对"
        )
        val chapters = bodies.mapIndexed { index, body -> ChapterTextInput(index, body) }
        val target = File(directory, "text.mz")

        val ranges = writer.write(target, chapters)

        RandomAccessFile(target, "r").use { handle ->
            ranges.forEach { range ->
                val buffer = ByteArray(range.byteLength)
                handle.seek(range.byteOffset)
                handle.readFully(buffer)
                val decoded = String(buffer, Charsets.UTF_8)
                assertEquals(writer.normalize(bodies[range.index]), decoded)
                assertEquals(decoded.length, range.charCount)
            }
        }
    }

    @Test
    fun `char count is utf16 units not bytes`() {
        val target = File(directory, "text.mz")
        val ranges = writer.write(target, listOf(ChapterTextInput(0, "中文")))

        assertEquals(2, ranges.single().charCount)
        assertEquals(6, ranges.single().byteLength)
    }

    @Test
    fun `normalization unifies line endings and strips source indentation`() {
        val normalized = writer.normalize("　　第一段\r\n\t带制表符\r 第三段  ")

        assertEquals("第一段\n带制表符\n第三段", normalized)
    }

    @Test
    fun `normalization collapses blank line runs and trims edges`() {
        val normalized = writer.normalize("\n\n第一段\n\n\n\n第二段\n\n")

        assertEquals("第一段\n\n第二段", normalized)
    }

    @Test
    fun `writing twice replaces the previous blob`() {
        val target = File(directory, "text.mz")
        writer.write(target, listOf(ChapterTextInput(0, "很长很长的第一版正文内容")))
        val ranges = writer.write(target, listOf(ChapterTextInput(0, "短")))

        assertEquals(target.length(), ranges.single().byteLength.toLong())
        assertTrue("the temporary blob must not survive", !File(directory, "text.mz.tmp").isFile)
    }

    @Test
    fun `chapters are written in index order regardless of input order`() {
        val target = File(directory, "text.mz")
        val ranges = writer.write(
            target,
            listOf(
                ChapterTextInput(2, "第三"),
                ChapterTextInput(0, "第一"),
                ChapterTextInput(1, "第二")
            )
        )

        assertEquals(listOf(0, 1, 2), ranges.map(ChapterTextRange::index))
        assertEquals("第一第二第三", target.readText())
    }
}
