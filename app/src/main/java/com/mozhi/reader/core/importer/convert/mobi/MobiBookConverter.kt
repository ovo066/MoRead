package com.mozhi.reader.core.importer.convert.mobi

import com.mozhi.reader.core.importer.convert.BookConversionException
import com.mozhi.reader.core.importer.convert.BookConversionException.Reason
import com.mozhi.reader.core.importer.convert.BookDocumentInput
import com.mozhi.reader.core.importer.convert.BookHtmlSanitizer
import com.mozhi.reader.core.importer.convert.ConvertedBook
import com.mozhi.reader.core.importer.convert.ConvertedChapter
import com.mozhi.reader.core.importer.convert.ConvertedResource
import com.mozhi.reader.core.importer.convert.HtmlBookConverter
import com.mozhi.reader.core.importer.convert.documentTitle
import com.mozhi.reader.core.importer.convert.xmlCharacters
import com.mozhi.reader.core.importer.convert.xmlOutput
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** 只读取本地暂存文件，生成与 Markdown / HTML 相同的中间模型。 */
class MobiBookConverter {
    fun convert(source: File, displayName: String = source.name, checkActive: () -> Unit = {}): ConvertedBook {
        try {
            return PalmDatabase(source).use { pdb ->
                checkActive()
                val old = MobiHeader(0, pdb.record(0, MAX_HEADER_BYTES))
                val boundary = old.integer(121)
                val header = if (old.version < 8 && boundary != null) {
                    var base = pdb.absolute(0, boundary)
                    // 两种制作者写法都存在：指向 BOUNDARY 本身，或直接指向后面的 record 0。
                    if (pdb.prefix(base, 8).magic(0, "BOUNDARY")) base = pdb.absolute(base, 1)
                    MobiHeader(base, pdb.record(base, MAX_HEADER_BYTES)).also {
                        demand(it.version == 8, "合订本边界未指向 KF8")
                    }
                } else old
                val title = documentTitle(header.strings(503).firstOrNull() ?: header.fullName.ifBlank {
                    displayName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
                }, 200).ifBlank { "Untitled" }
                val author = documentTitle(header.strings(100).joinToString("、"), 1000)
                val text = readText(pdb, header, checkActive)
                val kf8 = if (header.version == 8) Kf8Reconstructor(pdb, header, checkActive).reconstruct(text) else null
                // 合订本的图片通常只存一份，放在前半（旧式）那一段里：KF8 正文里的序号要从旧式头的第一张图数起。
                // 先按这个位置找；那里不是图片时，再按「KF8 自带资源」的位置找。
                val sections = listOfNotNull(
                    old.firstResource?.takeIf { header !== old }?.let { 0 to it },
                    header.firstResource?.let { header.base to it }
                )
                val resources = ResourceReader(pdb, sections, kf8?.flows.orEmpty(), checkActive)
                val sanitizer = BookHtmlSanitizer(resources::resolve, dropFontFaces = true)
                val css = mutableListOf<String>()
                val parts = kf8?.parts ?: oldParts(pdb, header, text, checkActive)
                val language = header.language()
                val chapters = if (parts == null) {
                    // 没有字节目录和分页标记时仍由既有 h1/h2 分章规则决定边界。
                    val doc = prepare(decode(text, header))
                    val converted = HtmlBookConverter().convertDocument(doc, displayName, title, author, language, sanitizer)
                    converted.resources.filter { it.mediaType == "text/css" }.forEach { css += it.bytes.toString(Charsets.UTF_8) }
                    converted.chapters
                } else parts.map { part ->
                    checkActive()
                    val doc = prepare(decode(part.bytes, header))
                    css += sanitizer.clean(doc)
                    val heading = doc.body().selectFirst("h1,h2")?.text()
                    val name = documentTitle(part.title ?: heading ?: title).ifBlank { title }
                    // 各部分可以有不同 body 类名，把安全属性留在该部分的容器上。
                    val body = doc.body()
                    if (body.attributes().size() > 0) {
                        val wrapper = body.appendElement("div")
                        body.attributes().asList().forEach { wrapper.attr(it.key, it.value) }
                        body.childNodes().toList().filter { it !== wrapper }.forEach(wrapper::appendChild)
                    }
                    xmlOutput(doc)
                    ConvertedChapter(name, body.html(), part.inToc)
                }
                kf8?.flows?.drop(1)?.forEach { flow ->
                    checkActive()
                    if (imageType(flow) != "image/svg+xml") css += sanitizer.cleanCss(decode(flow, header))
                }
                val cover = header.integer(201)?.let { offset -> resources.image(offset) }?.let {
                    sanitizer.registerImage(it.bytes, it.mediaType)
                }
                if (chapters.none { Jsoup.parse(it.xhtmlBody).body().let { body -> body.hasText() || body.select("img").isNotEmpty() } }) {
                    throw BookConversionException(Reason.EMPTY_DOCUMENT)
                }
                val allResources = sanitizer.resources.values.toMutableList()
                val styles = css.filter(String::isNotBlank).distinct().joinToString("\n")
                if (styles.isNotBlank()) allResources += ConvertedResource("document.css", "text/css", styles.toByteArray())
                ConvertedBook(title, author, language,
                    if (chapters.none { it.inToc }) chapters.map { it.copy(title = title, inToc = true) } else chapters,
                    allResources, coverHref = cover)
            }
        } catch (error: IOException) {
            // I/O 异常可能含设备路径；二进制边界只暴露固定结构原因，不夹带正文。
            throw BookConversionException(Reason.CORRUPT_FILE, "文件读取失败或记录截断")
        }
    }

