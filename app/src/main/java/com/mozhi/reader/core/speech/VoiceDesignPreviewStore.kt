package com.mozhi.reader.core.speech

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reusable generated previews are a cache, never a path supplied by a model or imported JSON. */
@Singleton
class VoiceDesignPreviewStore @Inject constructor(@ApplicationContext private val context: Context) {
    private fun file(id: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "voice-design-previews"), "$name.wav")
    }
    suspend fun save(id: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= 30 * 1024 * 1024)
        file(id).also { it.parentFile?.mkdirs(); it.writeBytes(bytes) }.absolutePath
    }
    fun find(id: String): String? = file(id).takeIf { it.isFile && it.length() > 44 }?.absolutePath
    suspend fun remove(id: String) = withContext(Dispatchers.IO) { file(id).delete(); Unit }
}
