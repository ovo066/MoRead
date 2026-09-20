package com.mozhi.reader.core.dictionary

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.Adler32
import java.util.zip.InflaterInputStream
import org.bouncycastle.crypto.digests.RIPEMD128Digest
import org.anarres.lzo.LzoDecompressor1x
import org.anarres.lzo.lzo_uintp

/** On-demand MDX/MDD v1/v2 reader. Only block indexes live in memory; records are never unpacked to disk. */
internal class MdictReader(private val file: File, private val resource: Boolean = file.extension.equals("mdd", true)) {
    private data class KeyBlock(val last: String, val position: Long, val compressed: Int, val expanded: Int, val entries: Int)
    private data class RecordBlock(val position: Long, val offset: Long, val compressed: Int, val expanded: Int)
    private data class Key(val offset: Long, val text: String)
    private val keys = mutableListOf<KeyBlock>()
    private val records = mutableListOf<RecordBlock>()
    private var version = 2.0
    private var charset: Charset = Charsets.UTF_8
    private var caseSensitive = false
    private var stripKeys = true
    private var totalRecordBytes = 0L
    val title: String
    val declaredTitle: String

    init {
        RandomAccessFile(file, "r").use { input ->
            val header = input.bytes(input.readInt().bounded(1_048_576))
            val checksum = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
            verify(header, checksum)
            val xml = header.toString(Charsets.UTF_16LE)
            val attrs = Regex("([A-Za-z]+)=\"([^\"]*)\"").findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
            version = attrs["GeneratedByEngineVersion"]?.toDoubleOrNull() ?: 1.2
            require(version < 3) { "暂不支持 MDX 3.0，请导出为 MDX 2.0" }
            val encrypted = when (val value = attrs["Encrypted"]) { "Yes" -> 1; else -> value?.toIntOrNull() ?: 0 }
            require(encrypted and 1 == 0) { "此词典需要授权密码，暂不支持导入加密授权词典" }
            charset = when {
                resource -> Charsets.UTF_16LE
                attrs["Encoding"].orEmpty().replace("-", "").equals("UTF16", true) -> Charsets.UTF_16LE
                else -> Charset.forName(attrs["Encoding"].orEmpty().ifBlank { "UTF-8" })
            }
            caseSensitive = attrs["KeyCaseSensitive"].equals("Yes", true)
            stripKeys = !resource && !attrs["StripKey"].equals("No", true)
            declaredTitle = org.jsoup.parser.Parser.unescapeEntities(attrs["Title"].orEmpty(), false)
            title = declaredTitle.ifBlank { file.nameWithoutExtension }
            val headerBytes = input.bytes(if (version >= 2) 40 else 16)
            val headerInput = DataInputStream(ByteArrayInputStream(headerBytes))
            val blocks = headerInput.number().count()
            headerInput.number() // total entries
            val expandedIndex = if (version >= 2) headerInput.number().blockSize() else 0
            val indexSize = headerInput.number().blockSize()
            val keyBlocksSize = headerInput.number()
            if (version >= 2) verify(headerBytes, input.readInt().toLong() and 0xffffffffL)
            val rawIndex = input.bytes(indexSize)
            val indexBytes = if (version >= 2) unpack(if (encrypted and 2 != 0) decodeIndex(rawIndex) else rawIndex, expandedIndex) else rawIndex
            val index = DataInputStream(ByteArrayInputStream(indexBytes))
            var keyPosition = input.filePointer
            repeat(blocks) {
                val count = index.number().count()
                index.indexText() // first key
                val last = index.indexText()
                val compressed = index.number().blockSize()
                val expanded = index.number().blockSize()
                keys += KeyBlock(normalize(last), keyPosition, compressed, expanded, count)
                keyPosition += compressed
            }
            require(index.available() == 0 && keyPosition == input.filePointer + keyBlocksSize && keyPosition <= file.length()) { "词典索引损坏" }
            input.seek(keyPosition)
            val recordCount = input.number().count()
            input.number()
            val recordIndexSize = input.number()
            val compressedRecords = input.number()
            require(recordIndexSize == recordCount.toLong() * if (version >= 2) 16 else 8) { "词典正文索引损坏" }
            var position = input.filePointer + recordIndexSize
            repeat(recordCount) {
                val compressed = input.number().blockSize()
                val expanded = input.number().blockSize()
                records += RecordBlock(position, totalRecordBytes, compressed, expanded)
                position += compressed
                totalRecordBytes += expanded
            }
            require(position == input.filePointer + compressedRecords && position <= file.length()) { "词典正文不完整" }
        }
    }