    private fun readText(pdb: PalmDatabase, header: MobiHeader, checkActive: () -> Unit): ByteArray {
        if (header.compression !in setOf(1, 2)) unsupported(
            if (header.compression == 17480) "暂不支持 HUFF/CDIC 压缩" else "未知 PalmDOC 压缩方式")
        pdb.absolute(header.base, header.textRecords)
        val output = ByteArrayOutputStream()
        repeat(header.textRecords) { i ->
            checkActive()
            val bytes = MobiBinary.stripTrailing(pdb.record(pdb.absolute(header.base, i + 1)), header.extraFlags)
            val decoded = if (header.compression == 2) MobiBinary.palmDoc(bytes, MobiBinary.MAX_TEXT_BYTES - output.size()) else bytes
            if (decoded.size > MobiBinary.MAX_TEXT_BYTES - output.size()) throw BookConversionException(Reason.TEXT_TOO_LARGE)
            output.write(decoded)
        }
        val bytes = output.toByteArray()
        demand(bytes.size >= header.textLength, "解压正文短于声明长度")
        demand(bytes.size - header.textLength <= 4096 && (header.textLength until bytes.size).all { bytes[it] == 0.toByte() },
            "解压正文长于声明长度且不是填充")
        return if (bytes.size == header.textLength) bytes else bytes.copyOf(header.textLength)
    }

    private fun oldParts(pdb: PalmDatabase, header: MobiHeader, text: ByteArray, checkActive: () -> Unit): List<MobiPart>? {
        header.ncx?.let { relative ->
            val ncx = MobiIndexReader(pdb, header.base, checkActive).read(relative)
            if (ncx.entries.isNotEmpty()) {
                demand(ncx.entries.size <= 10_000, "NCX 章节数过多")
                val positions = ncx.entries.map { row ->
                    val start = row.values(1, 1)[0]
                    val length = row.values(2, 1)[0]
                    range(start.toLong(), length.toLong(), text.size, "NCX 正文范围")
                    demand(start < text.size, "NCX 起点位于正文之外")
                    row.values(4, 1) // 层级由 TAGX 解码；最小 EPUB 的 nav 使用平面目录。
                    start to ncx.title(row)
                }.distinctBy { it.first }.sortedBy { it.first }
                val parts = mutableListOf<MobiPart>()
                if (positions.first().first > 0) {
                    val prelude = text.copyOfRange(0, positions.first().first)
                    val body = Jsoup.parse(decode(prelude, header)).body()
                    // HTML 头本身不构成阅读部分；有正文或封面时才保留目录之前的内容。
                    if (body.hasText() || body.select("img").isNotEmpty()) parts += MobiPart(prelude, null, false)
                }
                positions.forEachIndexed { i, (start, title) ->
                    val end = positions.getOrNull(i + 1)?.first ?: text.size
                    parts += MobiPart(text.copyOfRange(start, end), title, true)
                }
                return parts
            }
        }
        // ISO-8859-1 一字节一字符，仅用于寻找 ASCII 标记，正文仍按原编码逐段解码。
        val boundaries = PAGE_BREAK.findAll(text.toString(Charsets.ISO_8859_1)).take(10_001).toList()
        if (boundaries.isEmpty()) return null
        demand(boundaries.size <= 10_000, "分页标记过多")
        val parts = mutableListOf<MobiPart>()
        var start = 0
        boundaries.forEach { match ->
            if (match.range.first > start) parts += MobiPart(text.copyOfRange(start, match.range.first), null, true)
            start = match.range.last + 1
        }
        if (start < text.size) parts += MobiPart(text.copyOfRange(start, text.size), null, true)
        return parts.filter { Jsoup.parse(decode(it.bytes, header)).body().let { body -> body.hasText() || body.select("img").isNotEmpty() } }
    }

