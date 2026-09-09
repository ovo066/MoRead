package com.mozhi.reader.ai.companion

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deliberate guard: a future annotation memory source needs an explicit boundary review. */
class AnnotationMemoryBoundaryTest {
    @Test fun memoryDoesNotReadAnnotationRepositoryOrDao() {
        val root = listOf(File("src/main/java/com/mozhi/reader/ai/memory"),
            File("app/src/main/java/com/mozhi/reader/ai/memory")).first { it.isDirectory }
        listOf("MemoryConsolidator.kt", "RollingSummarizer.kt").forEach { name ->
            val source = File(root, name).readText()
            assertFalse(source.contains("AnnotationRepository"))
            assertFalse(source.contains("AnnotationDao"))
        }
    }
    @Test fun promptAndReadbackUseSharedAnnotationVisibility() {
        val root = listOf(File("src/main/java/com/mozhi/reader/ai"),
            File("app/src/main/java/com/mozhi/reader/ai")).first { it.isDirectory }
        assertTrue(File(root, "agent/ReaderToolset.kt").readText().contains("getVisibleCounts"))
        listOf("prompt/CompanionContextBuilder.kt", "agent/ReaderToolsetReadback.kt").forEach {
            assertTrue(File(root, it).readText().contains("AnnotationVisibility.isVisible"))
        }
    }
}
