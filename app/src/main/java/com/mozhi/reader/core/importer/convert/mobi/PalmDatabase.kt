package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

internal class PalmDatabase(file: File) : Closeable {
    private val input = RandomAccessFile(file, "r")
    private val offsets: LongArray
    val count: Int get() = offsets.size - 1

    init {
        try {
            val length = input.length()
            if (length > MobiBookConverter.MAX_SOURCE_BYTES) throw BookConversionException(Reason.MOBI_TOO_LARGE)
            if (length < 78) unsupported("不是 PalmDB 电子书")
            val header = ByteArray(78).also(input::readFully)
            if (!header.magic(60, "BOOKMOBI") && !header.magic(60, "TEXtREAd")) unsupported("不是 PalmDB 电子书")
            if (header.u16(32) and 1 != 0) unsupported("PalmDB 资源数据库")
            demand(header.u32(72) == 0L, "不支持链式 PalmDB 记录表")
            val records = header.u16(76)
            demand(records > 0 && 78L + records * 8L <= length, "PalmDB 记录表截断")
            offsets = LongArray(records + 1)
            val entry = ByteArray(8)
            repeat(records) { i ->
                input.readFully(entry)
                val offset = entry.u32(0)
                demand(offset >= 78L + records * 8L && offset < length, "PalmDB 记录偏移越界")
                demand(i == 0 || offset > offsets[i - 1], "PalmDB 记录偏移未递增")
                offsets[i] = offset
            }
            offsets[records] = length
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    fun length(index: Int): Int {
        demand(index in 0 until count, "PalmDB 记录号越界")
        return (offsets[index + 1] - offsets[index]).toInt()
    }

    fun record(index: Int, maxBytes: Int = MobiBinary.MAX_TEXT_BYTES): ByteArray {
        val size = length(index)
        demand(size <= maxBytes, "PalmDB 单条记录过大")
        input.seek(offsets[index])
        return ByteArray(size).also(input::readFully)
    }

    fun prefix(index: Int, size: Int): ByteArray {
        val count = minOf(length(index), size)
        input.seek(offsets[index])
        return ByteArray(count).also(input::readFully)
    }

    fun absolute(base: Int, relative: Int): Int {
        val index = base.toLong() + relative
        demand(relative >= 0 && index in 0 until count.toLong(), "相对记录号越界")
        return index.toInt()
    }

    override fun close() = input.close()
}
