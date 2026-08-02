package com.mozhi.reader.core.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

data class SystemTtsEngineInfo(val packageName: String, val label: String)

/**
 * 系统 TTS 门面（android.speech.tts.TextToSpeech）：支持指定第三方引擎（如 Multi TTS）、
 * 语言/语速/音调；长文按引擎输入上限分段 QUEUE_ADD。实例按引擎包名缓存，切引擎重建。
 */
@Singleton
class SystemTtsSpeaker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var tts: TextToSpeech? = null
    private var currentEnginePackage: String? = null

    private val mutableSpeaking = MutableStateFlow(false)
    val isSpeaking = mutableSpeaking.asStateFlow()

    /** 枚举已安装引擎；需要一个已初始化实例才能拿列表。 */
    suspend fun engines(): List<SystemTtsEngineInfo> {
        val instance = obtain("") ?: return emptyList()
        return instance.engines.map { SystemTtsEngineInfo(it.name, it.label) }
    }

    /** 按配置朗读；返回 false 表示引擎初始化失败或不支持所选语言。 */
    suspend fun speak(text: String, settings: TtsSettings): Boolean {
        val clean = text.trim()
        if (clean.isEmpty()) return false
        val instance = obtain(settings.systemEnginePackage) ?: return false
        if (settings.systemLanguageTag.isNotBlank()) {
            val result = instance.setLanguage(Locale.forLanguageTag(settings.systemLanguageTag))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return false
            }
        }
        instance.setSpeechRate(settings.systemRate.coerceIn(0.3f, 3f))
        instance.setPitch(settings.systemPitch.coerceIn(0.3f, 3f))
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mutableSpeaking.value = true
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == FINAL_UTTERANCE_ID) mutableSpeaking.value = false
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                mutableSpeaking.value = false
            }
        })
        val maxLength = TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3_500)
        val segments = clean.chunked(maxLength)
        segments.forEachIndexed { index, segment ->
            val isLast = index == segments.lastIndex
            instance.speak(
                segment,
                if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null,
                if (isLast) FINAL_UTTERANCE_ID else "moread-tts-$index"
            )
        }
        mutableSpeaking.value = true
        return true
    }

    fun stop() {
        tts?.runCatching { stop() }
        mutableSpeaking.value = false
    }

    /**
     * 听书批量朗读：整批 QUEUE_ADD 进引擎队列（句间无停顿），每个 utterance 开播时在
     * binder 线程回调 [onUtteranceStart]（参数为批内下标）。挂起直到整批播完；协程取消会
     * 立刻停掉引擎队列。返回 false 表示引擎初始化失败、语言不支持或引擎报错。
     */
    suspend fun speakBatch(
        utterances: List<String>,
        settings: TtsSettings,
        onUtteranceStart: (Int) -> Unit
    ): Boolean {
        if (utterances.isEmpty()) return true
        val instance = obtain(settings.systemEnginePackage) ?: return false
        if (settings.systemLanguageTag.isNotBlank()) {
            val result = instance.setLanguage(Locale.forLanguageTag(settings.systemLanguageTag))
            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                return false
            }
        }
        instance.setSpeechRate(settings.systemRate.coerceIn(0.3f, 3f))
        instance.setPitch(settings.systemPitch.coerceIn(0.3f, 3f))
        return suspendCancellableCoroutine { continuation ->
            val lastId = batchUtteranceId(utterances.lastIndex)
            instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mutableSpeaking.value = true
                    batchIndexOf(utteranceId)?.let(onUtteranceStart)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == lastId) {
                        mutableSpeaking.value = false
                        if (continuation.isActive) continuation.resume(true)
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mutableSpeaking.value = false
                    if (continuation.isActive) continuation.resume(false)
                }
            })
            utterances.forEachIndexed { index, text ->
                instance.speak(
                    text,
                    if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                    null,
                    batchUtteranceId(index)
                )
            }
            mutableSpeaking.value = true
            continuation.invokeOnCancellation {
                instance.runCatching { stop() }
                mutableSpeaking.value = false
            }
        }
    }

    private fun batchUtteranceId(index: Int): String = "$BATCH_UTTERANCE_PREFIX$index"

    private fun batchIndexOf(utteranceId: String?): Int? = utteranceId
        ?.takeIf { it.startsWith(BATCH_UTTERANCE_PREFIX) }
        ?.removePrefix(BATCH_UTTERANCE_PREFIX)
        ?.toIntOrNull()

    fun release() {
        tts?.runCatching { shutdown() }
        tts = null
        currentEnginePackage = null
        mutableSpeaking.value = false
    }

    /** 初始化是异步回调；挂起到 onInit，失败返回 null。 */
    private suspend fun obtain(enginePackage: String): TextToSpeech? {
        val existing = tts
        if (existing != null && currentEnginePackage == enginePackage) return existing
        release()
        return suspendCancellableCoroutine { continuation ->
            var instance: TextToSpeech? = null
            val listener = TextToSpeech.OnInitListener { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts = instance
                    currentEnginePackage = enginePackage
                    if (continuation.isActive) continuation.resume(instance)
                } else {
                    instance?.runCatching { shutdown() }
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            instance = if (enginePackage.isBlank()) {
                TextToSpeech(context, listener)
            } else {
                TextToSpeech(context, listener, enginePackage)
            }
            continuation.invokeOnCancellation { instance.runCatching { shutdown() } }
        }
    }

    private companion object {
        const val FINAL_UTTERANCE_ID = "moread-tts-final"
        const val BATCH_UTTERANCE_PREFIX = "moread-listen-"
    }
}
