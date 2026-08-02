package com.mozhi.reader.feature.importer

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEncodingDetectorTest {
    private val detector = TextEncodingDetector()

    @Test
    fun `decodes utf8 with bom`() {
        val content = "第一章 墨知\n这是正文"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            content.toByteArray(Charsets.UTF_8)

        val result = detector.decode(bytes)

        assertEquals(content, result.text)
        assertTrue(result.charsetName.contains("UTF-8", ignoreCase = true))
    }

    @Test
    fun `decodes gb18030 chinese text`() {
        val content = "第一章 江南\n烟雨入梦，灯火可亲。"
        val result = detector.decode(content.toByteArray(Charset.forName("GB18030")))

        assertEquals(content, result.text)
    }
}
