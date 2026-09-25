package com.mozhi.reader.core.speech

import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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
    private val initMutex = Mutex()
    private val playbackMutex = Mutex()
    @Volatile private var finishPlayback: (() -> Unit)? = null
    private var currentEnginePackage: String? = null

    private val mutableSpeaking = MutableStateFlow(false)
    val isSpeaking = mutableSpeaking.asStateFlow()

    /** Query installed services without opening or switching the playback engine. */
    suspend fun engines(): List<SystemTtsEngineInfo> = withContext(Dispatchers.IO) {
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .map { SystemTtsEngineInfo(it.serviceInfo.packageName, it.loadLabel(context.packageManager).toString()) }
            .distinctBy { it.packageName }.sortedBy { it.label }
    }

    /** A short-lived connection keeps voice discovery independent of active playback. */
    suspend fun voices(enginePackage: String): List<SystemTtsVoiceInfo> = withContext(Dispatchers.Main.immediate) {
        val instance = initialize(enginePackage) ?: error("语音引擎连接失败，请确认已安装并启用")
        try {
            val actualPackage = enginePackage.ifBlank { instance.defaultEngine.orEmpty() }
            instance.voices.orEmpty().map { voice ->
                SystemTtsVoiceInfo(actualPackage, voice.name, voice.locale.toLanguageTag(), voice.isNetworkConnectionRequired)
            }.distinctBy { it.id }.sortedWith(compareBy<SystemTtsVoiceInfo> { !it.languageTag.startsWith("zh") }.thenBy { it.name })
        } finally { instance.shutdown() }
    }

    private fun configure(instance: TextToSpeech, settings: TtsSettings): Boolean {
        if (settings.systemVoiceName.isNotBlank()) {
            val voice = instance.voices.orEmpty().firstOrNull { it.name == settings.systemVoiceName } ?: return false
            if (instance.setVoice(voice) == TextToSpeech.ERROR) return false
        } else if (settings.systemLanguageTag.isNotBlank()) {
            if (instance.setLanguage(Locale.forLanguageTag(settings.systemLanguageTag)) < TextToSpeech.LANG_AVAILABLE) return false
        } else {
            instance.defaultVoice?.let { if (instance.setVoice(it) == TextToSpeech.ERROR) return false }
        }
        return instance.setSpeechRate(settings.systemRate.coerceIn(0.3f, 3f)) != TextToSpeech.ERROR &&
            instance.setPitch(settings.systemPitch.coerceIn(0.3f, 3f)) != TextToSpeech.ERROR
    }

    /** Preview and selection reading share the same completion and cancellation handling as listening. */
    suspend fun speak(text: String, settings: TtsSettings): Boolean {
        if (text.isBlank()) return false
        stop()
        return speakBatch(listOf(text.trim()), settings) {}
    }

    fun stop() {
        finishPlayback?.invoke()
        tts?.runCatching { stop() }
        mutableSpeaking.value = false
    }

    suspend fun speakBatch(
        utterances: List<String>,
        settings: TtsSettings,
        onUtteranceStart: (Int) -> Unit
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        playbackMutex.withLock {
            if (utterances.isEmpty()) return@withLock true
            val instance = obtain(settings.systemEnginePackage) ?: return@withLock false
            if (!configure(instance, settings)) return@withLock false
            val chunks = utterances.flatMapIndexed { index, text ->
                text.chunked(TextToSpeech.getMaxSpeechInputLength().coerceAtMost(3_500)).map { index to it }
            }
            if (chunks.isEmpty()) return@withLock true
            val prefix = UUID.randomUUID().toString() + ":"
            try {
                suspendCancellableCoroutine { continuation ->
                    val finished = AtomicBoolean(false)
                    fun finish(ok: Boolean) {
                        if (finished.compareAndSet(false, true)) {
                            mutableSpeaking.value = false
                            if (!ok) instance.stop()
                            if (continuation.isActive) continuation.resume(ok)
                        }
                    }
                    finishPlayback = { finish(false) }
                    instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (finished.get() || utteranceId?.startsWith(prefix) != true) return
                            val chunk = utteranceId.removePrefix(prefix).toIntOrNull() ?: return
                            chunks.getOrNull(chunk)?.first?.let(onUtteranceStart)
                        }
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == prefix + chunks.lastIndex) finish(true)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId?.startsWith(prefix) == true) finish(false)
                        }
                    })
                    continuation.invokeOnCancellation { finish(false) }
                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.systemVolumeCompensation.coerceIn(0.25f, 2f))
                    }
                    if (!finished.get()) mutableSpeaking.value = true
                    for ((index, chunk) in chunks.withIndex()) {
                        if (finished.get()) break
                        if (instance.speak(chunk.second, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, params, prefix + index) == TextToSpeech.ERROR) {
                            finish(false)
                            break
                        }
                    }
                }
            } finally {
                finishPlayback = null
                mutableSpeaking.value = false
            }
        }
    }

    fun release() {
        stop()
        tts?.runCatching { shutdown() }
        tts = null
        currentEnginePackage = null
    }

    private suspend fun obtain(enginePackage: String): TextToSpeech? = initMutex.withLock {
        val existing = tts
        if (existing != null && currentEnginePackage == enginePackage) return@withLock existing
        release()
        initialize(enginePackage)?.also { tts = it; currentEnginePackage = enginePackage }
    }

    private suspend fun initialize(enginePackage: String): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        if (enginePackage.isNotBlank() && engines().none { it.packageName == enginePackage }) return@withContext null
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine { continuation ->
                var instance: TextToSpeech? = null
                val listener = TextToSpeech.OnInitListener { status ->
                    Handler(Looper.getMainLooper()).post {
                        if (!continuation.isActive || status != TextToSpeech.SUCCESS) {
                            instance?.shutdown()
                            if (continuation.isActive) continuation.resume(null)
                        } else continuation.resume(instance)
                    }
                }
                instance = if (enginePackage.isBlank()) TextToSpeech(context, listener)
                    else TextToSpeech(context, listener, enginePackage)
                continuation.invokeOnCancellation { instance?.shutdown() }
            }
        }
    }

}
