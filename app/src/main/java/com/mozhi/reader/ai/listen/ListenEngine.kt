package com.mozhi.reader.ai.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.SentenceSegmenter
import com.mozhi.reader.core.speech.SentenceSpan
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** 听书会话对外快照；null = 没有进行中的听书。 */
data class ListenState(
    val bookId: Long,
    val bookTitle: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterCount: Int,
    /** 当前朗读句在本章的 UTF-16 起止（起点可能是从页首切入的半句）。 */
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val isPlaying: Boolean,
    val engineMode: TtsEngineMode,
    /** 暂停原因等提示（如合成失败），正常播放为 null。 */
    val status: String? = null
)

/**
 * 连续听书引擎（Legado 式）：从给定位置开始逐句朗读，读完一章自动进入下一章，
 * 直到全书结束或用户停止。系统 TTS 走整章批量入队（句间无停顿），AI TTS 逐句
 * 合成 + 预取下一句。进度实时写回 books 表，阅读页据 [state] 自动翻页。
 */
@Singleton
class ListenEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val ttsSettingsStore: TtsSettingsStore,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val mediaService: AiMediaGenerationService
) {
    private data class Utterance(val start: Int, val end: Int, val text: String)

    private data class PendingSeek(val chapterIndex: Int, val charOffset: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<ListenState?>(null)
    val state: StateFlow<ListenState?> = mutableState.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var sessionJob: Job? = null
    private var utteranceJob: Job? = null
    private var prefetchJob: Job? = null
    private var player: MediaPlayer? = null

    @Volatile
    private var pendingSeek: PendingSeek? = null

    /** 当前章的句子区间，给上一句/下一句用；只在会话协程里替换。 */
    @Volatile
    private var currentSpans: List<SentenceSpan> = emptyList()

    private var focusRequest: AudioFocusRequest? = null
    private var noisyReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    val isActive: Boolean get() = mutableState.value != null

    fun isListening(bookId: Long): Boolean = mutableState.value?.bookId == bookId

    /** 从指定位置开始新的听书会话；已有会话（含其他书）会先停止。 */
    fun start(bookId: Long, chapterIndex: Int, charOffset: Int) {
        stop()
        paused.value = false
        pendingSeek = null
        sessionJob = scope.launch {
            try {
                runSession(bookId, chapterIndex, charOffset)
            } finally {
                cleanupPlayback()
                mutableState.value = null
                ListenService.stop(context)
            }
        }
    }

    fun pause() {
        if (sessionJob == null) return
        paused.value = true
        utteranceJob?.cancel()
        mutableState.value = mutableState.value?.copy(isPlaying = false)
    }

    fun resume() {
        if (sessionJob == null) return
        mutableState.value = mutableState.value?.copy(isPlaying = true, status = null)
        paused.value = false
    }

    fun toggle() {
        if (paused.value) resume() else pause()
    }

    fun stop() {
        val job = sessionJob
        sessionJob = null
        pendingSeek = null
        utteranceJob?.cancel()
        if (job != null) {
            job.cancel()
        } else {
            mutableState.value = null
        }
    }

    /** 跳到指定位置继续朗读（阅读页手动翻页/跳章时同步听书位置）。 */
    fun seekTo(chapterIndex: Int, charOffset: Int) {
        if (sessionJob == null) return
        pendingSeek = PendingSeek(chapterIndex, charOffset.coerceAtLeast(0))
        utteranceJob?.cancel()
    }

    fun nextSentence() {
        val current = mutableState.value ?: return
        seekTo(current.chapterIndex, current.sentenceEnd)
    }

    fun prevSentence() {
        val current = mutableState.value ?: return
        val spans = currentSpans
        val prev = spans.lastOrNull { it.end <= current.sentenceStart }
        if (prev != null) {
            seekTo(current.chapterIndex, prev.start)
        } else if (current.chapterIndex > 0) {
            seekTo(current.chapterIndex - 1, 0)
        } else {
            seekTo(current.chapterIndex, 0)
        }
    }

    fun nextChapter() {
        val current = mutableState.value ?: return
        if (current.chapterIndex + 1 < current.chapterCount) {
            seekTo(current.chapterIndex + 1, 0)
        }
    }

    fun prevChapter() {
        val current = mutableState.value ?: return
        seekTo((current.chapterIndex - 1).coerceAtLeast(0), 0)
    }

    // ---- 会话主循环 ----

    private suspend fun runSession(bookId: Long, startChapter: Int, startOffset: Int) {
        val book = libraryRepository.getBook(bookId) ?: return
        val chapters = libraryRepository.getChapters(bookId)
        if (chapters.isEmpty()) return
        var chapterIndex = startChapter.coerceIn(0, chapters.lastIndex)
        var offset = startOffset.coerceAtLeast(0)
        var settings = ttsSettingsStore.current()
        mutableState.value = ListenState(
            bookId = bookId,
            bookTitle = book.title,
            chapterIndex = chapterIndex,
            chapterTitle = chapters[chapterIndex].title,
            chapterCount = chapters.size,
            sentenceStart = offset,
            sentenceEnd = offset,
            isPlaying = true,
            engineMode = settings.engineMode
        )
        ListenService.start(context)
        requestAudioFocus()
        registerNoisyReceiver()

        while (coroutineContext.isActive) {
            if (chapterIndex >= chapters.size) break
            val chapter = chapters[chapterIndex]
            val body = libraryRepository.readChapterText(bookId, chapter)
            val spans = SentenceSegmenter.segment(body)
            currentSpans = spans
            // 每章开头重读一次设置：切引擎/换音色在下一章生效。
            settings = ttsSettingsStore.current()
            var queue = buildQueue(body, spans, offset)

            inner@ while (coroutineContext.isActive) {
                pendingSeek?.let { seek ->
                    pendingSeek = null
                    if (seek.chapterIndex != chapterIndex) {
                        chapterIndex = seek.chapterIndex.coerceIn(0, chapters.lastIndex)
                        offset = seek.charOffset
                        break@inner
                    }
                    queue = buildQueue(body, spans, seek.charOffset)
                }
                if (queue.isEmpty()) {
                    chapterIndex++
                    offset = 0
                    break@inner
                }
                if (paused.value) {
                    val head = queue.first()
                    publishSentence(book.title, bookId, chapters, chapter, head, playing = false)
                    releaseWakeLock()
                    paused.first { !it }
                    continue@inner
                }
                acquireWakeLock()
                when (settings.engineMode) {
                    TtsEngineMode.SYSTEM -> {
                        val batch = queue
                        var reached = 0
                        var engineOk = true
                        val result = speakGuarded {
                            engineOk = systemTtsSpeaker.speakBatch(
                                utterances = batch.map(Utterance::text),
                                settings = settings
                            ) { startedIndex ->
                                reached = startedIndex
                                val utterance = batch.getOrNull(startedIndex) ?: return@speakBatch
                                publishSentence(
                                    book.title, bookId, chapters, chapter, utterance,
                                    playing = true
                                )
                                persistPosition(bookId, chapter.chapterIndex, utterance.start)
                            }
                        }
                        when {
                            result == SpeakResult.DONE && engineOk -> {
                                chapterIndex++
                                offset = 0
                                break@inner
                            }
                            result == SpeakResult.DONE -> {
                                pauseWithStatus("系统 TTS 引擎不可用，请到设置 › 语音朗读检查")
                                queue = queue.drop(reached)
                            }
                            result is SpeakResult.Error -> {
                                pauseWithStatus(result.error.listenMessage())
                                queue = queue.drop(reached)
                            }
                            else -> queue = queue.drop(reached)
                        }
                    }
                    TtsEngineMode.AI -> {
                        val utterance = queue.first()
                        publishSentence(book.title, bookId, chapters, chapter, utterance, playing = true)
                        persistPosition(bookId, chapter.chapterIndex, utterance.start)
                        prefetch(bookId, queue.getOrNull(1), settings)
                        val result = speakGuarded {
                            val speech = mediaService.synthesizeSpeech(
                                bookId = bookId,
                                text = utterance.text,
                                voiceId = settings.aiVoiceId.takeIf(String::isNotBlank),
                                speed = settings.aiSpeed.takeIf { it != 1f },
                                volume = settings.aiVolume.takeIf { it != 1f },
                                pitch = settings.aiPitch.takeIf { it != 0 }
                            )
                            playFile(speech.path)
                        }
                        when (result) {
                            SpeakResult.DONE -> queue = queue.drop(1)
                            is SpeakResult.Error -> pauseWithStatus(result.error.listenMessage())
                            SpeakResult.INTERRUPTED -> Unit
                        }
                    }
                }
            }
        }
        if (coroutineContext.isActive && mutableState.value != null) {
            // 自然读完全书：给通知栏留一个短暂的完成态，再由 finally 收尾。
            mutableState.value = mutableState.value?.copy(isPlaying = false, status = "全书朗读完毕")
            kotlinx.coroutines.delay(1_800)
        }
    }

    private fun buildQueue(body: String, spans: List<SentenceSpan>, fromOffset: Int): List<Utterance> {
        val index = SentenceSegmenter.indexAt(spans, fromOffset)
        if (index >= spans.size) return emptyList()
        val result = ArrayList<Utterance>(spans.size - index)
        spans.subList(index, spans.size).forEachIndexed { i, span ->
            val start = if (i == 0) maxOf(span.start, fromOffset) else span.start
            val text = SentenceSegmenter.speakableText(body, start, span.end)
            if (text.isNotEmpty()) result += Utterance(start, span.end, text)
        }
        return result
    }

    private fun publishSentence(
        bookTitle: String,
        bookId: Long,
        chapters: List<ChapterEntity>,
        chapter: ChapterEntity,
        utterance: Utterance,
        playing: Boolean
    ) {
        mutableState.value = ListenState(
            bookId = bookId,
            bookTitle = bookTitle,
            chapterIndex = chapter.chapterIndex,
            chapterTitle = chapter.title,
            chapterCount = chapters.size,
            sentenceStart = utterance.start,
            sentenceEnd = utterance.end,
            isPlaying = playing,
            engineMode = mutableState.value?.engineMode ?: TtsEngineMode.AI,
            status = if (playing) null else mutableState.value?.status
        )
    }

    private fun persistPosition(bookId: Long, chapterIndex: Int, charOffset: Int) {
        scope.launch {
            runCatching { libraryRepository.updateReadPosition(bookId, chapterIndex, charOffset) }
        }
    }

    private fun pauseWithStatus(message: String) {
        paused.value = true
        mutableState.value = mutableState.value?.copy(isPlaying = false, status = message)
    }

    // ---- 单句执行与打断 ----

    private sealed interface SpeakResult {
        data object DONE : SpeakResult
        data object INTERRUPTED : SpeakResult
        data class Error(val error: Throwable) : SpeakResult
    }

    /** 在子 Job 里执行朗读；pause/seek 只取消子 Job，不动会话协程。 */
    private suspend fun speakGuarded(block: suspend () -> Unit): SpeakResult {
        var completed = false
        var failure: Throwable? = null
        coroutineScope {
            val job = launch {
                try {
                    block()
                    completed = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    failure = error
                }
            }
            utteranceJob = job
            job.join()
            utteranceJob = null
        }
        return when {
            completed -> SpeakResult.DONE
            failure != null -> SpeakResult.Error(failure!!)
            else -> SpeakResult.INTERRUPTED
        }
    }

    private fun prefetch(bookId: Long, next: Utterance?, settings: TtsSettings) {
        prefetchJob?.cancel()
        if (next == null) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            runCatching {
                mediaService.synthesizeSpeech(
                    bookId = bookId,
                    text = next.text,
                    voiceId = settings.aiVoiceId.takeIf(String::isNotBlank),
                    speed = settings.aiSpeed.takeIf { it != 1f },
                    volume = settings.aiVolume.takeIf { it != 1f },
                    pitch = settings.aiPitch.takeIf { it != 0 }
                )
            }
        }
    }

    private suspend fun playFile(path: String) = suspendCancellableCoroutine { continuation ->
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        mediaPlayer.setOnPreparedListener { it.start() }
        mediaPlayer.setOnCompletionListener {
            it.release()
            if (player === it) player = null
            if (continuation.isActive) continuation.resume(Unit)
        }
        mediaPlayer.setOnErrorListener { failed, _, _ ->
            failed.release()
            if (player === failed) player = null
            if (continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("无法播放合成语音"))
            }
            true
        }
        continuation.invokeOnCancellation {
            mediaPlayer.runCatching { release() }
            if (player === mediaPlayer) player = null
        }
        try {
            mediaPlayer.setDataSource(path)
            mediaPlayer.prepareAsync()
        } catch (error: Throwable) {
            mediaPlayer.runCatching { release() }
            if (player === mediaPlayer) player = null
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    // ---- 音频焦点 / 熄屏保活 ----

    private fun requestAudioFocus() {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause()
                    else -> Unit
                }
            }
            .build()
        focusRequest = request
        // 焦点被拒极罕见；即便被拒也继续播放，交给系统混音。
        audioManager.requestAudioFocus(request)
    }

    private fun registerNoisyReceiver() {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
            }
        }
        noisyReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "moread:listen").also {
            it.setReferenceCounted(false)
            it.acquire(WAKE_LOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.runCatching { release() }
    }

    private fun cleanupPlayback() {
        prefetchJob?.cancel()
        prefetchJob = null
        utteranceJob?.cancel()
        utteranceJob = null
        systemTtsSpeaker.stop()
        player?.runCatching { release() }
        player = null
        releaseWakeLock()
        wakeLock = null
        noisyReceiver?.let { receiver ->
            runCatching { context.unregisterReceiver(receiver) }
        }
        noisyReceiver = null
        focusRequest?.let { request ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
        currentSpans = emptyList()
    }

    private fun Throwable.listenMessage(): String = when (this) {
        is AiClientException -> message ?: "语音合成失败"
        is IllegalArgumentException -> message ?: "语音合成失败"
        else -> "朗读中断：${message ?: javaClass.simpleName}"
    }

    private companion object {
        /** 兜底超时：整章批量朗读也远小于 30 分钟；正常路径靠显式 release。 */
        const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    }
}
