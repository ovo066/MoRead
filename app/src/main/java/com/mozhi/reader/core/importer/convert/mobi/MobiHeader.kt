package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import java.nio.charset.Charset

internal class MobiHeader(val base: Int, data: ByteArray) {
    val compression: Int
    val textLength: Int
    val textRecords: Int
    val charset: Charset
    val version: Int
    val fullName: String
    val locale: Long
    val firstResource: Int?
    val extraFlags: Int
    val ncx: Int?
    val fdst: Int?
    val flowCount: Int?
    val fragments: Int?
    val skeletons: Int?
    val exth = linkedMapOf<Int, MutableList<ByteArray>>()

    init {
        demand(data.size >= 16, "PalmDOC 头截断")
        if (data.u16(12) != 0) throw BookConversionException(Reason.DRM_PROTECTED)
        compression = data.u16(0)
        val declared = data.u32(4)
        if (declared > MobiBinary.MAX_TEXT_BYTES) throw BookConversionException(Reason.TEXT_TOO_LARGE)
        textLength = declared.toInt()
        textRecords = data.u16(8)
        demand(textRecords > 0, "缺少正文记录")
        if (!data.magic(16, "MOBI")) unsupported("缺少 MOBI 头")
        val headerLength = data.uint(20, "MOBI 头长度")
        demand(headerLength >= 24, "MOBI 头过短")
        range(16, headerLength.toLong(), data.size, "MOBI 头")
        fun present(offset: Int, width: Int = 4) = offset.toLong() + width <= 16L + headerLength
        fun number(offset: Int): Long? = if (present(offset)) data.u32(offset) else null
        fun pointer(offset: Int): Int? = number(offset)?.takeUnless { it == 0xffffffffL }?.let {
            demand(it <= Int.MAX_VALUE, "MOBI 记录号溢出")
            it.toInt()
        }
        charset = when (data.u32(28)) {
            1252L -> Charset.forName("windows-1252")
            65001L -> Charsets.UTF_8
            else -> unsupported("MOBI 文本编码不受支持")
        }
        version = data.uint(36, "MOBI 版本")
        if (version > 8) unsupported("MOBI 版本高于 8")
        fullName = if (present(88)) {
            val start = data.uint(84, "书名偏移")
            val size = data.uint(88, "书名长度")
            data.sliceChecked(start, size, "书名").toString(charset).trimEnd('\u0000').trim()
        } else ""
        locale = number(92) ?: 0
        firstResource = pointer(108)
        extraFlags = if (present(242, 2)) data.u16(242) else 0
        ncx = pointer(244)
        fdst = if (version == 8) pointer(192) else null
        flowCount = if (version == 8) pointer(196) else null
        fragments = if (version == 8) pointer(248) else null
        skeletons = if (version == 8) pointer(252) else null
        if ((number(128) ?: 0) and 0x40L != 0L) {
            val start = 16 + headerLength
            demand(data.magic(start, "EXTH"), "EXTH 标志存在但头缺失")
            val size = data.uint(start + 4, "EXTH 长度")
            demand(size >= 12, "EXTH 长度过短")
            range(start.toLong(), size.toLong(), data.size, "EXTH")
            val count = data.uint(start + 8, "EXTH 条数")
            demand(count <= (size - 12) / 8, "EXTH 条数超出记录长度")
            var p = start + 12
            repeat(count) {
                demand(p <= start + size - 8, "EXTH 条目头截断")
                val type = data.uint(p, "EXTH 类型")
                val length = data.uint(p + 4, "EXTH 条目长度")
                demand(length >= 8 && length <= start + size - p, "EXTH 条目越界")
                if (type in setOf(100, 503, 201, 121, 524)) {
                    exth.getOrPut(type) { mutableListOf() }.add(data.copyOfRange(p + 8, p + length))
                }
                p += length
            }
        }
    }

    fun strings(type: Int): List<String> = exth[type].orEmpty().map { it.toString(charset).trimEnd('\u0000').trim() }
        .filter(String::isNotBlank)

    fun integer(type: Int): Int? = exth[type]?.firstOrNull()?.let {
        demand(it.size == 4, "EXTH 数值长度无效")
        it.u32(0).takeUnless { value -> value == 0xffffffffL }?.let { value ->
            demand(value <= Int.MAX_VALUE, "EXTH 数值溢出")
            value.toInt()
        }
    }

    fun language(): String {
        val explicit = strings(524).firstOrNull()?.replace('_', '-')
        if (explicit != null && explicit.matches(Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*"))) return explicit
        // locale 的低十位为语言，区域只在能够确定时附加，未知值保留为 und。
        val primary = (locale and 0x3ff).toInt()
        val region = ((locale ushr 10) and 63).toInt()
        return when (primary) {
            4 -> when (region) { 1 -> "zh-TW"; 2 -> "zh-CN"; 3 -> "zh-HK"; 4 -> "zh-SG"; else -> "zh" }
            9 -> when (region) { 1 -> "en-US"; 2 -> "en-GB"; else -> "en" }
            else -> mapOf(1 to "ar", 5 to "cs", 6 to "da", 7 to "de", 8 to "el", 10 to "es",
                11 to "fi", 12 to "fr", 13 to "he", 14 to "hu", 15 to "is", 16 to "it", 17 to "ja",
                18 to "ko", 19 to "nl", 20 to "no", 21 to "pl", 22 to "pt", 24 to "ro", 25 to "ru",
                26 to "hr", 27 to "sk", 29 to "sv", 30 to "th", 31 to "tr", 33 to "id", 34 to "uk",
                36 to "sl", 37 to "et", 38 to "lv", 39 to "lt", 42 to "vi", 57 to "hi")[primary] ?: "und"
        }
    }
}
