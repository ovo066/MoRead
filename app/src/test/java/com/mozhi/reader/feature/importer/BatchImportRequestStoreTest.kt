package com.mozhi.reader.feature.importer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchImportRequestStoreTest {

    @Test
    fun `large request survives disk round trip without WorkManager Data`() {
        val directory = Files.createTempDirectory("moread-batch-import").toFile()
        try {
            val store = BatchImportRequestFiles(directory)
            val payload = BatchImportRequestPayload(
                entries = (1..500).map { index ->
                    BatchImportRequestEntry(
                        uri = "content://com.android.externalstorage.documents/document/primary%3A" +
                            "小说%2F很长的分类名称%2F第${index}本书.epub",
                        groupPath = "小说/很长的分类名称/第${index}组"
                    )
                },
                deleteSourceAfterImport = false
            )

            val requestId = store.create(payload)

            assertEquals(payload, store.read(requestId))
            assertTrue(directory.resolve("$requestId.json").length() > 10_240L)

            store.delete(requestId)
            assertNull(store.read(requestId))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid request id cannot escape request directory`() {
        val directory = Files.createTempDirectory("moread-batch-import").toFile()
        try {
            val store = BatchImportRequestFiles(directory)
            assertNull(store.read("../outside"))
            assertFalse(directory.resolve("outside.json").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
