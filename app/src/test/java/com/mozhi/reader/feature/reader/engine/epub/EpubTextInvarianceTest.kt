package com.mozhi.reader.feature.reader.engine.epub

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class EpubTextInvarianceTest {
    @Test
    fun `fixture text stays byte-for-byte stable`() {
        FineLayoutFixtures.names.forEach { name ->
            val actual = FineLayoutFixtures.parse(name).text.toByteArray(StandardCharsets.UTF_8)
            val snapshot = FineLayoutFixtures.snapshotFile("text/$name.txt")
            if (System.getenv("REGEN_SNAPSHOTS") == "1") {
                snapshot.parentFile.mkdirs()
                snapshot.writeBytes(actual)
            } else {
                assertArrayEquals("text.mz coordinate text changed for $name", snapshot.readBytes(), actual)
            }
        }
    }
}
