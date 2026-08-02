package com.mozhi.reader.feature.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import org.mozilla.universalchardet.UniversalDetector

class TextEncodingDetector @Inject constructor() {
    fun decode(bytes: ByteArray): DetectedText {
        val bomCharset = detectBom(bytes)
        val detected = bomCharset ?: detectWithUniversalDetector(bytes)
        val charset = runCatching { Charset.forName(detected ?: "UTF-8") }
            .getOrElse { Charsets.UTF_8 }

        val decoded = (listOf(charset) + fallbackCharsets(charset))
            .firstNotNullOfOrNull { candidate ->
                decodeStrict(bytes, candidate)?.let { candidate to it }
            }
            ?: (charset to bytes.toString(charset))

        return DetectedText(
            charsetName = decoded.first.name(),
            text = decoded.second.removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
        )
    }

    private fun detectWithUniversalDetector(bytes: ByteArray): String? {
        val detector = UniversalDetector(null)
        detector.handleData(bytes, 0, bytes.size)
        detector.dataEnd()
        return detector.detectedCharset.also { detector.reset() }
    }

    private fun detectBom(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte() -> "UTF-8"
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> "UTF-16LE"
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> "UTF-16BE"
        else -> null
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun fallbackCharsets(primary: Charset): List<Charset> =
        listOf("UTF-8", "GB18030", "Big5", "UTF-16LE", "UTF-16BE")
            .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
            .filterNot { it == primary }
}
