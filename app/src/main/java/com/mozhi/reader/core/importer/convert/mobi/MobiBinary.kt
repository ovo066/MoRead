package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookConversionException.Reason

internal fun corrupt(detail: String): Nothing = throw BookConversionException(Reason.CORRUPT_FILE, detail)
internal fun unsupported(detail: String): Nothing = throw BookConversionException(Reason.UNSUPPORTED_FORMAT, detail)
internal fun demand(ok: Boolean, detail: String) { if (!ok) corrupt(detail) }

/** 先用 Long 校验范围，再转成数组下标，避免偏移加长度发生整数回绕。 */
internal fun range(offset: Long, length: Long, size: Int, field: String): IntRange {
    demand(offset >= 0 && length >= 0 && offset <= size && length <= size.toLong() - offset, "$field 越界")
    return offset.toInt() until (offset + length).toInt()
}
internal fun ByteArray.u8(at: Int): Int {
    demand(at in indices, "字节偏移越界")
    return this[at].toInt() and 255
}
internal fun ByteArray.u16(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
internal fun ByteArray.u32(at: Int): Long = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
internal fun ByteArray.uint(at: Int, field: String): Int {
    val value = u32(at)
    demand(value <= Int.MAX_VALUE, "$field 数值溢出")
    return value.toInt()
}
internal fun ByteArray.magic(at: Int, text: String): Boolean = at >= 0 && at.toLong() + text.length <= size &&
    text.indices.all { u8(at + it) == text[it].code }
internal fun ByteArray.sliceChecked(start: Int, length: Int, field: String): ByteArray {
    range(start.toLong(), length.toLong(), size, field)
    return copyOfRange(start, start + length)
}

internal data class VariableInteger(val value: Int, val bytes: Int)

internal object MobiBinary {
    const val MAX_TEXT_BYTES = 64 * 1024 * 1024
    const val MAX_INDEX_ENTRIES = 100_000

    fun forward(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): VariableInteger {
        range(start.toLong(), (end.toLong() - start), bytes.size, "变长整数")
        var value = 0L
        for (i in 0 until minOf(5, end - start)) {
            val b = bytes.u8(start + i)
            value = (value shl 7) or (b and 127).toLong()
            demand(value <= Int.MAX_VALUE, "正向变长整数溢出")
            if (b and 128 != 0) return VariableInteger(value.toInt(), i + 1)
        }
        corrupt("正向变长整数未终止")
    }

    fun backward(bytes: ByteArray, end: Int = bytes.size): VariableInteger {
        demand(end in 1..bytes.size, "反向变长整数越界")
        var value = 0L
        for (i in 0 until minOf(5, end)) {
            val b = bytes.u8(end - i - 1)
            value = value or ((b and 127).toLong() shl (7 * i))
            demand(value <= Int.MAX_VALUE, "反向变长整数溢出")
            if (b and 128 != 0) return VariableInteger(value.toInt(), i + 1)
        }
        corrupt("反向变长整数未终止")
    }

    fun stripTrailing(bytes: ByteArray, flags: Int): ByteArray {
        demand(flags in 0..65535, "尾部标志越界")
        var end = bytes.size
        // 每个高位项的长度包含其长度整数本身；低位补齐项在所有高位项之后剥离。
        for (bit in 1..15) if (flags and (1 shl bit) != 0) {
            val length = backward(bytes, end)
            demand(length.value >= length.bytes && length.value <= end, "尾部项长度越界")
            end -= length.value
        }
        if (flags and 1 != 0) {
            demand(end > 0, "缺少多字节补齐项")
            val length = (bytes.u8(end - 1) and 3) + 1
            demand(length <= end, "多字节补齐项越界")
            end -= length
        }
        return bytes.copyOf(end)
    }

    fun palmDoc(bytes: ByteArray, limit: Int = MAX_TEXT_BYTES): ByteArray {
        demand(limit in 0..MAX_TEXT_BYTES, "解压预算越界")
        // 不按文件声明的正文长度预分配，缓冲区只随实际输出增长。
        var output = ByteArray(minOf(4096, limit))
        var size = 0
        fun put(value: Int) {
            if (size == limit) throw BookConversionException(Reason.TEXT_TOO_LARGE)
            if (size == output.size) output = output.copyOf(minOf(limit, maxOf(16, output.size * 2)))
            output[size++] = value.toByte()
        }
        var p = 0
        while (p < bytes.size) {
            val b = bytes.u8(p++)
            when (b) {
                in 1..8 -> {
                    demand(b <= bytes.size - p, "PalmDOC 字面量截断")
                    repeat(b) { put(bytes.u8(p++)) }
                }
                in 0x80..0xbf -> {
                    demand(p < bytes.size, "PalmDOC 回溯指令截断")
                    val pair = ((b and 63) shl 8) or bytes.u8(p++)
                    val distance = pair ushr 3
                    val length = (pair and 7) + 3
                    demand(distance in 1..size, "PalmDOC 回溯距离越界")
                    // 重叠拷贝必须逐字节读取刚写出的数据。
                    repeat(length) { put(output[size - distance].toInt()) }
                }
                in 0xc0..0xff -> { put(32); put(b xor 128) }
                else -> put(b)
            }
        }
        return output.copyOf(size)
    }

    /** Kindle 使用 0-9 / A-V 的 base32；并非 RFC 4648 的字母表。 */
    fun base32(value: String): Int {
        demand(value.isNotEmpty() && value.length <= 7, "Kindle 序号长度无效")
        var result = 0L
        value.forEach { char ->
            val digit = "0123456789ABCDEFGHIJKLMNOPQRSTUV".indexOf(char.uppercaseChar())
            demand(digit >= 0, "Kindle 序号字符无效")
            result = result * 32 + digit
            demand(result <= Int.MAX_VALUE, "Kindle 序号溢出")
        }
        return result.toInt()
    }

    fun embedIndex(value: String): Int {
        val index = base32(value)
        demand(index > 0, "Kindle 图片序号必须从 1 起算")
        return index - 1
    }
}
