package com.mozhi.reader.core.dictionary

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DictionaryCompanionImportTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun fixture(directory: File, source: String, name: String) = File(directory, name).apply {
        parentFile!!.mkdirs()
        DictionaryCompanionImportTest::class.java.getResourceAsStream("/dictionary/$source")!!.use { writeBytes(it.readBytes()) }
    }

    @Test fun mdxAutomaticallyImportsMatchingSplitResourcesAndCssDependenciesWithoutOtherDictionaries() = runTest {
        val folder = File(app.cacheDir, "companion").apply { mkdirs() }
        val mdx = fixture(folder, "sample-v2.mdx", "词典.MDX")
        fixture(folder, "sample.mdd", "词典.1.MDD")
        fixture(folder, "sample.mdd", "其他词典.mdd")
        File(folder, "词典.css").writeText("@import 'styles/layout.css'; .head{color:teal}")
        File(folder, "styles").mkdirs()
        File(folder, "styles/layout.css").writeText("@font-face{font-family:demo;src:url('../fonts/demo.woff')} p{background:url('../images/demo.svg')}")
        File(folder, "fonts").mkdirs()
        File(folder, "fonts/demo.woff").writeBytes(byteArrayOf(1, 2, 3))
        File(folder, "images").mkdirs()
        File(folder, "images/demo.svg").writeText("<svg xmlns='http://www.w3.org/2000/svg'/>")
        val repo = LocalDictionaryRepository(app)
        val result = repo.importMdxResult(Uri.fromFile(mdx))
        assertEquals("$result; uri=${Uri.fromFile(mdx)}; files=${DictionaryCompanionFiles(app).siblings(Uri.fromFile(mdx))}", 5, result.resourcesImported)
        assertTrue(result.resourceWarnings.isEmpty())
        assertEquals(5, result.dictionary.resourceCount)
        assertNotNull(repo.resource(result.dictionary.id, "styles/layout.css"))
        assertArrayEquals(byteArrayOf(1, 2, 3), repo.resource(result.dictionary.id, "fonts\\demo.woff"))
        assertNull(repo.resource(result.dictionary.id, "../../source-name.txt"))
        assertTrue(repo.lookup("apple").single().html.contains("词典.css"))
        val repeated = repo.importMdxResult(Uri.fromFile(mdx))
        assertTrue(repeated.duplicate)
        assertEquals(0, repeated.resourcesImported)
        assertEquals(5, repeated.dictionary.resourceCount)
    }

    @Test fun selectingOneDictionaryWithDifferentlyNamedResourcesAssociatesThemAndReportsBrokenMdd() = runTest {
        val folder = File(app.cacheDir, "selected").apply { mkdirs() }
        val mdx = fixture(folder, "sample-v2.mdx", "词典.mdx")
        val mdd = fixture(folder, "sample.mdd", "资源.mdd")
        val broken = File(folder, "损坏.mdd").apply { writeText("broken") }
        val result = LocalDictionaryRepository(app).importFiles(listOf(mdx, mdd, broken).map(Uri::fromFile))
        assertEquals(1, result.imported)
        assertEquals(1, result.resources)
        assertEquals(1, result.failures.size)
        assertTrue(result.failures.single().contains("损坏.mdd"))
        assertTrue(mdx.isFile && mdd.isFile && broken.isFile)
    }

    @Test fun folderImportKeepsEachDictionaryWithItsOwnResources() = runTest {
        val folder = File(app.cacheDir, "two").apply { mkdirs() }
        fixture(folder, "sample-v2.mdx", "A.mdx")
        fixture(folder, "sample-v1.mdx", "B.mdx")
        File(folder, "A.css").writeText("body{color:teal}")
        File(folder, "B.css").writeText("body{color:maroon}")
        val repo = LocalDictionaryRepository(app)
        val result = repo.importFolder(Uri.fromFile(folder))
        assertEquals(2, result.imported)
        assertEquals(result.toString(), 2, result.resources)
        val definitions = repo.lookup("apple")
        val a = definitions.first { it.html.contains("A.css") }
        val b = definitions.first { it.html.contains("B.css") }
        assertNotNull(repo.resource(a.dictionaryId, "A.css"))
        assertNull(repo.resource(a.dictionaryId, "B.css"))
        assertNotNull(repo.resource(b.dictionaryId, "B.css"))
        assertNull(repo.resource(b.dictionaryId, "A.css"))
    }

    @Test fun grantedDocumentTreeImportsNestedCssAndImagesFromARealContentProvider() = runTest {
        val folder = File(app.cacheDir, "saf").apply { mkdirs() }
        fixture(folder, "sample-v2.mdx", "词典.mdx")
        File(folder, "词典.css").writeText("body{background:url('images/paper.svg')}")
        File(folder, "images").mkdirs()
        File(folder, "images/paper.svg").writeText("<svg xmlns='http://www.w3.org/2000/svg'/>")
        val authority = "moread.test.dictionary.documents"
        val provider = TestDocuments(folder)
        provider.attachInfo(app, ProviderInfo().apply { this.authority = authority })
        ShadowContentResolver.registerProviderInternal(authority, provider)
        val repo = LocalDictionaryRepository(app)
        val result = repo.importFolder(DocumentsContract.buildTreeDocumentUri(authority, "root"))
        assertEquals(1, result.imported)
        assertEquals(2, result.resources)
        assertTrue(result.failures.isEmpty())
        assertNotNull(repo.resource(repo.list().single().id, "images/paper.svg"))
    }

    private class TestDocuments(private val root: File) : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
            val columns = projection ?: arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val cursor = MatrixCursor(columns)
            val id = DocumentsContract.getDocumentId(uri)
            val file = if (id == "root") root else File(root, id.removePrefix("root/"))
            val files = if (uri.lastPathSegment == "children") file.listFiles().orEmpty().toList() else listOf(file)
            files.forEach { child ->
                cursor.addRow(columns.map { column -> when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> "root/${child.relativeTo(root).invariantSeparatorsPath}"
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> child.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> if (child.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream"
                    else -> null
                } }.toTypedArray())
            }
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(
            File(root, DocumentsContract.getDocumentId(uri).removePrefix("root/")), ParcelFileDescriptor.MODE_READ_ONLY)
        override fun getType(uri: Uri) = "application/octet-stream"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    }
}
