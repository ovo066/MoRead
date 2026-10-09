package com.mozhi.reader.core.importer

import com.mozhi.reader.ai.persona.PersonaCardIdentity
import com.mozhi.reader.core.datastore.ReaderAssetDedup
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDeduplicationTest {

    @Test fun `streaming copy and in-memory hashing agree`() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        val streamed = SourceFingerprints.copy(ByteArrayInputStream(bytes), output)
        assertEquals(SourceFingerprints.of(bytes), streamed)
        assertTrue(bytes.contentEquals(output.toByteArray()))
        assertEquals(200_000L, streamed.size)
        assertEquals(64, streamed.sha256.length)
    }

    @Test fun `titles are compared without spacing brackets or extensions`() {
        assertEquals(BookTitles.normalize("三体（全集）.txt"), BookTitles.normalize("三体"))
        assertEquals(BookTitles.normalize(" The  Hobbit "), BookTitles.normalize("the hobbit"))
        assertFalse(BookTitles.normalize("三体2") == BookTitles.normalize("三体"))
    }

    @Test fun `asset dedup matches recorded hashes and lazily hashes same-size legacy files`() {
        val dir = Files.createTempDirectory("assets").toFile()
        val a = File(dir, "a.ttf").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val b = File(dir, "b.ttf").apply { writeBytes(byteArrayOf(9, 9, 9, 9)) }
        val incoming = SourceFingerprints.of(byteArrayOf(1, 2, 3, 4))
        val legacy = listOf("" to b.path, "" to a.path)
        assertEquals(a.path, ReaderAssetDedup.find(legacy, incoming, { it.second }, { it.first })?.second)
        val recorded = listOf(incoming.sha256 to b.path)
        assertEquals(b.path, ReaderAssetDedup.find(recorded, incoming, { it.second }, { it.first })?.second)
        assertNull(ReaderAssetDedup.find(listOf("" to File(dir, "missing.ttf").path), incoming, { it.second }, { it.first }))
        dir.deleteRecursively()
    }

    @Test fun `persona cards match only when name persona and greeting are the same`() {
        assertTrue(PersonaCardIdentity.same("林黛玉", "多愁善感", "你来了", " 林黛玉", "多愁 善感", "你来了"))
        assertFalse(PersonaCardIdentity.same("林黛玉", "多愁善感", "你来了", "林黛玉", "多愁善感", "你又来了"))
        assertFalse(PersonaCardIdentity.same("", "", "", "", "", ""))
    }
}
