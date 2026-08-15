package com.mozhi.reader.core.importer.lan

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartReaderTest {

    @Test
    fun readsSingleFilePart() {
        val body = multipart(
            part(disposition = "form-data; name=\"file\"; filename=\"三体.txt\"", content = "正文内容")
        )

        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY)
        val part = reader.nextPart()!!

        assertEquals("file", part.name)
        assertEquals("三体.txt", part.fileName)
        val sink = ByteArrayOutputStream()
        assertEquals(
            "正文内容".toByteArray().size.toLong(),
            reader.writeBodyTo(sink)
        )
        assertEquals("正文内容", sink.toString(Charsets.UTF_8.name()))
        assertNull(reader.nextPart())
    }

    @Test
    fun readsFieldThenFileAndSkipsUnwantedPart() {
        val body = multipart(
            part(disposition = "form-data; name=\"token\"", content = "abc"),
            part(disposition = "form-data; name=\"file\"; filename=\"a.epub\"", content = "EPUBDATA")
        )

        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY)

        val field = reader.nextPart()!!
        assertEquals("token", field.name)
        assertNull("普通表单字段没有文件名", field.fileName)
        // 不消费这一部分的内容，直接推进——nextPart 必须自己把它丢掉。
        val file = reader.nextPart()!!
        assertEquals("a.epub", file.fileName)
        val sink = ByteArrayOutputStream()
        reader.writeBodyTo(sink)
        assertEquals("EPUBDATA", sink.toString(Charsets.UTF_8.name()))
        assertNull(reader.nextPart())
    }

    /** 正文里出现「像边界但不是边界」的字节，不能把文件从中间截断。 */
    @Test
    fun bodyContainingPartialBoundaryIsPreserved() {
        // 与真边界只差最后几个字符：匹配必须走到最后一位才判定失败，考验跨缓冲的回退。
        val nearMiss = BOUNDARY.dropLast(3) + "XYZ"
        val tricky = "第一行\r\n--$nearMiss\r\n第二行\r\n--短\r\n收尾"
        val body = multipart(
            part(disposition = "form-data; name=\"file\"; filename=\"x.txt\"", content = tricky)
        )

        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY, bufferSize = 128)
        reader.nextPart()
        val sink = ByteArrayOutputStream()
        reader.writeBodyTo(sink)

        assertEquals(tricky, sink.toString(Charsets.UTF_8.name()))
    }

    /** 二进制体（含 CRLF 与 0x00）必须逐字节原样落盘。 */
    @Test
    fun binaryBodySurvivesSmallBufferRefills() {
        val binary = ByteArray(9_000) { index -> (index % 256).toByte() }
        val body = multipart(
            part(
                disposition = "form-data; name=\"file\"; filename=\"b.epub\"",
                contentBytes = binary
            )
        )

        // 缓冲区故意开得很小，逼迫边界匹配跨多次填充。
        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY, bufferSize = 256)
        reader.nextPart()
        val sink = ByteArrayOutputStream()
        val written = reader.writeBodyTo(sink)

        assertEquals(binary.size.toLong(), written)
        assertArrayEquals(binary, sink.toByteArray())
    }

    @Test
    fun decodesRfc5987FileName() {
        val body = multipart(
            part(
                disposition = "form-data; name=\"file\"; " +
                    "filename=\"_.txt\"; filename*=UTF-8''%E4%B8%89%E4%BD%93.txt",
                content = "x"
            )
        )

        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY)

        // filename* 优先于退化的 ASCII filename。
        assertEquals("三体.txt", reader.nextPart()!!.fileName)
    }

    @Test
    fun ignoresPreambleBeforeFirstBoundary() {
        val withPreamble = "这是一些客户端写的说明文字\r\n".toByteArray() + multipart(
            part(disposition = "form-data; name=\"file\"; filename=\"a.txt\"", content = "内容")
        )

        val reader = MultipartReader(ByteArrayInputStream(withPreamble), BOUNDARY)

        assertEquals("a.txt", reader.nextPart()!!.fileName)
    }

    @Test
    fun truncatedStreamFailsLoudly() {
        val complete = multipart(
            part(disposition = "form-data; name=\"file\"; filename=\"a.txt\"", content = "内容内容内容")
        )
        val truncated = complete.copyOf(complete.size - 20)

        val reader = MultipartReader(ByteArrayInputStream(truncated), BOUNDARY)
        reader.nextPart()

        // 半截上传必须报错，绝不能把残缺文件当成功落盘。
        assertThrows(IOException::class.java) { reader.writeBodyTo(ByteArrayOutputStream()) }
    }

    @Test
    fun emptyBodyPartYieldsZeroBytes() {
        val body = multipart(
            part(disposition = "form-data; name=\"file\"; filename=\"empty.txt\"", content = "")
        )

        val reader = MultipartReader(ByteArrayInputStream(body), BOUNDARY)
        reader.nextPart()
        val sink = ByteArrayOutputStream()

        assertEquals(0L, reader.writeBodyTo(sink))
        assertTrue(sink.toByteArray().isEmpty())
    }

    private companion object {
        const val BOUNDARY = "----MoReadBoundaryQWERTY"

        class Part(val disposition: String, val contentType: String?, val bytes: ByteArray)

        fun part(
            disposition: String,
            content: String? = null,
            contentBytes: ByteArray? = null,
            contentType: String? = null
        ) = Part(disposition, contentType, contentBytes ?: content.orEmpty().toByteArray())

        fun multipart(vararg parts: Part): ByteArray {
            val output = ByteArrayOutputStream()
            parts.forEach { part ->
                output.write("--$BOUNDARY\r\n".toByteArray())
                output.write("Content-Disposition: ${part.disposition}\r\n".toByteArray())
                part.contentType?.let { output.write("Content-Type: $it\r\n".toByteArray()) }
                output.write("\r\n".toByteArray())
                output.write(part.bytes)
                output.write("\r\n".toByteArray())
            }
            output.write("--$BOUNDARY--\r\n".toByteArray())
            return output.toByteArray()
        }
    }
}
