package com.mozhi.reader.feature.importer

import com.mozhi.reader.core.library.EpubLayoutDiagnostic
import com.mozhi.reader.core.library.EpubLayoutDiagnosticSeverity
import com.mozhi.reader.core.library.EpubLayoutPackage
import com.mozhi.reader.core.library.EpubLayoutResource
import com.mozhi.reader.core.library.EpubLayoutResourceKind
import com.mozhi.reader.core.library.EpubLayoutSpineItem
import com.mozhi.reader.core.library.EpubResourcePath
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

@Singleton
class EpubPackageInspector @Inject constructor() {
    fun inspect(epubFile: File): EpubLayoutPackage {
        require(epubFile.isFile) { "EPUB 文件不存在" }
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
            require(entries.size <= MAX_ARCHIVE_ENTRIES) { "EPUB 包含过多资源" }
            val entriesByPath = entries.associateBy { normalizeArchivePath(it.name).lowercase() }
            val diagnostics = ArrayList<EpubLayoutDiagnostic>()
            val packagePath = readPackagePath(zip, entriesByPath)
            val packageEntry = entriesByPath[packagePath.lowercase()]
                ?: error("EPUB 缺少包文档：$packagePath")
            val packageDocument = Jsoup.parse(
                readText(zip, packageEntry, MAX_PACKAGE_BYTES),
                "",
                Parser.xmlParser()
            )
            val packageElement = packageDocument.selectFirst("package")
            val manifestItems = packageDocument.select("manifest > item")
            val resourceById = LinkedHashMap<String, EpubLayoutResource>()
            var totalHashedBytes = 0L
            manifestItems.forEach { item ->
                val id = item.attr("id").trim()
                val rawHref = item.attr("href")
                val href = EpubResourcePath.normalize(rawHref, packagePath)
                if (id.isEmpty() || href == null) {
                    diagnostics += warning("invalid-manifest-item", "资源路径无效", rawHref)
                    return@forEach
                }
                val mediaType = item.attr("media-type").trim().lowercase()
                val kind = resourceKind(mediaType, href, item.attr("properties"))
                val archiveEntry = entriesByPath[href.lowercase()]
                if (archiveEntry == null) {
                    diagnostics += warning("missing-resource", "清单资源在 EPUB 中不存在", href)
                }
                val size = archiveEntry?.size?.coerceAtLeast(0L) ?: 0L
                val shouldHash = archiveEntry != null && kind != EpubLayoutResourceKind.OTHER &&
                    size <= MAX_RESOURCE_BYTES && totalHashedBytes + size <= MAX_HASHED_BYTES
                val digest = if (shouldHash) {
                    totalHashedBytes += size
                    zip.getInputStream(archiveEntry).use(::sha256)
                } else {
                    if (archiveEntry != null && kind != EpubLayoutResourceKind.OTHER) {
                        diagnostics += warning("resource-too-large", "资源过大，未建立内容哈希", href)
                    }
                    null
                }
                resourceById[id] = EpubLayoutResource(
                    id = id,
                    href = href,
                    archivePath = archiveEntry?.let { normalizeArchivePath(it.name) } ?: href,
                    mediaType = mediaType,
                    properties = item.attr("properties").splitWhitespace(),
                    kind = kind,
                    sizeBytes = size,
                    sha256 = digest
                )
            }
            val spine = packageDocument.select("spine > itemref").mapIndexed { index, item ->
                val idref = item.attr("idref").trim()
                val resource = resourceById[idref]
                if (resource == null) {
                    diagnostics += warning("missing-spine-resource", "spine 引用了未知资源", idref)
                }
                EpubLayoutSpineItem(
                    index = index,
                    idref = idref,
                    href = resource?.href,
                    linear = !item.attr("linear").equals("no", true),
                    properties = item.attr("properties").splitWhitespace()
                )
            }
            val stylesheets = resourceById.values
                .filter { it.kind == EpubLayoutResourceKind.STYLESHEET }
                .mapNotNull { resource ->
                    entriesByPath[resource.archivePath.lowercase()]?.let { entry ->
                        EpubStylesheetSource(
                            href = resource.href,
                            css = readText(zip, entry, MAX_STYLESHEET_BYTES)
                        )
                    }
                }
            val css = EpubCssStylesheetSet.parse(stylesheets)
            css.unsupportedProperties.sorted().forEach { property ->
                diagnostics += EpubLayoutDiagnostic(
                    severity = EpubLayoutDiagnosticSeverity.INFO,
                    code = "unsupported-css-property",
                    message = property
                )
            }
            return EpubLayoutPackage(
                packageDocumentPath = packagePath,
                epubVersion = packageElement?.attr("version")?.takeIf(String::isNotBlank),
                uniqueIdentifier = packageElement
                    ?.attr("unique-identifier")
                    ?.takeIf(String::isNotBlank)
                    ?.let { id -> packageDocument.getElementById(id)?.text()?.trim() }
                    ?.takeIf(String::isNotEmpty),
                resources = resourceById.values.toList(),
                spine = spine,
                fontFaces = css.fontFaces,
                diagnostics = diagnostics
            )
        }
    }

    fun readStylesheets(epubFile: File, layoutPackage: EpubLayoutPackage): Map<String, String> =
        ZipFile(epubFile).use { zip ->
            val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory)
                .associateBy { normalizeArchivePath(it.name).lowercase() }
            buildMap {
                layoutPackage.resources
                    .asSequence()
                    .filter { it.kind == EpubLayoutResourceKind.STYLESHEET }
                    .forEach resourceLoop@ { resource ->
                        val entry = entries[resource.archivePath.lowercase()] ?: return@resourceLoop
                        val css = readText(zip, entry, MAX_STYLESHEET_BYTES)
                        EpubResourcePath.packageAliases(resource.href, layoutPackage.packageDocumentPath)
                            .forEach { alias -> putIfAbsent(alias, css) }
                        EpubResourcePath.packageAliases(resource.archivePath, layoutPackage.packageDocumentPath)
                            .forEach { alias -> putIfAbsent(alias, css) }
                    }
            }
        }

    private fun readPackagePath(zip: ZipFile, entries: Map<String, ZipEntry>): String {
        val container = entries[CONTAINER_PATH.lowercase()] ?: error("EPUB 缺少 container.xml")
        val document = Jsoup.parse(readText(zip, container, MAX_CONTAINER_BYTES), "", Parser.xmlParser())
        val rawPath = document.selectFirst("rootfile[full-path]")?.attr("full-path")
            ?: error("EPUB container.xml 缺少 rootfile")
        return EpubResourcePath.normalize(rawPath) ?: error("EPUB 包文档路径无效")
    }

    private fun readText(zip: ZipFile, entry: ZipEntry, maxBytes: Int): String =
        zip.getInputStream(entry).use { input -> readLimited(input, maxBytes).toString(Charsets.UTF_8) }

    private fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "EPUB 资源超过解析上限" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun resourceKind(mediaType: String, href: String, properties: String): EpubLayoutResourceKind =
        when {
            mediaType == "application/xhtml+xml" -> EpubLayoutResourceKind.DOCUMENT
            mediaType == "text/css" -> EpubLayoutResourceKind.STYLESHEET
            mediaType == "image/svg+xml" || href.endsWith(".svg", true) -> EpubLayoutResourceKind.SVG
            mediaType.startsWith("image/") -> EpubLayoutResourceKind.IMAGE
            mediaType.startsWith("font/") || mediaType in FONT_MEDIA_TYPES ||
                FONT_EXTENSIONS.any { href.endsWith(it, true) } -> EpubLayoutResourceKind.FONT
            mediaType == "application/x-dtbncx+xml" ||
                properties.splitWhitespace().any { it == "nav" } -> EpubLayoutResourceKind.NAVIGATION
            else -> EpubLayoutResourceKind.OTHER
        }

    private fun warning(code: String, message: String, href: String?) = EpubLayoutDiagnostic(
        severity = EpubLayoutDiagnosticSeverity.WARNING,
        code = code,
        message = message,
        href = href
    )

    private fun normalizeArchivePath(path: String): String = path.replace('\\', '/').removePrefix("./")

    private fun String.splitWhitespace(): List<String> = trim()
        .split(Regex("\\s+"))
        .filter(String::isNotEmpty)

    private companion object {
        const val CONTAINER_PATH = "META-INF/container.xml"
        const val MAX_ARCHIVE_ENTRIES = 20_000
        const val MAX_CONTAINER_BYTES = 1024 * 1024
        const val MAX_PACKAGE_BYTES = 8 * 1024 * 1024
        const val MAX_STYLESHEET_BYTES = 8 * 1024 * 1024
        const val MAX_RESOURCE_BYTES = 64L * 1024 * 1024
        const val MAX_HASHED_BYTES = 1024L * 1024 * 1024
        val FONT_MEDIA_TYPES = setOf(
            "application/font-sfnt",
            "application/font-woff",
            "application/vnd.ms-opentype",
            "application/x-font-opentype",
            "application/x-font-truetype"
        )
        val FONT_EXTENSIONS = setOf(".ttf", ".otf", ".woff", ".woff2")
    }
}
