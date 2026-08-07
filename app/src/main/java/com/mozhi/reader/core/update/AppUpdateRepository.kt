package com.mozhi.reader.core.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.mozhi.reader.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class UpdateRelease(
    val tag: String,
    val title: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
    val assetBytes: Long,
    val publishedAt: String
)

data class AppUpdateState(
    val checking: Boolean = false,
    val available: UpdateRelease? = null,
    val upToDate: Boolean = false,
    val downloading: Boolean = false,
    val downloadProgress: Float? = null,
    val downloadedApk: File? = null,
    val error: String? = null
)

@Singleton
class AppUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val httpClient: OkHttpClient,
    private val preferences: UpdatePreferencesStore
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()

    suspend fun check(force: Boolean = false) = mutex.withLock {
        if (!force && !preferences.shouldCheck()) return@withLock
        mutableState.value = mutableState.value.copy(checking = true, error = null, upToDate = false)
        try {
            val releases = withContext(Dispatchers.IO) { fetchReleases() }
            val latest = releases
                .filterNot { it.draft }
                .mapNotNull { it.toUpdateRelease() }
                .maxWithOrNull { a, b -> compareVersionNames(a.tag, b.tag) }
            val available = latest?.takeIf { compareVersionNames(it.tag, BuildConfig.VERSION_NAME) > 0 }
            mutableState.value = mutableState.value.copy(
                checking = false,
                available = available,
                upToDate = available == null,
                error = null
            )
            preferences.markChecked()
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                checking = false,
                error = error.message ?: "检查更新失败"
            )
        }
    }

    suspend fun download(release: UpdateRelease): File = mutex.withLock {
        val existing = mutableState.value.downloadedApk
            ?.takeIf { it.isFile && mutableState.value.available?.tag == release.tag }
        if (existing != null) return@withLock existing
        mutableState.value = mutableState.value.copy(
            downloading = true,
            downloadProgress = 0f,
            error = null
        )
        try {
            val output = withContext(Dispatchers.IO) { downloadApk(release) }
            mutableState.value = mutableState.value.copy(
                downloading = false,
                downloadProgress = 1f,
                downloadedApk = output
            )
            output
        } catch (error: Throwable) {
            File(context.cacheDir, "updates/${release.tag.sanitizeFileName()}.apk.part").delete()
            mutableState.value = mutableState.value.copy(
                downloading = false,
                downloadProgress = null,
                error = error.message ?: "下载更新失败"
            )
            throw error
        }
    }

    fun clearError() {
        mutableState.value = mutableState.value.copy(error = null)
    }

    private fun fetchReleases(): List<GitHubRelease> {
        val request = Request.Builder()
            .url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "MoRead/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub 返回 ${response.code}")
            return JSON.decodeFromString(
                ListSerializer(GitHubRelease.serializer()),
                response.body.string()
            )
        }
    }

    private fun downloadApk(release: UpdateRelease): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val partial = File(directory, "${release.tag.sanitizeFileName()}.apk.part")
        val output = File(directory, "${release.tag.sanitizeFileName()}.apk")
        val request = Request.Builder()
            .url(release.assetUrl)
            .header("User-Agent", "MoRead/${BuildConfig.VERSION_NAME}")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("下载服务器返回 ${response.code}")
            val body = response.body
            val total = body.contentLength().takeIf { it > 0 }
                ?: release.assetBytes.takeIf { it > 0 }
            partial.outputStream().buffered().use { sink ->
                body.byteStream().use { source ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        copied += read
                        mutableState.value = mutableState.value.copy(
                            downloadProgress = total?.let {
                                (copied.toFloat() / it).coerceIn(0f, 1f)
                            }
                        )
                    }
                }
            }
        }
        verifyDownloadedApk(partial)
        if (output.exists()) output.delete()
        check(partial.renameTo(output)) { "无法保存更新文件" }
        return output
    }

    private fun verifyDownloadedApk(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: error("下载的文件不是有效 APK")
        check(archive.packageName == context.packageName) { "更新包的应用标识不匹配" }
        check(PackageInfoCompat.getLongVersionCode(archive) > BuildConfig.VERSION_CODE.toLong()) {
            "更新包版本号不高于当前版本"
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        check(archive.signerDigests() == installed.signerDigests()) {
            "更新包签名与当前应用不一致，已阻止安装"
        }
    }

    private fun PackageInfo.signerDigests(): Set<String> {
        val certificateSignatures = if (Build.VERSION.SDK_INT >= 28) {
            signingInfo?.let { info ->
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            }.orEmpty()
        } else {
            @Suppress("DEPRECATION") signatures.orEmpty()
        }
        return certificateSignatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String = "",
        val name: String? = null,
        val body: String? = null,
        val draft: Boolean = false,
        @SerialName("published_at") val publishedAt: String? = null,
        val assets: List<GitHubAsset> = emptyList()
    ) {
        fun toUpdateRelease(): UpdateRelease? {
            val asset = assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true) &&
                    it.name.contains("mozhi", ignoreCase = true)
            } ?: assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
            if (tagName.isBlank() || asset.url.isBlank()) return null
            return UpdateRelease(
                tag = tagName,
                title = name?.takeIf(String::isNotBlank) ?: tagName,
                notes = body.orEmpty(),
                assetUrl = asset.url,
                assetName = asset.name,
                assetBytes = asset.size,
                publishedAt = publishedAt.orEmpty()
            )
        }
    }

    @Serializable
    private data class GitHubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val url: String = "",
        val size: Long = 0
    )

    private companion object {
        const val RELEASES_API = "https://api.github.com/repos/ovo066/MoRead/releases?per_page=10"
        val JSON = Json { ignoreUnknownKeys = true }
    }
}

/** 足够覆盖项目版本号（v0.10.0-beta4 / 1.2.0-rc.1）的 SemVer 比较。 */
internal fun compareVersionNames(left: String, right: String): Int {
    fun parse(value: String): Pair<List<Int>, List<String>?> {
        val clean = value.trim().removePrefix("v").removePrefix("V")
        val main = clean.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pre = clean.substringAfter('-', "").takeIf(String::isNotBlank)?.split('.', '-')
        return main to pre
    }
    val (leftMain, leftPre) = parse(left)
    val (rightMain, rightPre) = parse(right)
    repeat(maxOf(leftMain.size, rightMain.size)) { index ->
        val compared = leftMain.getOrElse(index) { 0 }.compareTo(rightMain.getOrElse(index) { 0 })
        if (compared != 0) return compared
    }
    if (leftPre == null && rightPre != null) return 1
    if (leftPre != null && rightPre == null) return -1
    if (leftPre == null) return 0
    repeat(maxOf(leftPre.size, rightPre!!.size)) { index ->
        val a = leftPre.getOrNull(index) ?: return -1
        val b = rightPre.getOrNull(index) ?: return 1
        val aNumber = a.toIntOrNull()
        val bNumber = b.toIntOrNull()
        val compared = when {
            aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
            aNumber != null -> -1
            bNumber != null -> 1
            else -> a.compareTo(b, ignoreCase = true)
        }
        if (compared != 0) return compared
    }
    return 0
}

private fun String.sanitizeFileName(): String = replace(Regex("[^A-Za-z0-9._-]"), "_")
