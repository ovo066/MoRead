package com.mozhi.reader.core.library

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class BookTextCompactionOutcome { COMPRESSED, ALREADY_COMPRESSED, TOO_SMALL, TOO_LARGE, NO_GAIN, MISSING }

data class BookTextCompactionResult(
    val outcome: BookTextCompactionOutcome,
    val beforeBytes: Long = 0,
    val afterBytes: Long = beforeBytes
) {
    val freedBytes: Long get() = (beforeBytes - afterBytes).coerceAtLeast(0)
}

/**
 * Independent 64 KiB blocks. Offsets index the decoded UTF-8 stream, so compaction changes neither
 * chapter rows nor annotations, reading positions, or content hashes. Old plain text stays readable.
 * A chapter read only inflates the blocks it intersects.
 */
internal object BookTextArchive {
    const val BLOCK_BYTES = 64 * 1024
    private const val META = "moread-text-v1"
    private const val MAX_BYTES = 512L * 1024 * 1024
    private class MutationGate { var version = 0L }
    private val mutationGates = Array(64) { MutationGate() }
    private fun gate(file: File) = mutationGates[file.canonicalPath.hashCode().and(63)]

    fun replaceAtomically(temporary: File, target: File, expectedVersion: Long? = null, validate: () -> Unit = {}) {
        val gate = gate(target)
        synchronized(gate) {
            if (expectedVersion != null && expectedVersion != gate.version) throw IOException("正文已变化，稍后重试压缩")
            validate()
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            gate.version++
        }
    }

    class Reader(file: File) : Closeable {
        private val raw = RandomAccessFile(file, "r")
        private var archive: ZipFile? = null
        val length: Long

        init {
            try {
                val compressed = raw.length() >= 4 && raw.readInt() == 0x504b0304
                raw.seek(0)
                if (compressed) {
                    val zip = ZipFile(file).also { archive = it }
                    val meta = zip.getEntry(META) ?: throw IOException("正文压缩索引缺失")
                    if (meta.size !in 1L..80L) throw IOException("正文压缩索引无效")
                    val fields = zip.getInputStream(meta).use { input ->
                        val bytes = ByteArray(meta.size.toInt())
                        java.io.DataInputStream(input).readFully(bytes)
                        if (input.read() != -1) throw IOException("正文压缩索引超出长度")
                        bytes.toString(Charsets.US_ASCII).split(':')
                    }
                    if (fields.size != 2 || fields[0].toIntOrNull() != BLOCK_BYTES) throw IOException("正文压缩格式不受支持")
                    length = fields[1].toLongOrNull()?.takeIf { it in 0..MAX_BYTES }
                        ?: throw IOException("正文压缩长度无效")
                } else length = raw.length()
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        val compressed: Boolean get() = archive != null

        fun read(offset: Long, size: Int): ByteArray {
            if (offset < 0 || size < 0 || offset > length || size > length - offset) throw IOException("章节正文不完整")
            val output = ByteArray(size)
            if (archive == null) {
                raw.seek(offset)
                raw.readFully(output)
            } else {
                var copied = 0
                while (copied < size) {
                    val position = offset + copied
                    val block = readBlock((position / BLOCK_BYTES).toInt())
                    val start = (position % BLOCK_BYTES).toInt()
                    val count = minOf(block.size - start, size - copied)
                    block.copyInto(output, copied, start, start + count)
                    copied += count
                }
            }
            return output
        }

        fun forEachBlock(consume: (ByteArray) -> Unit) {
            var offset = 0L
            while (offset < length) {
                val bytes = if (archive == null) read(offset, minOf(BLOCK_BYTES.toLong(), length - offset).toInt())
                    else readBlock((offset / BLOCK_BYTES).toInt())
                consume(bytes)
                offset += bytes.size
            }
        }

        private fun readBlock(index: Int): ByteArray {
            val zip = requireNotNull(archive)
            val expected = minOf(BLOCK_BYTES.toLong(), length - index.toLong() * BLOCK_BYTES).toInt()
            val entry = zip.getEntry("text/$index") ?: throw IOException("正文压缩块缺失")
            if (expected <= 0 || entry.size != expected.toLong() || entry.compressedSize !in 0..(BLOCK_BYTES + 1024L)) {
                throw IOException("正文压缩块长度无效")
            }
            val bytes = ByteArray(expected)
            zip.getInputStream(entry).use { input ->
                var cursor = 0
                while (cursor < expected) {
                    val count = input.read(bytes, cursor, expected - cursor)
                    if (count <= 0) throw IOException("正文压缩块不完整")
                    cursor += count
                }
                if (input.read() != -1) throw IOException("正文压缩块超出索引范围")
            }
            if (CRC32().apply { update(bytes) }.value != entry.crc) throw IOException("正文压缩块校验失败")
            return bytes
        }

        override fun close() {
            try { archive?.close() } finally { raw.close() }
        }
    }

    /** Verify the decoded stream before atomic replacement; failures leave the original intact. */
    fun compact(file: File, checkActive: () -> Unit = {}): Long = compactWithResult(file, checkActive).freedBytes

    fun compactWithResult(file: File, checkActive: () -> Unit = {}): BookTextCompactionResult {
        if (!file.isFile) return BookTextCompactionResult(BookTextCompactionOutcome.MISSING)
        val gate = gate(file)
        val version = synchronized(gate) { gate.version }
        val originalSize = file.length()
        val modified = file.lastModified()
        val temporary = File.createTempFile("text-compact-", ".tmp", file.parentFile)
        try {
            val sourceHash = MessageDigest.getInstance("SHA-256")
            Reader(file).use { source ->
                if (source.compressed) return BookTextCompactionResult(BookTextCompactionOutcome.ALREADY_COMPRESSED, originalSize)
                if (source.length < 4096) return BookTextCompactionResult(BookTextCompactionOutcome.TOO_SMALL, originalSize)
                if (source.length > MAX_BYTES) return BookTextCompactionResult(BookTextCompactionOutcome.TOO_LARGE, originalSize)
                ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                    var index = 0
                    source.forEachBlock { bytes ->
                        checkActive()
                        sourceHash.update(bytes)
                        zip.putNextEntry(ZipEntry("text/${index++}").apply { time = 0 })
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry(META).apply { time = 0 })
                    zip.write("$BLOCK_BYTES:${source.length}".toByteArray(Charsets.US_ASCII))
                    zip.closeEntry()
                }
            }
            if (temporary.length() >= originalSize) return BookTextCompactionResult(BookTextCompactionOutcome.NO_GAIN, originalSize)
            val decodedHash = MessageDigest.getInstance("SHA-256")
            Reader(temporary).use { reader ->
                if (reader.length != originalSize) throw IOException("压缩前后正文长度不一致")
                reader.forEachBlock { checkActive(); decodedHash.update(it) }
            }
            if (!sourceHash.digest().contentEquals(decodedHash.digest())) throw IOException("压缩前后正文校验不一致")
            checkActive()
            val compressedSize = temporary.length()
            replaceAtomically(temporary, file, version) {
                if (file.length() != originalSize || file.lastModified() != modified) throw IOException("正文已变化，稍后重试压缩")
            }
            return BookTextCompactionResult(BookTextCompactionOutcome.COMPRESSED, originalSize, compressedSize)
        } finally { temporary.delete() }
    }
}
