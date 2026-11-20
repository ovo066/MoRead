package com.mozhi.reader.core.importer.convert.mobi

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.database.entity.BookSourceType
import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import com.mozhi.reader.core.epub.EpubPackageInspector
import com.mozhi.reader.core.epub.EpubTextExtractor
import com.mozhi.reader.core.importer.PreparedImport
import com.mozhi.reader.core.importer.SourceFingerprints
import com.mozhi.reader.core.importer.convert.MinimalEpubWriter
import com.mozhi.reader.core.library.BookLayoutStore
import com.mozhi.reader.core.library.BookMediaStore
import com.mozhi.reader.core.library.BookTocEntryDraft
import com.mozhi.reader.core.library.ChapterDraft
import com.mozhi.reader.core.library.ChapterTextInput
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.readium.ReadiumServices
import com.mozhi.reader.feature.importer.ImportCoordinator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 缺失的真实样书分别跳过，手工向量测试仍独立运行。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MobiFixtureTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun oldMobi() = checkFixture("sample-old.mobi", kf8 = false)
    @Test fun pureKf8() = checkFixture("sample-kf8.azw3", kf8 = true)
    @Test fun combinedMobiPrefersKf8() = checkFixture("sample-both.mobi", kf8 = true)

    private fun checkFixture(name: String, kf8: Boolean) = runBlocking {
        val resource = javaClass.getResourceAsStream("/mobi/$name")
        assumeNotNull(resource)
        val file = temporary.newFile(name)
        resource!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val fingerprint = SourceFingerprints.of(file)
        val book = MobiBookConverter().convert(file)
        assertEquals("墨知样书", book.title)
        assertEquals("测试作者", book.author)
        assertEquals("zh", book.language.substringBefore('-'))
        val toc = book.chapters.filter { it.inToc }
        assertEquals(TITLES, toc.map { it.title })
        val docs = toc.map { Jsoup.parse(it.xhtmlBody) }
        SENTENCES.forEachIndexed { index, sentences ->
            sentences.forEach { sentence ->
                assertTrue("Missing chapter ${index + 1} sentence", docs[index].text().contains(sentence))
                docs.forEachIndexed { other, doc -> if (other != index) assertFalse("Text crossed chapter boundary", doc.text().contains(sentence)) }
            }
        }
        val image = docs[0].select("img").map { it.attr("src") }
            .firstNotNullOfOrNull { href -> book.resources.firstOrNull { it.href == href && it.mediaType.startsWith("image/") } }
        assertNotNull("First chapter illustration must be packaged; resources: ${book.resources.map { it.href + ' ' + it.mediaType }}; body: ${toc[0].xhtmlBody}", image)
        // 样书里是一张 16×16 的 PNG；转换工具可能把它换成别的通用格式（旧式文件里常是 JPEG 或 GIF），只要求解得开、尺寸没变。
        val decoded = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(image!!.bytes))
        assertNotNull("Illustration must decode (${image.mediaType}, ${image.bytes.size} bytes)", decoded)
        assertEquals(16, decoded.width)
        assertEquals(16, decoded.height)
        // 旧式 MOBI 可能用 b，KF8 也可能把 font-weight 保存在样式表；都必须保留语义。
        val bold = docs[1].select("b,strong,[style]").any { element ->
            element.text().contains("一整夜") && (element.normalName() in setOf("b", "strong") ||
                Regex("font-weight\\s*:\\s*(bold|[7-9]00)", RegexOption.IGNORE_CASE).containsMatchIn(element.attr("style")))
        }
        val styles = book.resources.filter { it.mediaType == "text/css" }.joinToString("\n") { it.bytes.toString(Charsets.UTF_8) }
        val classBold = docs[1].select("[class]").any { element ->
            element.text().contains("一整夜") && element.classNames().any { cls ->
                Regex("\\.${Regex.escape(cls)}\\b[^{}]*\\{[^{}]*font-weight\\s*:\\s*(bold|[7-9]00)", RegexOption.IGNORE_CASE).containsMatchIn(styles)
            }
        }
        assertTrue("Bold emphasis must survive", bold || classBold)
        assertTrue(docs[1].select("blockquote").any { it.text().contains("逝者如斯夫，不舍昼夜。") })
        assertTrue(docs[2].text().contains("（全书完）"))
        if (kf8) {
            assertTrue(docs[2].select(".note").any { it.text().contains("（全书完）") })
            // 生成工具可能把 #aa3333 缩写成 #a33，两者表示同一条颜色声明。
            assertTrue(Regex("\\.note\\b[^{}]*\\{[^{}]*color\\s*:\\s*#(?:aa3333|a33)\\b", RegexOption.IGNORE_CASE).containsMatchIn(styles))
        }

        val epub = temporary.newFile("$name.epub")
        MinimalEpubWriter.write(book, fingerprint.sha256, epub)
        val inspector = EpubPackageInspector()
        val layout = inspector.inspect(epub)
        assertEquals(book.chapters.size, layout.spine.size)
        assertFalse(layout.diagnostics.any { it.code == "missing-resource" })
        ZipFile(epub).use { zip ->
            val nav = zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).use {
                Jsoup.parse(it.readBytes().toString(Charsets.UTF_8), "", Parser.xmlParser())
            }
            assertEquals(TITLES, nav.select("nav li a").map { it.text() })
            assertNotNull(zip.getEntry("OEBPS/${image.href}"))
        }

        val files = temporary.newFolder()
        val cache = temporary.newFolder()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        val readium = ReadiumServices(context)
        val publication = readium.open(epub)
        try {
            assertEquals(book.chapters.size, publication.readingOrder.size)
            assertEquals(TITLES, publication.tableOfContents.map { it.title })
        } finally { publication.close() }

        // 与 DocumentImportPipelineTest 相同：真实转换、Readium、正文抽取及布局存储，只替换数据库。
        val library = mockk<LibraryRepository>(relaxed = true)
        val entity = slot<BookEntity>()
        val chapters = slot<List<ChapterDraft>>()
        val entries = slot<List<BookTocEntryDraft>>()
        val bodies = slot<List<ChapterTextInput>>()
        coEvery { library.findBookBySourceHash(any()) } returns null
        coEvery { library.insertBook(capture(entity), capture(chapters), capture(entries)) } returns 31L
        coEvery { library.materializeBookText(31L, capture(bodies), false) } returns Unit
        val importer = ImportCoordinator(context, mockk(), mockk(), mockk(), EpubTextExtractor(), EpubLayoutDocumentParser(),
            inspector, BookLayoutStore(context), BookMediaStore(context), mockk(), readium, library)
        assertEquals(PreparedImport.BookImported(31L), importer.prepare(Uri.fromFile(file), false))
        assertEquals(BookSourceType.EPUB, entity.captured.sourceType)
        assertEquals(book.title, entity.captured.title)
        assertEquals(book.author, entity.captured.author)
        assertEquals(fingerprint.sha256, entity.captured.sourceSha256)
        assertEquals(fingerprint.size, entity.captured.sourceByteSize)
        assertEquals(book.chapters.size, chapters.captured.size)
        assertEquals(TITLES, entries.captured.map { it.title })
        val indexes = entries.captured.map { requireNotNull(it.chapterIndex) }
        assertEquals(TITLES, indexes.map { chapters.captured[it].title })
        SENTENCES.forEachIndexed { i, sentences -> sentences.forEach { assertTrue(bodies.captured[indexes[i]].body.contains(it)) } }
        coVerify(exactly = 1) { library.markTextReady(31L) }
        assertTrue(File(cache, "document-import").listFiles().orEmpty().isEmpty())
        assertEquals(fingerprint, SourceFingerprints.of(file))
    }

    companion object {
        private val TITLES = listOf("第一章 起点", "第二章 中途", "第三章 终点")
        private val SENTENCES = listOf(
            listOf("晨光落在码头的石阶上。", "她把信折好，放进袖中。"),
            listOf("雨下了一整夜，江水漫过了堤岸。", "逝者如斯夫，不舍昼夜。"),
            listOf("船靠岸的时候，天已经亮了。")
        )
    }
}
