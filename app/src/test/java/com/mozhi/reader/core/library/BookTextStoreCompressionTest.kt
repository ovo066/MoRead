package com.mozhi.reader.core.library

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BookTextStoreCompressionTest {
    @Test fun compressionPreservesContentRevisionAndStrictUtf16Coordinates() = runTest {
        val store = BookTextStore(RuntimeEnvironment.getApplication())
        val first = "甲🙂乙。".repeat(9000)
        val second = "后半章与批注位置一致。".repeat(4000)
        val firstBytes = first.toByteArray()
        val secondBytes = second.toByteArray()
        store.textFile(7).writeBytes(firstBytes + secondBytes)
        val before = store.contentRevision(7)
        assertTrue(store.compact(7) > 0)
        assertEquals(before, store.contentRevision(7))
        assertEquals(first, store.readChapterStrict(7, 0, firstBytes.size))
        assertEquals(second, store.readChapter(7, firstBytes.size.toLong(), secondBytes.size))
        assertEquals(second, store.readChapterStrict(7, firstBytes.size.toLong(), secondBytes.size))
    }
}
