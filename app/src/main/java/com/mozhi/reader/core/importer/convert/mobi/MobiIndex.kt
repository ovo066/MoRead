package com.mozhi.reader.core.importer.convert.mobi

import java.nio.charset.Charset

internal data class MobiIndexEntry(val label: String, val tags: Map<Int, List<Int>>) {
    fun values(tag: Int, minimum: Int): List<Int> {
        val values = tags[tag].orEmpty()
        demand(values.size >= minimum, "INDX 缺少标签 $tag 的值")
        return values
    }
}

internal data class MobiIndex(val entries: List<MobiIndexEntry>, val labels: Map<Int, String>) {
    fun title(entry: MobiIndexEntry): String {
        val offset = entry.values(3, 1)[0]
        return labels[offset] ?: corrupt("INDX CTOC 标题偏移不存在")
    }
}

/** NCX、skeleton、fragment 共用 TAGX 控制字节解码，不能为三张表假定固定列宽。 */
internal class MobiIndexReader(private val pdb: PalmDatabase, private val base: Int, private val checkActive: () -> Unit) {
    private data class Tag(val id: Int, val values: Int, val mask: Int, val end: Int)
    private data class TagAmount(val tag: Tag, val count: Int?, val bytes: Int?)

    fun read(relative: Int): MobiIndex {
        val start = pdb.absolute(base, relative)
        val header = pdb.record(start, MAX_INDEX_RECORD_BYTES)
        checkHeader(header)
        val headerSize = header.uint(4, "INDX 头长度")
        val records = header.uint(24, "INDX 记录数")
        val total = header.uint(36, "INDX 总条数")
        val ctocCount = header.uint(52, "CTOC 记录数")
        demand(records in 1..4096 && total <= MobiBinary.MAX_INDEX_ENTRIES && ctocCount <= 256, "INDX 条数过大")
        demand(start.toLong() + records + ctocCount < pdb.count, "INDX 记录范围越界")
        demand(header.magic(headerSize, "TAGX"), "INDX 缺少 TAGX")
        val tagLength = header.uint(headerSize + 4, "TAGX 长度")
        val controls = header.uint(headerSize + 8, "TAGX 控制字节数")
        demand(tagLength >= 12 && (tagLength - 12) % 4 == 0 && controls in 1..32, "TAGX 头无效")
        range(headerSize.toLong(), tagLength.toLong(), header.size, "TAGX")
        demand((tagLength - 12) / 4 <= 256, "TAGX 标签数过大")
        val tags = (headerSize + 12 until headerSize + tagLength step 4).map {
            Tag(header.u8(it), header.u8(it + 1), header.u8(it + 2), header.u8(it + 3))
        }
        val encoding = when (header.u32(28)) {
            1252L -> Charset.forName("windows-1252")
            65001L, 0xffffffffL, 0L -> Charsets.UTF_8
            else -> unsupported("INDX 文本编码不受支持")
        }
        val entries = mutableListOf<MobiIndexEntry>()
        var budget = header.size.toLong()
        var valueCount = 0L
        repeat(records) { record ->
            checkActive()
            val data = pdb.record(start + 1 + record, MAX_INDEX_RECORD_BYTES)
            budget += data.size
            demand(budget <= MobiBinary.MAX_TEXT_BYTES, "INDX 总数据过大")
            checkHeader(data)
            val idxt = data.uint(20, "IDXT 偏移")
            val count = data.uint(24, "IDXT 条数")
            demand(count <= MobiBinary.MAX_INDEX_ENTRIES - entries.size, "INDX 条数过大")
            demand(data.magic(idxt, "IDXT"), "INDX 缺少 IDXT")
            range(idxt.toLong() + 4, count.toLong() * 2, data.size, "IDXT 表")
            var previous = data.uint(4, "INDX 头长度") - 1
            repeat(count) { row ->
                val p = data.u16(idxt + 4 + row * 2)
                val end = if (row + 1 < count) data.u16(idxt + 6 + row * 2) else idxt
                demand(p > previous && end > p && end <= idxt, "IDXT 条目范围无效")
                previous = p
                val labelLength = data.u8(p)
                demand(labelLength + 1 + controls <= end - p, "INDX 标签或控制字节截断")
                val label = data.copyOfRange(p + 1, p + 1 + labelLength).toString(encoding)
                val controlStart = p + 1 + labelLength
                val valuesStart = controlStart + controls
                val decoded = decodeTags(data, controlStart, valuesStart, end, controls, tags)
                valueCount += decoded.values.sumOf { it.size }
                demand(valueCount <= 1_000_000, "INDX 标签值总数过大")
                entries += MobiIndexEntry(label, decoded)
            }
        }
        demand(entries.size == total, "INDX 总条数不一致")
        val labels = linkedMapOf<Int, String>()
        repeat(ctocCount) { record ->
            checkActive()
            val data = pdb.record(start + records + 1 + record, 0x10000)
            budget += data.size
            demand(budget <= MobiBinary.MAX_TEXT_BYTES, "CTOC 总数据过大")
            var p = 0
            while (p < data.size && data.u8(p) != 0) {
                demand(labels.size < MobiBinary.MAX_INDEX_ENTRIES, "CTOC 条数过大")
                val key = record * 0x10000 + p
                val length = MobiBinary.forward(data, p)
                p += length.bytes
                val bytes = data.sliceChecked(p, length.value, "CTOC 字符串")
                labels[key] = bytes.toString(encoding)
                p += length.value
            }
        }
        return MobiIndex(entries, labels)
    }