    private fun prepare(html: String): Document {
        val doc = Jsoup.parse(xmlCharacters(html))
        doc.select("a").toList().filter { it.hasAttr("filepos") || it.attr("href").startsWith("kindle:pos:", true) }.forEach { it.unwrap() }
        doc.getAllElements().toList().filter { it.normalName().startsWith("mbp:") }.forEach { it.unwrap() }
        doc.select("img[recindex]").forEach { image ->
            val index = image.attr("recindex").toIntOrNull() ?: corrupt("图片 recindex 无效")
            demand(index > 0, "图片 recindex 必须从 1 起算")
            image.attr("src", "mobi:resource:${index - 1}").removeAttr("recindex")
        }
        return doc
    }

    private fun decode(bytes: ByteArray, header: MobiHeader): String = try {
        header.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { corrupt("正文字节编码无效或章节边界切断字符") }

    /** [sections]：资源记录可能所在的几处，每处是（记录号基准，第一条资源的相对记录号），按优先顺序。 */
    private class ResourceReader(
        val pdb: PalmDatabase, val sections: List<Pair<Int, Int>>, val flows: List<ByteArray>, val checkActive: () -> Unit
    ) {
        private val cache = mutableMapOf<Int, ConvertedResource?>()
        private var total = 0L

        fun resolve(uri: String): ConvertedResource? {
            val embed = EMBED.matchEntire(uri.trim())
            if (embed != null) return image(MobiBinary.embedIndex(embed.groupValues[1]))
            if (uri.startsWith("mobi:resource:")) {
                return image(uri.substringAfterLast(':').toIntOrNull() ?: corrupt("图片记录号无效"))
            }
            val flow = FLOW.matchEntire(uri.trim()) ?: return null
            val index = MobiBinary.base32(flow.groupValues[1])
            demand(index in flows.indices && index > 0, "Kindle flow 引用越界")
            val bytes = flows[index]
            return if (imageType(bytes) == "image/svg+xml") ConvertedResource("", "image/svg+xml", bytes) else null
        }

        fun image(offset: Int): ConvertedResource? {
            if (cache.containsKey(offset)) return cache[offset]
            checkActive()
            demand(offset >= 0, "图片记录号无效")
            // 越界的候选位置直接略过：它只说明图片不在这一处，不代表文件损坏。
            val found = sections.firstNotNullOfOrNull { (base, first) ->
                val index = base.toLong() + first + offset
                if (index !in 0 until pdb.count.toLong()) null
                else imageType(pdb.prefix(index.toInt(), 1024))?.let { index.toInt() to it }
            }
            val index = found?.first ?: 0
            val type = found?.second
            val resource = if (type == null) null else {
                if (pdb.length(index) > BookDocumentInput.MAX_IMAGE_BYTES) throw BookConversionException(Reason.IMAGE_TOO_LARGE)
                total += pdb.length(index)
                demand(total <= MAX_SOURCE_BYTES, "图片资源总量过大")
                ConvertedResource("", type, pdb.record(index, BookDocumentInput.MAX_IMAGE_BYTES))
            }
            cache[offset] = resource
            return resource
        }
    }

    companion object {
        const val MAX_SOURCE_BYTES = 256 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 1024 * 1024
        private val EMBED = Regex("kindle:embed:([0-9a-v]+)(?:\\?[^\\s]*)?", RegexOption.IGNORE_CASE)
        private val FLOW = Regex("kindle:flow:([0-9a-v]+)(?:\\?[^\\s]*)?", RegexOption.IGNORE_CASE)
        private val PAGE_BREAK = Regex("<mbp:pagebreak\\b[^>]*>", RegexOption.IGNORE_CASE)

        internal fun imageType(bytes: ByteArray): String? {
            fun starts(vararg values: Int) = bytes.size >= values.size && values.indices.all { bytes.u8(it) == values[it] }
            return when {
                starts(0xff, 0xd8, 0xff) -> "image/jpeg"
                starts(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a) -> "image/png"
                bytes.magic(0, "GIF87a") || bytes.magic(0, "GIF89a") -> "image/gif"
                bytes.magic(0, "BM") -> "image/bmp"
                bytes.magic(0, "RIFF") && bytes.magic(8, "WEBP") -> "image/webp"
                Regex("^(?:\\s|\\uFEFF)*(?:<\\?xml[^>]*>\\s*)?(?:<!--.*?-->\\s*)*<svg(?:\\s|/?>)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .containsMatchIn(bytes.copyOfRange(0, minOf(1024, bytes.size)).toString(Charsets.UTF_8)) -> "image/svg+xml"
                else -> null
            }
        }
    }
}