    @Synchronized fun lookup(word: String): ByteArray? = RandomAccessFile(file, "r").use { input ->
        val target = normalize(word)
        if (target.isBlank()) return@use null
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].last < target) lo = mid + 1 else hi = mid
        }
        if (lo >= keys.size) return@use null
        val entries = readKeys(input, lo)
        val found = entries.indexOfFirst { normalize(it.text) == target }
        if (found < 0) return@use null
        val start = entries[found].offset
        val end = entries.drop(found + 1).firstOrNull { it.offset > start }?.offset
            ?: if (lo + 1 < keys.size) readKeys(input, lo + 1).firstOrNull { it.offset > start }?.offset ?: totalRecordBytes else totalRecordBytes
        require(start >= 0 && end >= start && end <= totalRecordBytes && end - start <= MAX_RECORD) { "词条过大或索引损坏" }
        val result = ByteArrayOutputStream((end - start).toInt())
        records.asSequence().filter { it.offset < end && it.offset + it.expanded > start }.forEach { block ->
            input.seek(block.position)
            val bytes = unpack(input.bytes(block.compressed), block.expanded)
            val from = (start - block.offset).coerceAtLeast(0).toInt()
            val to = (end - block.offset).coerceAtMost(bytes.size.toLong()).toInt()
            result.write(bytes, from, to - from)
        }
        result.toByteArray()
    }

    fun definition(word: String): String? {
        var current = word
        val visited = mutableSetOf<String>()
        repeat(8) {
            if (!visited.add(normalize(current))) return null
            val value = lookup(current)?.toString(charset)?.trimEnd('\u0000') ?: return null
            if (!value.startsWith("@@@LINK=")) return value
            current = value.removePrefix("@@@LINK=").trim()
        }
        return null
    }

    private fun readKeys(input: RandomAccessFile, index: Int): List<Key> {
        val block = keys[index]
        input.seek(block.position)
        val decoded = DataInputStream(ByteArrayInputStream(unpack(input.bytes(block.compressed), block.expanded)))
        return List(block.entries) {
            val offset = decoded.number()
            val bytes = ByteArrayOutputStream()
            while (true) {
                val first = decoded.readUnsignedByte()
                val second = if (charset == Charsets.UTF_16LE) decoded.readUnsignedByte() else 0
                if (first == 0 && second == 0) break
                bytes.write(first)
                if (charset == Charsets.UTF_16LE) bytes.write(second)
                require(bytes.size() <= 32_768) { "词条名称过长" }
            }
            Key(offset, bytes.toByteArray().toString(charset))
        }.also { require(decoded.available() == 0) { "词条索引不完整" } }
    }

    private fun DataInputStream.indexText(): String {
        val length = if (version >= 2) readUnsignedShort() else readUnsignedByte()
        val unit = if (charset == Charsets.UTF_16LE) 2 else 1
        val bytes = ByteArray(length * unit).also(::readFully)
        if (version >= 2) repeat(unit) { readByte() }
        return bytes.toString(charset)
    }
    private fun DataInputStream.number(): Long = if (version >= 2) readLong() else readInt().toLong() and 0xffffffffL
    private fun RandomAccessFile.number(): Long = if (version >= 2) readLong() else readInt().toLong() and 0xffffffffL
    private fun normalize(value: String): String {
        val text = if (resource) value.replace('/', '\\').let { if (it.startsWith('\\')) it else "\\$it" } else value.trim()
        val stripped = if (stripKeys) text.replace(Regex("[\\p{P}\\p{Z}\\s]"), "") else text
        return if (caseSensitive) stripped else stripped.lowercase(Locale.ROOT)
    }
    private fun RandomAccessFile.bytes(size: Int): ByteArray {
        require(size >= 0 && size <= MAX_BLOCK && size.toLong() <= length() - filePointer) { "词典数据不完整或分块过大" }
        return ByteArray(size).also(::readFully)
    }
    private fun Long.count(): Int { require(this in 0..1_000_000) { "词典索引过大" }; return toInt() }
    private fun Long.blockSize(): Int { require(this in 0..MAX_BLOCK.toLong()) { "词典分块过大" }; return toInt() }
    private fun Int.bounded(max: Int): Int { require(this in 1..max) { "词典文件头无效" }; return this }

    companion object {
        private const val MAX_BLOCK = 32 * 1024 * 1024
        private const val MAX_RECORD = 16 * 1024 * 1024L
        private fun verify(bytes: ByteArray, expected: Long) {
            require(Adler32().apply { update(bytes) }.value == expected) { "词典校验失败，文件可能损坏" }
        }
        private fun unpack(block: ByteArray, expected: Int): ByteArray {
            require(block.size >= 8 && expected in 0..MAX_BLOCK) { "词典分块无效" }
            val output = when (block[0].toInt()) {
                0 -> block.copyOfRange(8, block.size)
                1 -> ByteArray(expected).also { bytes ->
                    val length = lzo_uintp(expected)
                    val status = LzoDecompressor1x().decompress(block, 8, block.size - 8, bytes, 0, length)
                    require(status == 0 && length.value == expected) { "LZO 词典解压失败" }
                }
                2 -> InflaterInputStream(ByteArrayInputStream(block, 8, block.size - 8)).use { input ->
                    val bytes = ByteArray(expected)
                    var read = 0
                    while (read < expected) { val n = input.read(bytes, read, expected - read); require(n > 0) { "词典解压不完整" }; read += n }
                    require(input.read() == -1) { "词典解压大小不符" }
                    bytes
                }
                else -> error("不支持的词典压缩格式")
            }
            require(output.size == expected) { "词典分块大小不符" }
            val checksum = DataInputStream(ByteArrayInputStream(block, 4, 4)).readInt().toLong() and 0xffffffffL
            verify(output, checksum)
            return output
        }
        private fun decodeIndex(input: ByteArray): ByteArray {
            require(input.size >= 8)
            val seed = input.copyOfRange(4, 8) + byteArrayOf(0x95.toByte(), 0x36, 0, 0)
            val key = ByteArray(16)
            RIPEMD128Digest().apply { update(seed, 0, seed.size); doFinal(key, 0) }
            val result = input.copyOf()
            var previous = 0x36
            for (i in 8 until input.size) {
                val value = input[i].toInt() and 255
                result[i] = (((value ushr 4) or (value shl 4)) xor previous xor ((i - 8) and 255) xor (key[(i - 8) % 16].toInt() and 255)).toByte()
                previous = value
            }
            return result
        }
    }
}
