package com.mozhi.reader.core.dictionary

import android.app.Application
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Adler32
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DictionaryManagementTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun fixture(source: String, name: String = source) = File(app.cacheDir, name).apply {
        DictionaryManagementTest::class.java.getResourceAsStream("/dictionary/$source")!!.use { writeBytes(it.readBytes()) }
    }

    @Test fun identicalFilesAreSkippedEvenAfterRenameOrReopenAndDoNotLoseResourcesOrDisabledState() = runTest {
        val repo = LocalDictionaryRepository(app)
        val file = fixture("sample-v2.mdx")
        val first = repo.importMdxResult(Uri.fromFile(file))
        val mdd = fixture("sample.mdd")
        assertEquals(DictionaryResourceImportResult(1, 1), repo.importResources(first.dictionary.id, listOf(Uri.fromFile(mdd), Uri.fromFile(mdd))))
        repo.setEnabled(first.dictionary.id, false)
        val renamed = File(app.cacheDir, "改名后的词典.MDX").also { file.copyTo(it) }
        val dir = File(app.filesDir, "reader-custom/dictionaries/${first.dictionary.id}")
        File(dir, "main.mdx.sha256").delete() // legacy imports acquire a fingerprint on first comparison
        val reopened = LocalDictionaryRepository(app)
        val duplicate = reopened.importMdxResult(Uri.fromFile(renamed))
        assertFalse(first.duplicate)
        assertTrue(duplicate.duplicate)
        assertEquals(first.dictionary.id, duplicate.dictionary.id)
        assertFalse(duplicate.dictionary.enabled)
        assertEquals(1, duplicate.dictionary.resourceCount)
        assertEquals(1, reopened.list().size)
        assertEquals(1, dir.parentFile!!.listFiles()!!.size) // no abandoned import directory
        assertEquals("body{color:blue}", reopened.resource(first.dictionary.id, "style.css")!!.toString(Charsets.UTF_8))
    }

    @Test fun sameNameDifferentContentIsKeptAndDeletionDoesNotTouchOriginalsOrOtherDictionaries() = runTest {
        val repo = LocalDictionaryRepository(app)
        val file = fixture("sample-v2.mdx", "词典.mdx")
        val first = repo.importMdxResult(Uri.fromFile(file))
        file.writeBytes(fixture("sample-v1.mdx").readBytes())
        val second = repo.importMdxResult(Uri.fromFile(file))
        assertFalse(second.duplicate)
        assertNotEquals(first.dictionary.id, second.dictionary.id)
        val mdd = fixture("sample.mdd")
        repo.importResources(first.dictionary.id, listOf(Uri.fromFile(mdd)))
        repo.delete(first.dictionary.id)
        assertEquals(listOf(second.dictionary.id), repo.list().map { it.id })
        assertFalse(File(app.filesDir, "reader-custom/dictionaries/${first.dictionary.id}").exists())
        assertTrue(file.isFile && mdd.isFile)
        assertTrue(repo.lookup("apple").single().html.contains("苹果"))
    }

    @Test fun concurrentImportsPublishOnlyOneDictionary() = runTest {
        val repo = LocalDictionaryRepository(app)
        val uri = Uri.fromFile(fixture("sample-v2.mdx"))
        val results = coroutineScope { List(2) { async(Dispatchers.Default) { repo.importMdxResult(uri) } }.awaitAll() }
        assertEquals(1, results.count { it.duplicate })
        assertEquals(1, repo.list().size)
    }

    @Test fun missingOrPlaceholderTitlesUseTheSourceFilenameAndOldPlaceholderImportsAreRepaired() = runTest {
        val repo = LocalDictionaryRepository(app)
        for ((index, title) in listOf("", "   ", "Title (No HTML code allowed)").withIndex()) {
            val file = fixture("sample-v2.mdx", "牛津词典第${index + 1}版.MDX")
            replaceTitle(file, title)
            val result = repo.importMdxResult(Uri.fromFile(file))
            assertEquals(file.nameWithoutExtension, result.dictionary.title)
        }
        val file = fixture("sample-v1.mdx", "古汉语词典.mdx")
        val initial = repo.importMdxResult(Uri.fromFile(file))
        val dir = File(app.filesDir, "reader-custom/dictionaries/${initial.dictionary.id}")
        File(dir, "title.txt").writeText("Title (No HTML code allowed)")
        File(dir, "source-name.txt").delete()
        val repaired = repo.importMdxResult(Uri.fromFile(file))
        assertTrue(repaired.duplicate)
        assertEquals("古汉语词典", repaired.dictionary.title)
        assertEquals("古汉语词典", LocalDictionaryRepository(app).list().first { it.id == initial.dictionary.id }.title)
        assertEquals("正常词典", dictionaryDisplayTitle("<b>正常词典</b>", "file.mdx"))
    }

    private fun replaceTitle(file: File, title: String) {
        val bytes = file.readBytes()
        val oldLength = ByteBuffer.wrap(bytes).int
        val header = bytes.copyOfRange(4, 4 + oldLength).toString(Charsets.UTF_16LE)
            .replace(Regex("Title=\"[^\"]*\""), "Title=\"$title\"").toByteArray(Charsets.UTF_16LE)
        val prefix = ByteBuffer.allocate(header.size + 8).putInt(header.size).put(header)
            .order(ByteOrder.LITTLE_ENDIAN).putInt(Adler32().apply { update(header) }.value.toInt()).array()
        file.writeBytes(prefix + bytes.copyOfRange(oldLength + 8, bytes.size))
    }
}
