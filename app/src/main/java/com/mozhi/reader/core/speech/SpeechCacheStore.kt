package com.mozhi.reader.core.speech

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class SpeechCacheStats(
    val fileCount: Int = 0,
    val totalBytes: Long = 0,
    val budgetBytes: Long = SpeechCacheStore.DEFAULT_BUDGET_BYTES,
    val autoSyncOnWifi: Boolean = false,
    val lastSyncAt: Long = 0L
) {
    val usedFraction: Float
        get() = if (budgetBytes <= 0) 0f else (totalBytes.toFloat() / budgetBytes).coerceIn(0f, 1f)
}

data class SpeechCacheBookStats(
    val bookId: Long,
    val fileCount: Int,
    val totalBytes: Long
)

/**
 * AI 合成语音的本地缓存。
 *
 * 位置从 cacheDir 迁到 filesDir：系统随时可以清空 cacheDir，而这些文件每一个都对应一次
 * 真金白银的 API 调用，被清掉就要重新付费合成。淘汰改成全局容量预算而不是「每本书 60 个
 * 文件」——听一章长篇就能把 60 个名额用光，而 60 个文件到底占多少空间用户根本无从得知。
 */
@Singleton
class SpeechCacheStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: DataStore<Preferences>
) {
    fun directory(): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }

    fun directoryFor(bookId: Long): File = File(directory(), bookId.toString()).apply { mkdirs() }

    val settings: Flow<SpeechCacheStats> = dataStore.data.map { preferences ->
        SpeechCacheStats(
            budgetBytes = preferences[BUDGET] ?: DEFAULT_BUDGET_BYTES,
            autoSyncOnWifi = preferences[AUTO_SYNC] ?: false,
            lastSyncAt = preferences[LAST_SYNC] ?: 0L
        )
    }

    /** 统计要遍历整棵目录树，一律在 IO 线程做。 */
    suspend fun stats(): SpeechCacheStats = withContext(Dispatchers.IO) {
        val files = audioFiles()
        val stored = settings.first()
        stored.copy(fileCount = files.size, totalBytes = files.sumOf(File::length))
    }

    suspend fun budgetBytes(): Long = settings.first().budgetBytes

    suspend fun setBudgetBytes(value: Long) {
        dataStore.edit { it[BUDGET] = value.coerceIn(MIN_BUDGET_BYTES, MAX_BUDGET_BYTES) }
    }

    suspend fun setAutoSyncOnWifi(enabled: Boolean) {
        dataStore.edit { it[AUTO_SYNC] = enabled }
    }

    suspend fun markSynced(now: Long = System.currentTimeMillis()) {
        dataStore.edit { it[LAST_SYNC] = now }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        directory().deleteRecursively()
        directory()
        Unit
    }

    suspend fun statsByBook(): List<SpeechCacheBookStats> = withContext(Dispatchers.IO) {
        summarizeSpeechCache(directory())
    }

    suspend fun clearBook(bookId: Long): Long = withContext(Dispatchers.IO) {
        val target = File(directory(), bookId.toString())
        if (!target.isDirectory) return@withContext 0L
        val bytes = target.walkTopDown().filter(File::isFile).sumOf(File::length)
        target.deleteRecursively()
        bytes
    }

    fun audioFiles(): List<File> = directory()
        .walkTopDown()
        .filter { it.isFile && it.extension.lowercase() in AUDIO_EXTENSIONS }
        .toList()

    /**
     * 一次性把老的 cacheDir 缓存搬过来。缓存不搬也只是重新花钱合成，所以失败不声张，
     * 但能省一次就省一次。
     */
    suspend fun migrateLegacyCache() = withContext(Dispatchers.IO) {
        val legacy = File(context.cacheDir, LEGACY_DIRECTORY)
        if (!legacy.isDirectory) return@withContext
        runCatching {
            legacy.listFiles()?.forEach { bookDir ->
                if (!bookDir.isDirectory) return@forEach
                val target = File(directory(), bookDir.name).apply { mkdirs() }
                bookDir.listFiles()?.forEach { file ->
                    val destination = File(target, file.name)
                    if (!destination.exists()) file.copyTo(destination, overwrite = false)
                }
            }
            legacy.deleteRecursively()
        }
        Unit
    }

    /** 超出预算时按最久未用删起，返回删掉的字节数。 */
    suspend fun enforceBudget(): Long = withContext(Dispatchers.IO) {
        val budget = budgetBytes()
        val files = audioFiles().sortedByDescending(File::lastModified)
        var kept = 0L
        var removed = 0L
        files.forEach { file ->
            val length = file.length()
            if (kept + length <= budget) {
                kept += length
            } else {
                if (file.delete()) removed += length
            }
        }
        removed
    }

    companion object {
        const val DIRECTORY = "speech-cache"
        const val LEGACY_DIRECTORY = "agent-speech"
        const val DEFAULT_BUDGET_BYTES = 300L * 1024 * 1024
        const val MIN_BUDGET_BYTES = 50L * 1024 * 1024
        const val MAX_BUDGET_BYTES = 2L * 1024 * 1024 * 1024
        val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac")

        private val BUDGET = longPreferencesKey("speech_cache_budget_bytes")
        private val AUTO_SYNC = booleanPreferencesKey("speech_cache_auto_sync_wifi")
        private val LAST_SYNC = longPreferencesKey("speech_cache_last_sync_at")
    }
}

internal fun summarizeSpeechCache(root: File): List<SpeechCacheBookStats> = root.listFiles()
    .orEmpty()
    .asSequence()
    .filter(File::isDirectory)
    .mapNotNull { bookDirectory ->
        val bookId = bookDirectory.name.toLongOrNull() ?: return@mapNotNull null
        val files = bookDirectory.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in SpeechCacheStore.AUDIO_EXTENSIONS }
            .toList()
        SpeechCacheBookStats(bookId, files.size, files.sumOf(File::length))
    }
    .sortedByDescending(SpeechCacheBookStats::totalBytes)
    .toList()
