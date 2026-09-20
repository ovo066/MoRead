package com.mozhi.reader.core.dictionary

import android.app.Application
import android.net.Uri
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MultilingualDictionaryTest {
    @Test fun multipleDictionariesKeepChineseEntriesResourcesAndEnabledStateAcrossReopen() = runTest {
        val app = RuntimeEnvironment.getApplication()
        fun fixture(name: String) = File(app.cacheDir, name).apply {
            MultilingualDictionaryTest::class.java.getResourceAsStream("/dictionary/$name")!!.use { writeBytes(it.readBytes()) }
        }
        val repository = LocalDictionaryRepository(app)
        val english = repository.importMdx(Uri.fromFile(fixture("sample-v2.mdx")))
        val classical = repository.importMdx(Uri.fromFile(fixture("sample-classical.mdx")))
        repository.importResources(classical.id, listOf(Uri.fromFile(fixture("sample.mdd"))))
        assertEquals(2, repository.list().size)
        assertEquals(2, repository.lookup("apple").size)
        assertTrue(repository.lookup("学而时习之").single().html.contains("学习后按时温习"))
        assertTrue(repository.lookup("故").single().html.contains("旧的"))
        assertEquals("body{color:blue}", repository.resource(classical.id, "style.css")!!.toString(Charsets.UTF_8))
        repository.setEnabled(english.id, false)
        val reopened = LocalDictionaryRepository(app)
        assertFalse(reopened.list().first { it.id == english.id }.enabled)
        assertEquals(classical.id, reopened.lookup("apple").single().dictionaryId)
        reopened.setEnabled(english.id, true)
        reopened.delete(classical.id)
        assertEquals(english.id, reopened.lookup("apple").single().dictionaryId)
        assertTrue(reopened.lookup("故").isEmpty())
    }

    @Test fun selectionQueriesAcceptChineseClassicalPhrasesAndOtherLanguages() {
        listOf("故", "学而时习之", "résumé", "take off", "日本語", "APPLE").forEach { assertEquals(it, dictionaryQuery(" $it ")) }
        assertEquals("take off", dictionaryQuery("take\n off"))
        listOf("", "   ", "……", "词".repeat(81), "a\u0000b").forEach { assertNull(dictionaryQuery(it)) }
    }
}
