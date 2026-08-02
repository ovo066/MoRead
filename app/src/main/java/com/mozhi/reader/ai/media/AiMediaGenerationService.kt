package com.mozhi.reader.ai.media

import android.content.Context
import android.graphics.BitmapFactory
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.library.IllustrationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AgentMediaResult(
    val status: String = "ok",
    @SerialName("media_kind") val mediaKind: String,
    @SerialName("media_id") val mediaId: Long? = null,
    val path: String,
    @SerialName("media_type") val mediaType: String? = null,
    val message: String
) {
    fun encode(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }
        fun decode(value: String): AgentMediaResult? = runCatching {
            codec.decodeFromString(serializer(), value)
        }.getOrNull()?.takeIf { it.status == "ok" && it.path.isNotBlank() }
    }
}

data class CachedSpeech(val path: String, val mediaType: String?, val cacheHit: Boolean)

/** Agent 与划线菜单共用的媒体落盘服务。 */
@Singleton
class AiMediaGenerationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: AiClientFactory,
    private val illustrations: IllustrationRepository
) {
    private val speechMutex = Mutex()

    suspend fun generateIllustration(
        bookId: Long,
        chapterIndex: Int?,
        charOffset: Int?,
        sourceText: String,
        prompt: String,
        personaId: Long?
    ): IllustrationEntity {
        val cleanPrompt = prompt.trim().take(MAX_PROMPT_CHARS)
        require(cleanPrompt.isNotEmpty()) { "生图提示词不能为空" }
        val resolved = clientFactory.imageGeneration()
        val generated = resolved.client.generateImages(prompt = cleanPrompt).first()
        val bytes = resolved.client.materializeImage(generated)
        require(bytes.size <= MAX_MEDIA_BYTES) { "生成图片超过 30 MB，已取消保存" }
        return withContext(Dispatchers.IO) {
            val directory = File(context.filesDir, "illustrations/$bookId").apply { mkdirs() }
            val output = File(
                directory,
                "illustration-${System.currentTimeMillis()}-${System.nanoTime()}.${imageExtension(generated.mediaType, bytes)}"
            )
            output.writeBytes(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(output.absolutePath, bounds)
            try {
                illustrations.insert(
                    IllustrationEntity(
                        bookId = bookId,
                        chapterIndex = chapterIndex,
                        charOffset = charOffset,
                        sourceText = sourceText.trim().take(MAX_SOURCE_CHARS),
                        prompt = cleanPrompt,
                        imagePath = output.absolutePath,
                        mediaType = generated.mediaType,
                        pixelWidth = bounds.outWidth.coerceAtLeast(0),
                        pixelHeight = bounds.outHeight.coerceAtLeast(0),
                        createdByPersonaId = personaId,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } catch (error: Throwable) {
                output.delete()
                throw error
            }
        }
    }

    /** 同一模型、文本、音色和参数命中同一文件，不重复向厂商计费。 */
    suspend fun synthesizeSpeech(
        bookId: Long,
        text: String,
        voiceId: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        pitch: Int? = null,
        format: String? = null
    ): CachedSpeech {
        val cleanText = text.trim().take(MAX_SPEECH_CHARS)
        require(cleanText.isNotEmpty()) { "朗读文本不能为空" }
        val resolved = clientFactory.mediaForRole(ModelRole.TTS)
        val key = sha256(
            listOf(
                resolved.provider.id,
                resolved.provider.baseUrl,
                resolved.provider.extraJson,
                resolved.model.id,
                resolved.model.modelName,
                resolved.model.extraJson,
                cleanText,
                voiceId.orEmpty(),
                speed,
                volume,
                pitch,
                format.orEmpty()
            ).joinToString("\u0000")
        )
        return speechMutex.withLock {
            withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "agent-speech/$bookId").apply { mkdirs() }
                directory.listFiles()?.firstOrNull { it.nameWithoutExtension == key }?.let {
                    it.setLastModified(System.currentTimeMillis())
                    return@withContext CachedSpeech(it.absolutePath, mediaTypeForExtension(it.extension), true)
                }
                null
            } ?: run {
                val generated = resolved.client.synthesizeSpeech(
                    text = cleanText,
                    voice = voiceId,
                    responseFormat = format,
                    speed = speed,
                    volume = volume,
                    pitch = pitch
                )
                require(generated.bytes.size <= MAX_MEDIA_BYTES) { "生成语音超过 30 MB，已取消缓存" }
                withContext(Dispatchers.IO) {
                    val directory = File(context.cacheDir, "agent-speech/$bookId").apply { mkdirs() }
                    val output = File(directory, "$key.${audioExtension(generated.mediaType, format)}")
                    output.writeBytes(generated.bytes)
                    directory.listFiles()
                        ?.sortedByDescending(File::lastModified)
                        ?.drop(MAX_CACHED_SPEECH_FILES)
                        ?.forEach(File::delete)
                    CachedSpeech(output.absolutePath, generated.mediaType, false)
                }
            }
        }
    }

    private fun imageExtension(mediaType: String?, bytes: ByteArray): String = when {
        mediaType?.contains("png", true) == true -> "png"
        mediaType?.contains("webp", true) == true -> "webp"
        bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png"
        else -> "jpg"
    }

    private fun audioExtension(mediaType: String?, requested: String?): String = when {
        mediaType?.contains("wav", true) == true || requested == "wav" -> "wav"
        mediaType?.contains("flac", true) == true || requested == "flac" -> "flac"
        mediaType?.contains("aac", true) == true || requested == "aac" -> "aac"
        else -> "mp3"
    }

    private fun mediaTypeForExtension(extension: String): String = when (extension.lowercase()) {
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        else -> "audio/mpeg"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_PROMPT_CHARS = 8_000
        const val MAX_SOURCE_CHARS = 8_000
        const val MAX_SPEECH_CHARS = 8_000
        const val MAX_MEDIA_BYTES = 30 * 1024 * 1024
        const val MAX_CACHED_SPEECH_FILES = 60
    }
}