    private fun checkHeader(data: ByteArray) {
        demand(data.magic(0, "INDX") && data.size >= 56, "INDX 头缺失或截断")
        val length = data.uint(4, "INDX 头长度")
        demand(length in 56..data.size, "INDX 头长度越界")
    }

    private fun decodeTags(data: ByteArray, controlStart: Int, valuesStart: Int, end: Int, controls: Int, tags: List<Tag>): Map<Int, List<Int>> {
        var cursor = valuesStart
        fun next(): Int = MobiBinary.forward(data, cursor, end).let { cursor += it.bytes; it.value }
        var control = 0
        val amounts = mutableListOf<TagAmount>()
        tags.forEach { tag ->
            if (tag.end == 1) { control++; return@forEach }
            demand(tag.end == 0 && control < controls && tag.mask != 0 && tag.values > 0, "TAGX 描述无效")
            val masked = data.u8(controlStart + control) and tag.mask
            if (masked != 0) {
                if (masked == tag.mask && Integer.bitCount(tag.mask) > 1) {
                    // 扩展计数先于所有标签值存放，必须先扫控制区再读取值区。
                    amounts += TagAmount(tag, null, next())
                } else {
                    amounts += TagAmount(tag, masked ushr Integer.numberOfTrailingZeros(tag.mask), null)
                }
            }
        }
        val result = linkedMapOf<Int, List<Int>>()
        amounts.forEach { amount ->
            val values = mutableListOf<Int>()
            if (amount.bytes != null) {
                demand(amount.bytes <= end - cursor, "TAGX 值区长度越界")
                val stop = cursor + amount.bytes
                while (cursor < stop) {
                    demand(values.size < 4096, "TAGX 单项值过多")
                    val v = MobiBinary.forward(data, cursor, stop)
                    cursor += v.bytes
                    values += v.value
                }
                demand(values.size % amount.tag.values == 0, "TAGX 值组不完整")
            } else {
                val count = amount.count!!.toLong() * amount.tag.values
                demand(count <= end - cursor && count <= 4096, "TAGX 值数量越界")
                repeat(count.toInt()) { values += next() }
            }
            demand(amount.tag.id !in result, "TAGX 重复标签")
            result[amount.tag.id] = values
        }
        return result
    }

    private companion object { const val MAX_INDEX_RECORD_BYTES = 1024 * 1024 }
}
