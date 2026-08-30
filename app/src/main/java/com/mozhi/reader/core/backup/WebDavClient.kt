package com.mozhi.reader.core.backup

import com.mozhi.reader.core.diag.SkipApiCallLogging
import java.io.File
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

data class RemoteBackup(
    val name: String,
    val size: Long,
    val modifiedAt: Long
)

/** OkHttp WebDAV 子集，沿用 Legado 的 PROPFIND/MKCOL/PUT/GET 方案。 */
@Singleton
class WebDavClient @Inject constructor(httpClient: OkHttpClient) {
    /**
     * 备份传输独立放宽超时。newBuilder 仍复用连接池和线程池；所有请求再用 tag
     * 绕过 API 诊断快照，避免把数百 MB 的 zip 写进内存。
     */
    private val transferClient = httpClient.newBuilder()
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    suspend fun test(credentials: WebDavCredentials) = withContext(Dispatchers.IO) {
        val root = credentials.baseHttpUrl()
        val response = execute(credentials, propFind(root, depth = 0))
        response.close()
        ensureDirectory(credentials)
    }

    /**
     * 列目录。[suffixes] 用来滤掉目录项与无关文件——PROPFIND 会把集合自身也列进来，
     * 不按扩展名筛就会把目录当成一个「备份」。
     */
    suspend fun list(
        credentials: WebDavCredentials,
        suffixes: Set<String> = setOf(BACKUP_EXTENSION)
    ): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val directory = ensureDirectory(credentials)
        execute(credentials, propFind(directory, depth = 1)).use { response ->
            val document = Jsoup.parse(response.body.string(), Parser.xmlParser())
            document.allElements
                .filter { it.localTag() == "response" }
                .mapNotNull { element -> element.toRemoteBackup() }
                .filter { file -> suffixes.any { file.name.endsWith(it, ignoreCase = true) } }
                .sortedByDescending(RemoteBackup::modifiedAt)
        }
    }

    suspend fun upload(
        credentials: WebDavCredentials,
        file: File,
        remoteName: String,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        require(file.isFile) { "本地备份文件不存在" }
        val target = ensureDirectory(credentials).newBuilder().addPathSegment(remoteName).build()
        val request = Request.Builder()
            .url(target)
            .put(ProgressFileRequestBody(file, onProgress))
            .build()
        execute(credentials, request).close()
    }

    suspend fun download(
        credentials: WebDavCredentials,
        remoteName: String,
        output: File,
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        val target = ensureDirectory(credentials).newBuilder().addPathSegment(remoteName).build()
        execute(credentials, Request.Builder().url(target).get().build()).use { response ->
            output.parentFile?.mkdirs()
            val total = response.body.contentLength()
            var received = 0L
            response.body.byteStream().buffered().use { source ->
                output.outputStream().buffered().use { sink ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        received += count
                        onProgress(received, total)
                    }
                }
            }
        }
    }

    suspend fun delete(credentials: WebDavCredentials, remoteName: String) =
        withContext(Dispatchers.IO) {
            val target = ensureDirectory(credentials).newBuilder().addPathSegment(remoteName).build()
            execute(
                credentials,
                Request.Builder().url(target).method("DELETE", null).build()
            ).close()
        }

    private fun ensureDirectory(credentials: WebDavCredentials): HttpUrl {
        var current = credentials.baseHttpUrl()
        credentials.remoteDirectory.split('/').map(String::trim).filter(String::isNotEmpty).forEach { segment ->
            current = current.newBuilder().addPathSegment(segment).addPathSegment("").build()
            val exists = transferClient.newCall(authenticated(credentials, propFind(current, 0))).execute().use {
                when {
                    it.isSuccessful -> true
                    it.code == 404 -> false
                    else -> throw WebDavException("WebDAV 返回 ${it.code} ${it.message}")
                }
            }
            if (!exists) {
                val request = Request.Builder().url(current).method("MKCOL", null).build()
                transferClient.newCall(authenticated(credentials, request)).execute().use { response ->
                    if (!response.isSuccessful && response.code != 405) {
                        throw WebDavException("创建远程目录失败：${response.code} ${response.message}")
                    }
                }
            }
        }
        return current
    }

    private fun WebDavCredentials.baseHttpUrl(): HttpUrl {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: throw WebDavException("WebDAV 地址无效")
        if (url.scheme != "https") throw WebDavException("为保护账号密码，WebDAV 地址必须使用 HTTPS")
        return if (url.pathSegments.lastOrNull().isNullOrEmpty()) url
        else url.newBuilder().addPathSegment("").build()
    }

    private fun propFind(url: HttpUrl, depth: Int): Request = Request.Builder()
        .url(url)
        .header("Depth", depth.toString())
        .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml".toMediaType()))
        .build()

    private fun execute(credentials: WebDavCredentials, request: Request): okhttp3.Response {
        val response = transferClient.newCall(authenticated(credentials, request)).execute()
        if (!response.isSuccessful) {
            val code = response.code
            val message = response.message
            response.close()
            throw WebDavException(
                if (code == 401 || code == 403) "WebDAV 账号或密码错误" else "WebDAV 返回 $code $message"
            )
        }
        return response
    }

    private fun authenticated(credentials: WebDavCredentials, request: Request): Request =
        request.newBuilder()
            .header("Authorization", Credentials.basic(credentials.username, credentials.password))
            .header("User-Agent", "MoRead-WebDAV")
            .tag(SkipApiCallLogging::class.java, SkipApiCallLogging)
            .build()

    private fun Element.toRemoteBackup(): RemoteBackup? {
        val href = getAllElements().firstOrNull { it.localTag() == "href" }?.text().orEmpty()
        val display = getAllElements().firstOrNull { it.localTag() == "displayname" }
            ?.text()?.takeIf(String::isNotBlank)
        val name = display ?: href.trimEnd('/').substringAfterLast('/')
            .let { URLDecoder.decode(it, Charsets.UTF_8.name()) }
        if (name.isBlank()) return null
        val size = getAllElements().firstOrNull { it.localTag() == "getcontentlength" }
            ?.text()?.toLongOrNull() ?: 0L
        val modified = getAllElements().firstOrNull { it.localTag() == "getlastmodified" }
            ?.text()?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
            ?: 0L
        return RemoteBackup(name, size, modified)
    }

    private fun Element.localTag(): String = tagName().substringAfter(':').lowercase()

    private class ProgressFileRequestBody(
        private val file: File,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = file.length()
        override fun isOneShot(): Boolean = true

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.inputStream().buffered().use { source ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, 0, count)
                    sent += count
                    onProgress(sent, total)
                }
            }
        }
    }

    companion object {
        const val BACKUP_EXTENSION = ".moread.zip"
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/>
            <d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
    }
}

class WebDavException(message: String) : Exception(message)
