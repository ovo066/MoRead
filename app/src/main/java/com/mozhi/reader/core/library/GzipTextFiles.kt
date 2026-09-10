package com.mozhi.reader.core.library

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 章节 DOM 这类 JSON 落盘时 gzip 压缩（精排书一章的 DOM JSON 常有几十 KB，整本几 MB，压后约
 * 八分之一）；读取按魔数识别，旧安装里未压缩的 `.json` 与新写入的 `.json.gz` 走同一个入口。
 */
object GzipTextFiles {
    private const val GZIP_MAGIC_0 = 0x1f
    private const val GZIP_MAGIC_1 = 0x8b

    fun writeGzip(target: File, text: String) {
        target.parentFile?.mkdirs()
        GZIPOutputStream(target.outputStream().buffered()).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
    }

    /** Keep the indexed filename unchanged; a crash must never leave the index pointing at a deleted file. */
    fun replaceWithGzip(target: File, text: String) {
        val temporary = File.createTempFile("${target.name}.", ".tmp", target.parentFile)
        try {
            writeGzip(temporary, text)
            Files.move(temporary.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    fun isGzip(file: File): Boolean = file.inputStream().use { stream ->
        stream.read() == GZIP_MAGIC_0 && stream.read() == GZIP_MAGIC_1
    }

    /** 读文本：gzip 魔数开头就解压，否则按普通 UTF-8 文件读。 */
    fun readText(file: File): String = file.inputStream().buffered().use { input ->
        // Probe and decode the same open file: compaction may atomically replace its path meanwhile.
        input.mark(2)
        val gzip = input.read() == GZIP_MAGIC_0 && input.read() == GZIP_MAGIC_1
        input.reset()
        val decoded = if (gzip) GZIPInputStream(input) else input
        decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
