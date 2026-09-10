package com.mozhi.reader.core.library

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GzipTextFilesTest {
    private val text = "{\"chapterIndex\":1,\"body\":\"" + "精排 DOM JSON 里重复的属性名与标签 ".repeat(200) + "\"}"

    @Test
    fun `gzip round trip preserves text and shrinks repetitive json`() {
        val file = File.createTempFile("moread-layout", ".json.gz")
        try {
            GzipTextFiles.writeGzip(file, text)
            assertEquals(text, GzipTextFiles.readText(file))
            assertTrue("gzip should shrink repetitive JSON (${file.length()} vs ${text.toByteArray().size})",
                file.length() < text.toByteArray().size / 4)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `plain files written by older installs are still readable`() {
        val file = File.createTempFile("moread-layout", ".json")
        try {
            file.writeText(text)
            assertEquals(text, GzipTextFiles.readText(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `atomic compression preserves the indexed filename and leaves no temporary files`() {
        val directory = Files.createTempDirectory("moread-compact").toFile()
        val file = File(directory, "chapter.json")
        try {
            file.writeText(text)
            GzipTextFiles.replaceWithGzip(file, text)
            assertTrue(GzipTextFiles.isGzip(file))
            assertEquals(text, GzipTextFiles.readText(file))
            assertEquals(listOf("chapter.json"), directory.list()!!.toList())
            GzipTextFiles.replaceWithGzip(file, text + "updated")
            assertEquals(text + "updated", GzipTextFiles.readText(file))
        } finally {
            file.delete()
            directory.delete()
        }
    }

    @Test
    fun `tiny plain files that are shorter than the gzip magic are read as text`() {
        val file = File.createTempFile("moread-layout", ".json")
        try {
            file.writeText("1")
            assertEquals("1", GzipTextFiles.readText(file))
            file.writeText("")
            assertEquals("", GzipTextFiles.readText(file))
        } finally {
            file.delete()
        }
    }
}
