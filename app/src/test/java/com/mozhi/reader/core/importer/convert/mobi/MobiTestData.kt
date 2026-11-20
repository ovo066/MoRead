package com.mozhi.reader.core.importer.convert.mobi

import java.io.ByteArrayOutputStream

/** 手工编码小记录，避免损坏文件测试依赖外部样书或转换工具。 */
internal object MobiTestData {
    fun ByteArray.put16(at: Int, value: Int) { this[at] = (value ushr 8).toByte(); this[at + 1] = value.toByte() }
    fun ByteArray.put32(at: Int, value: Int) { repeat(4) { this[at + it] = (value ushr (24 - 8 * it)).toByte() } }
    fun ByteArray.magic(at: Int, value: String) { value.toByteArray(Charsets.US_ASCII).copyInto(this, at) }
    fun integer(value: Int) = ByteArray(4).apply { put32(0, value) }

    fun pdb(records: List<ByteArray>): ByteArray {
        val header = ByteArray(78 + records.size * 8 + 2)
        header.magic(0, "sample")
        header.magic(60, "BOOKMOBI")
        header.put16(76, records.size)
        var offset = header.size
        records.forEachIndexed { i, record -> header.put32(78 + i * 8, offset); offset += record.size }
        return ByteArrayOutputStream().apply { write(header); records.forEach { write(it) } }.toByteArray()
    }

    fun header(textLength: Int, version: Int = 6, compression: Int = 1, encryption: Int = 0,
        records: Int = 1, exth: List<Pair<Int, ByteArray>> = emptyList()): ByteArray {
        val metadata = if (exth.isEmpty()) ByteArray(0) else ByteArrayOutputStream().apply {
            write("EXTH".toByteArray()); write(integer(12 + exth.sumOf { 8 + it.second.size })); write(integer(exth.size))
            exth.forEach { (type, value) -> write(integer(type)); write(integer(8 + value.size)); write(value) }
        }.toByteArray()
        val title = "墨知样书".toByteArray()
        return ByteArray(280 + metadata.size + title.size).apply {
            put16(0, compression); put32(4, textLength); put16(8, records); put16(10, 4096); put16(12, encryption)
            magic(16, "MOBI"); put32(20, 264); put32(28, 65001); put32(36, version)
            put32(84, 280 + metadata.size); put32(88, title.size); put32(92, 4)
            put32(108, -1); put32(192, -1); put32(196, 1)
            put32(244, -1); put32(248, -1); put32(252, -1)
            if (metadata.isNotEmpty()) put32(128, 64)
            metadata.copyInto(this, 280); title.copyInto(this, 280 + metadata.size)
        }
    }

    fun vint(value: Int): ByteArray {
        var n = value
        val bytes = mutableListOf((n and 127 or 128).toByte())
        n = n ushr 7
        while (n != 0) { bytes.add(0, (n and 127).toByte()); n = n ushr 7 }
        return bytes.toByteArray()
    }

    /** 所有测试标签均用单控制字节，值数由描述表声明。 */
    fun index(tags: List<Pair<Int, Int>>, rows: List<Pair<String, List<Int>>>, labels: List<String> = emptyList()): List<ByteArray> {
        val tagx = ByteArray(12 + (tags.size + 1) * 4).apply {
            magic(0, "TAGX"); put32(4, size); put32(8, 1)
            tags.forEachIndexed { i, (tag, values) ->
                this[12 + i * 4] = tag.toByte(); this[13 + i * 4] = values.toByte(); this[14 + i * 4] = (1 shl i).toByte()
            }
            this[lastIndex] = 1
        }
        val head = ByteArray(192 + tagx.size).apply {
            magic(0, "INDX"); put32(4, 192); put32(24, 1); put32(28, 65001); put32(36, rows.size)
            put32(52, if (labels.isEmpty()) 0 else 1); tagx.copyInto(this, 192)
        }
        val body = ByteArrayOutputStream()
        val offsets = rows.map { (name, values) ->
            val offset = 192 + body.size()
            val label = name.toByteArray()
            body.write(label.size); body.write(label); body.write((1 shl tags.size) - 1)
            values.forEach { body.write(vint(it)) }
            offset
        }
        val record = ByteArray(192 + body.size() + 4 + rows.size * 2).apply {
            magic(0, "INDX"); put32(4, 192); put32(20, 192 + body.size()); put32(24, rows.size)
            body.toByteArray().copyInto(this, 192); magic(192 + body.size(), "IDXT")
            offsets.forEachIndexed { i, offset -> put16(196 + body.size() + i * 2, offset) }
        }
        val ctoc = ByteArrayOutputStream().apply { labels.forEach { val b = it.toByteArray(); write(vint(b.size)); write(b) }; write(0) }.toByteArray()
        return listOf(head, record) + if (labels.isEmpty()) emptyList() else listOf(ctoc)
    }
}
