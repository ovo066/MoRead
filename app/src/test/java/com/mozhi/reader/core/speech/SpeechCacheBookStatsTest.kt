package com.mozhi.reader.core.speech

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechCacheBookStatsTest {
    @Test
    fun `按书汇总音频文件并忽略非书目录`() {
        val root = Files.createTempDirectory("speech-cache-test").toFile()
        try {
            root.resolve("1").apply { mkdirs() }.resolve("a.mp3").writeBytes(ByteArray(3))
            root.resolve("1").resolve("b.txt").writeBytes(ByteArray(8))
            root.resolve("2").apply { mkdirs() }.resolve("c.wav").writeBytes(ByteArray(5))
            root.resolve("misc").apply { mkdirs() }.resolve("d.mp3").writeBytes(ByteArray(9))

            val stats = summarizeSpeechCache(root)
            assertEquals(listOf(2L, 1L), stats.map(SpeechCacheBookStats::bookId))
            assertEquals(listOf(5L, 3L), stats.map(SpeechCacheBookStats::totalBytes))
        } finally {
            root.deleteRecursively()
        }
    }
}
