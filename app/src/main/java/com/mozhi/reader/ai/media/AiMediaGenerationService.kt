package com.mozhi.reader.ai.media

import android.content.Context
import android.graphics.BitmapFactory
import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.entity.IllustrationEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.di.ApplicationScope
import com.mozhi.reader.core.library.IllustrationRepository
import com.mozhi.reader.core.speech.SpeechCacheStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

internal object SpeechCacheKey {
    fun build(
        providerId: Long,
        providerBaseUrl: String,
        providerExtraJson: String,
        modelId: Long,
        modelName: String,
        modelExtraJson: String,
        text: String,
        voiceId: String?,
        speed: Float?,
        volume: Float?,
        pitch: Int?,
        format: String?,
        emotion: String?,
        instruction: String?
    ): String = MessageDigest.getInstance("SHA-256")
        .digest(
            listOf(
                providerId,
                providerBaseUrl,
                providerExtraJson,
                modelId,
                modelName,
                modelExtraJson,
                text,
                voiceId.orEmpty(),
                speed,
                volume,
                pitch,
                format.orEmpty(),
                emotion.orEmpty(),
                instruction.orEmpty()
            ).joinToString("\u0000").toByteArray()
        )
        .joinToString("") { "%02x".format(it) }
}

/** Agent 与划线菜单共用的媒体落盘服务。 */
@Singleton
class AiMediaGenerationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val clientFactory: AiClientFactory,
    private val imagePromptComposer: ImagePromptComposer,
    private val illustrations: IllustrationRepository,
    private val speechCache: SpeechCacheStore,
    @ApplicationScope applicationScope: CoroutineScope
) {
    private val sharedSpeechGenerations =
        SharedGenerationRegistry<String, CachedSpeech>(applicationScope)

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
        val generatedPrompt = imagePromptComposer.compose(cleanPrompt).take(MAX_PROMPT_CHARS)
        val resolved = clientFactory.imageGeneration()
        val generated = resolved.client.generateImages(prompt = generatedPrompt).first()
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
                        prompt = generatedPrompt,
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
        format: String? = null,
        emotion: String? = null,
        instruction: String? = null
    ): CachedSpeech {
        val cleanText = text.trim().take(MAX_SPEECH_CHARS)
        require(cleanText.isNotEmpty()) { "朗读文本不能为空" }
        val resolved = clientFactory.mediaForRole(ModelRole.TTS)
        val key = speechCacheKey(
            resolved = resolved,
            text = cleanText,
            voiceId = voiceId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            format = format,
            emotion = emotion,
            instruction = instruction
        )
        // 注册表 key 包含书籍：缓存目录按书隔离，不能把另一本文本相同的音频路径直接返回。
        return sharedSpeechGenerations.await("$bookId:$key") {
            withContext(Dispatchers.IO) {
                val directory = speechCache.directoryFor(bookId)
                directory.listFiles()?.firstOrNull { it.nameWithoutExtension == key }?.let {
                    // 命中即续命：容量淘汰按最后使用时间来，常听的段落不该被先删。
                    it.setLastModified(System.currentTimeMillis())
                    return@withContext CachedSpeech(
                        it.absolutePath,
                        mediaTypeForExtension(it.extension),
                        true
                    )
                }
                null
            } ?: run {
                val generated = resolved.client.synthesizeSpeech(
                    text = cleanText,
                    voice = voiceId,
                    responseFormat = format,
                    speed = speed,
                    volume = volume,
                    pitch = pitch,
                    emotion = emotion,
                    instruction = instruction
                )
                require(generated.bytes.size <= MAX_MEDIA_BYTES) { "生成语音超过 30 MB，已取消缓存" }
                withContext(Dispatchers.IO) {
                    val directory = speechCache.directoryFor(bookId)
                    val output = File(directory, "$key.${audioExtension(generated.mediaType, format)}")
                    output.writeBytes(generated.bytes)
                    // 淘汰按全局容量而不是每本书的文件数：听一章长篇就能把文件名额用光，
                    // 而「60 个文件」到底占多少空间用户也无从判断。
                    speechCache.enforceBudget()
                    CachedSpeech(output.absolutePath, generated.mediaType, false)
                }
            }
        }
    }

    /** 只查本地语音缓存，不触发网络请求，也不刷新文件的淘汰时间。 */
    suspend fun peekCachedSpeech(
        bookId: Long,
        text: String,
        voiceId: String? = null,
        speed: Float? = null,
        volume: Float? = null,
        pitch: Int? = null,
        format: String? = null,
        emotion: String? = null,
        instruction: String? = null
    ): CachedSpeech? {
        val cleanText = text.trim().take(MAX_SPEECH_CHARS)
        if (cleanText.isEmpty()) return null
        val resolved = clientFactory.mediaForRole(ModelRole.TTS)
        val key = speechCacheKey(
            resolved = resolved,
            text = cleanText,
            voiceId = voiceId,
            speed = speed,
            volume = volume,
            pitch = pitch,
            format = format,
            emotion = emotion,
            instruction = instruction
        )
        return withContext(Dispatchers.IO) {
            speechCache.directoryFor(bookId)
                .listFiles()
                ?.firstOrNull { it.nameWithoutExtension == key }
                ?.let { CachedSpeech(it.absolutePath, mediaTypeForExtension(it.extension), true) }
        }
    }

    private fun speechCacheKey(
        resolved: com.mozhi.reader.ai.client.ResolvedMediaClient,
        text: String,
        voiceId: String?,
        speed: Float?,
        volume: Float?,
        pitch: Int?,
        format: String?,
        emotion: String?,
        instruction: String?
    ): String = SpeechCacheKey.build(
        providerId = resolved.provider.id,
        providerBaseUrl = resolved.provider.baseUrl,
        providerExtraJson = resolved.provider.extraJson,
        modelId = resolved.model.id,
        modelName = resolved.model.modelName,
        modelExtraJson = resolved.model.extraJson,
        text = text,
        voiceId = voiceId,
        speed = speed,
        volume = volume,
        pitch = pitch,
        format = format,
        emotion = emotion,
        instruction = instruction
    )

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

    private companion object {
        const val MAX_PROMPT_CHARS = 8_000
        const val MAX_SOURCE_CHARS = 8_000
        const val MAX_SPEECH_CHARS = 8_000
        const val MAX_MEDIA_BYTES = 30 * 1024 * 1024
    }
}
