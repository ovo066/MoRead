package com.mozhi.reader.core.backup

import java.io.File
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
class WebDavClient @Inject constructor(private val httpClient: OkHttpClient) {
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

    suspend fun upload(credentials: WebDavCredentials, file: File, remoteName: String) =
        withContext(Dispatchers.IO) {
            require(file.isFile) { "本地备份文件不存在" }
            val target = ensureDirectory(credentials).newBuilder().addPathSegment(remoteName).build()
            val request = Request.Builder()
                .url(target)
                .put(file.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            execute(credentials, request).close()
        }

    suspend fun download(credentials: WebDavCredentials, remoteName: String, output: File) =
        withContext(Dispatchers.IO) {
            val target = ensureDirectory(credentials).newBuilder().addPathSegment(remoteName).build()
            execute(credentials, Request.Builder().url(target).get().build()).use { response ->
                output.parentFile?.mkdirs()
                output.outputStream().buffered().use { sink -> response.body.byteStream().copyTo(sink) }
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
            val exists = httpClient.newCall(authenticated(credentials, propFind(current, 0))).execute().use {
                when {
                    it.isSuccessful -> true
                    it.code == 404 -> false
                    else -> throw WebDavException("WebDAV 返回 ${it.code} ${it.message}")
                }
            }
            if (!exists) {
                val request = Request.Builder().url(current).method("MKCOL", null).build()
                httpClient.newCall(authenticated(credentials, request)).execute().use { response ->
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
        val response = httpClient.newCall(authenticated(credentials, request)).execute()
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

    companion object {
        const val BACKUP_EXTENSION = ".moread.zip"
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/>
            <d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>"""
    }
}

class WebDavException(message: String) : Exception(message)
