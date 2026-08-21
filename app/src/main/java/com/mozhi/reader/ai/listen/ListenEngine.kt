package com.mozhi.reader.ai.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.SystemClock
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.mozhi.reader.ai.client.AiClientException
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.database.entity.ChapterEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.ReaderTextReplacementRule
import com.mozhi.reader.core.datastore.audiobookRevision
import com.mozhi.reader.core.datastore.purifyForListening
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.SentenceSegmenter
import com.mozhi.reader.core.speech.SentenceSpan
import com.mozhi.reader.core.speech.SleepTimerPlan
import com.mozhi.reader.core.speech.SleepTimerPlanner
import com.mozhi.reader.core.speech.SleepTimerState
import com.mozhi.reader.core.speech.SystemTtsSpeaker
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSettingsStore
import com.mozhi.reader.core.speech.TtsSynthesisGranularity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine

enum class ListenPlaybackMode { STANDARD, PRODUCED }

/** 听书会话对外快照；null = 没有进行中的听书。 */
data class ListenState(
    val bookId: Long,
    val bookTitle: String,
    val coverPath: String? = null,
    val chapterIndex: Int,
    val chapterTitle: String,
    val chapterCount: Int,
    /** 当前朗读句在本章的 UTF-16 起止（起点可能是从页首切入的半句）。 */
    val sentenceStart: Int,
    val sentenceEnd: Int,
    val previousText: String = "",
    val currentText: String = "",
    val nextText: String = "",
    val chapterProgress: Float = 0f,
    val isPlaying: Boolean,
    val engineMode: TtsEngineMode,
    /** STANDARD 只走普通 TTS；PRODUCED 只播放已制作的多角色章节，不静默回退。 */
    val playbackMode: ListenPlaybackMode = ListenPlaybackMode.STANDARD,
    val scripted: Boolean = false,
    val currentRoleName: String? = null,
    val currentRoleColor: String? = null,
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
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val ttsSettingsStore: TtsSettingsStore,
    private val systemTtsSpeaker: SystemTtsSpeaker,
    private val mediaService: AiMediaGenerationService,
    private val audiobookRepository: AudiobookRepository
) {
    private data class Utterance(
        val start: Int,
        val end: Int,
        val text: String,
        val engineMode: TtsEngineMode? = null,
        val voiceId: String? = null,
        val emotion: String? = null,
        val instruction: String? = null,
        val audioPath: String? = null,
        val roleName: String? = null,
        val roleColor: String? = null
    )

    private data class PendingSeek(val chapterIndex: Int, val charOffset: Int)

    private data class ChapterPlaybackPlan(
        val utterances: List<Utterance>,
        val scripted: Boolean,
        val status: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<ListenState?>(null)
    val state: StateFlow<ListenState?> = mutableState.asStateFlow()
    private val mutableSleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = mutableSleepTimer.asStateFlow()

    private val paused = MutableStateFlow(false)
    private var sessionJob: Job? = null
    private var sessionToken: Any? = null
    @Volatile private var activeBookId: Long? = null
    @Volatile private var activePlaybackMode: ListenPlaybackMode? = null
    private var utteranceJob: Job? = null
    private var prefetchJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var player: MediaPlayer? = null

    @Volatile
    private var pendingSeek: PendingSeek? = null

    /** 当前章的句子区间，给上一句/下一句用；只在会话协程里替换。 */
    @Volatile
    private var currentSpans: List<SentenceSpan> = emptyList()

    private var focusRequest: AudioFocusRequest? = null
    private var resumeAfterTransientFocusLoss = false
    private var noisyReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var currentBodyLength: Int = 0

    val isActive: Boolean get() = activeBookId != null

    fun isListening(bookId: Long): Boolean = activeBookId == bookId

    fun isListening(bookId: Long, playbackMode: ListenPlaybackMode): Boolean =
        activeBookId == bookId && activePlaybackMode == playbackMode

    /**
     * 从指定位置开始听书。启动中的会话也会同步登记，同书同模式重复进入播放页是幂等的。
     * 旧会话的 finally 通过 token 隔离，不能反过来清理刚启动的新会话。
     */
    fun start(
        bookId: Long,
        chapterIndex: Int,
        charOffset: Int,
        playbackMode: ListenPlaybackMode = ListenPlaybackMode.STANDARD
    ) {
        if (isListening(bookId, playbackMode) && sessionJob?.isActive == true) return

        val previous = sessionJob
        val token = Any()
        sessionToken = token
        activeBookId = bookId
        activePlaybackMode = playbackMode
        sessionJob = null
        previous?.cancel()
        cleanupPlayback()
        mutableState.value = null
        paused.value = false
        pendingSeek = null

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runSession(bookId, chapterIndex, charOffset, playbackMode)
            } finally {
                if (sessionToken === token) {
                    cleanupPlayback()
                    mutableState.value = null
                    activeBookId = null
                    activePlaybackMode = null
                    sessionJob = null
                    sessionToken = null
                    ListenService.stop(context)
                }
            }
        }
        sessionJob = job
        job.start()
    }

    fun pause() {
        resumeAfterTransientFocusLoss = false
        pausePlayback()
    }

    private fun pausePlayback() {
        if (sessionJob == null) return
        paused.value = true
        utteranceJob?.cancel()
        mutableState.value = mutableState.value?.copy(isPlaying = false)
    }

    fun resume() {
        if (sessionJob == null) return
        resumeAfterTransientFocusLoss = false
        mutableState.value = mutableState.value?.copy(isPlaying = true, status = null)
        paused.value = false
    }

    fun toggle() {
        if (paused.value) resume() else pause()
    }

    fun stop() {
        val job = sessionJob
        sessionToken = null
        activeBookId = null
        activePlaybackMode = null
        sessionJob = null
        pendingSeek = null
        resumeAfterTransientFocusLoss = false
        job?.cancel()
        cleanupPlayback()
        mutableState.value = null
        ListenService.stop(context)
    }

    fun setSleepTimer(plan: SleepTimerPlan?) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        mutableSleepTimer.value = plan?.let(SleepTimerPlanner::start)
        if (plan == null) return
        sleepTimerJob = scope.launch {
            var previousTick = SystemClock.elapsedRealtime()
            while (isActive && mutableSleepTimer.value != null && sessionJob != null) {
                kotlinx.coroutines.delay(SLEEP_TIMER_TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - previousTick
                previousTick = now
                val current = mutableSleepTimer.value ?: break
                val next = SleepTimerPlanner.tick(
                    state = current,
                    elapsedMillis = elapsed,
                    playing = mutableState.value?.isPlaying == true
                )
                mutableSleepTimer.value = next
                if (SleepTimerPlanner.isExpired(next)) {
                    fadeOutAndStop()
                    break
                }
            }
        }
    }

    fun seekToChapterFraction(fraction: Float) {
        val current = mutableState.value ?: return
        seekTo(current.chapterIndex, (currentBodyLength * fraction.coerceIn(0f, 1f)).toInt())
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

    private suspend fun runSession(
        bookId: Long,
        startChapter: Int,
        startOffset: Int,
        playbackMode: ListenPlaybackMode
    ) {
        val book = libraryRepository.getBook(bookId) ?: return
        val chapters = libraryRepository.getChapters(bookId)
        if (chapters.isEmpty()) return
        var chapterIndex = startChapter.coerceIn(0, chapters.lastIndex)
        var offset = startOffset.coerceAtLeast(0)
        var settings = ttsSettingsStore.current()
        mutableState.value = ListenState(
            bookId = bookId,
            bookTitle = book.title,
            coverPath = book.coverPath,
            chapterIndex = chapterIndex,
            chapterTitle = chapters[chapterIndex].title,
            chapterCount = chapters.size,
            sentenceStart = offset,
            sentenceEnd = offset,
            isPlaying = true,
            engineMode = settings.engineMode,
            playbackMode = playbackMode,
            status = if (playbackMode == ListenPlaybackMode.PRODUCED) "正在载入多角色成品" else null
        )
        ListenService.start(
            context = context,
            bookId = bookId,
            chapterIndex = chapterIndex,
            charOffset = offset,
            playbackMode = playbackMode
        )
        registerNoisyReceiver()
        var playedProducedChapter = false
        var producedSkipStatus: String? = null

        while (coroutineContext.isActive) {
            if (chapterIndex >= chapters.size) break
            val chapter = chapters[chapterIndex]
            val body = libraryRepository.readChapterText(bookId, chapter)
            // 每章开头重读一次设置：切引擎/换音色在下一章生效。
            settings = ttsSettingsStore.current()
            configureAudioFocus(settings.allowAudioMixing)
            val rules = readerSettingsRepository.settings.first().textReplacementRules
            val playbackPlan = buildChapterPlaybackPlan(
                bookId = bookId,
                chapter = chapter,
                body = body,
                rules = rules,
                requireProducedAudio = playbackMode == ListenPlaybackMode.PRODUCED
            )
            if (playbackMode == ListenPlaybackMode.PRODUCED && !playbackPlan.scripted) {
                // “播放成品”只播 READY 的多角色章节，绝不静默退回普通 TTS。
                producedSkipStatus = playbackPlan.status ?: producedSkipStatus
                chapterIndex++
                offset = 0
                continue
            }
            if (playbackPlan.scripted) playedProducedChapter = true
            val spans = if (playbackPlan.scripted) {
                playbackPlan.utterances.map { SentenceSpan(it.start, it.end) }
            } else {
                segment(body, settings)
            }
            currentSpans = spans
            currentBodyLength = body.length
            var queue = if (playbackPlan.scripted) {
                playbackPlan.utterances.dropWhile { it.end <= offset }
            } else {
                buildQueue(body, spans, offset, rules)
            }

            inner@ while (coroutineContext.isActive) {
                pendingSeek?.let { seek ->
                    pendingSeek = null
                    if (seek.chapterIndex != chapterIndex) {
                        chapterIndex = seek.chapterIndex.coerceIn(0, chapters.lastIndex)
                        offset = seek.charOffset
                        break@inner
                    }
                    queue = if (playbackPlan.scripted) {
                        playbackPlan.utterances.dropWhile { it.end <= seek.charOffset }
                    } else {
                        buildQueue(body, spans, seek.charOffset, rules)
                    }
                }
                if (queue.isEmpty()) {
                    if (onChapterCompleted()) return
                    chapterIndex++
                    offset = 0
                    break@inner
                }
                if (paused.value) {
                    val head = queue.first()
                    publishSentence(
                        book, chapters, chapter, body, spans, head,
                        playing = false,
                        status = playbackPlan.status
                    )
                    releaseWakeLock()
                    paused.first { !it }
                    continue@inner
                }
                acquireWakeLock()
                settings = ttsSettingsStore.current()
                configureAudioFocus(settings.allowAudioMixing)
                when (queue.first().engineMode ?: settings.engineMode) {
                    TtsEngineMode.SYSTEM -> {
                        val batch = if (playbackPlan.scripted) listOf(queue.first()) else queue
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
                                    book, chapters, chapter, body, spans, utterance,
                                    playing = true,
                                    status = playbackPlan.status
                                )
                                persistPosition(bookId, chapter.chapterIndex, utterance.start)
                            }
                        }
                        when {
                            result == SpeakResult.DONE && engineOk -> queue = queue.drop(batch.size)
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
                        publishSentence(
                            book, chapters, chapter, body, spans, utterance,
                            playing = true,
                            status = playbackPlan.status
                        )
                        persistPosition(bookId, chapter.chapterIndex, utterance.start)
                        prefetch(bookId, queue.drop(1), settings)
                        val result = speakGuarded {
                            val readyPath = utterance.audioPath?.takeIf { it.isNotBlank() && File(it).isFile }
                            if (readyPath != null) {
                                playFile(readyPath)
                            } else {
                                val speech = synthesizeWithRetry(bookId, utterance, settings)
                                playFile(speech.path)
                            }
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
            val completionStatus = when {
                playbackMode == ListenPlaybackMode.STANDARD -> "全书朗读完毕"
                playedProducedChapter -> "已播放全部可用成品章节"
                else -> producedSkipStatus ?: "没有可播放的成品章节，请先完成制作"
            }
            mutableState.value = mutableState.value?.copy(isPlaying = false, status = completionStatus)
            kotlinx.coroutines.delay(1_800)
        }
    }

    private fun segment(body: String, settings: TtsSettings): List<SentenceSpan> = when (
        settings.synthesisGranularity
    ) {
        TtsSynthesisGranularity.SENTENCE -> SentenceSegmenter.segment(
            body,
            settings.maxSynthesisChars
        )
        TtsSynthesisGranularity.PARAGRAPH -> SentenceSegmenter.segmentParagraphs(
            body,
            settings.maxSynthesisChars
        )
        TtsSynthesisGranularity.CHAPTER -> SentenceSegmenter.segmentChapter(
            body,
            settings.maxSynthesisChars
        )
    }

    private suspend fun buildChapterPlaybackPlan(
        bookId: Long,
        chapter: ChapterEntity,
        body: String,
        rules: List<ReaderTextReplacementRule>,
        requireProducedAudio: Boolean
    ): ChapterPlaybackPlan {
        val chapterState = audiobookRepository.getChapter(bookId, chapter.chapterIndex)
        val usableStates = if (requireProducedAudio) {
            setOf(AudiobookChapterState.READY.name)
        } else {
            setOf(
                AudiobookChapterState.CONFIRMED.name,
                AudiobookChapterState.SYNTHESIZING.name,
                AudiobookChapterState.READY.name
            )
        }
        if (chapterState?.state !in usableStates) {
            return ChapterPlaybackPlan(emptyList(), scripted = false)
        }
        val segments = audiobookRepository.getSegments(bookId, chapter.chapterIndex)
        if (segments.isEmpty() || segments.any { it.revision != audiobookRevision(body, rules) }) {
            audiobookRepository.markStale(bookId, chapter.chapterIndex)
            return ChapterPlaybackPlan(
                utterances = emptyList(),
                scripted = false,
                status = "成品剧本已过期，请重新制作"
            )
        }
        val roles = audiobookRepository.getRoles(bookId).associateBy(AudiobookRoleEntity::id)
        val utterances = buildScriptedQueue(body, segments, roles, rules)
        return if (utterances.isEmpty()) {
            ChapterPlaybackPlan(emptyList(), scripted = false)
        } else {
            ChapterPlaybackPlan(
                utterances = utterances,
                scripted = true,
                status = if (chapterState?.state == AudiobookChapterState.READY.name) {
                    "正在播放多角色成品"
                } else {
                    "多角色实时朗读 · AI 对白按需生成"
                }
            )
        }
    }

    private fun buildScriptedQueue(
        body: String,
        segments: List<AudiobookSegmentEntity>,
        roles: Map<Long, AudiobookRoleEntity>,
        rules: List<ReaderTextReplacementRule>
    ): List<Utterance> = segments.mapNotNull { segment ->
        val role = segment.roleId?.let(roles::get) ?: return@mapNotNull null
        val start = segment.startCharOffset.coerceIn(0, body.length)
        val end = segment.endCharOffset.coerceIn(start, body.length)
        val text = purifyForListening(body, start, end, rules).text
            .replace('\uFFFC', ' ')
            .trim()
        if (text.isEmpty()) return@mapNotNull null
        val engine = if (role.engine == AudiobookEngine.SYSTEM.name) {
            TtsEngineMode.SYSTEM
        } else {
            TtsEngineMode.AI
        }
        Utterance(
            start = start,
            end = end,
            text = text,
            engineMode = engine,
            voiceId = role.voiceId.takeIf(String::isNotBlank),
            emotion = segment.emotion,
            instruction = segment.instruction,
            audioPath = segment.audioPath,
            roleName = role.name,
            roleColor = role.color
        )
    }

    private fun buildQueue(
        body: String,
        spans: List<SentenceSpan>,
        fromOffset: Int,
        rules: List<ReaderTextReplacementRule>
    ): List<Utterance> {
        val index = SentenceSegmenter.indexAt(spans, fromOffset)
        if (index >= spans.size) return emptyList()
        val result = ArrayList<Utterance>(spans.size - index)
        spans.subList(index, spans.size).forEachIndexed { i, span ->
            val start = if (i == 0) maxOf(span.start, fromOffset) else span.start
            val text = purifyForListening(body, start, span.end, rules).text
                .replace('\uFFFC', ' ')
                .trim()
            if (text.isNotEmpty()) result += Utterance(start, span.end, text)
        }
        return result
    }

    private fun publishSentence(
        book: com.mozhi.reader.core.database.entity.BookEntity,
        chapters: List<ChapterEntity>,
        chapter: ChapterEntity,
        body: String,
        spans: List<SentenceSpan>,
        utterance: Utterance,
        playing: Boolean,
        status: String? = null
    ) {
        val spanIndex = spans.indexOfFirst { it.start == utterance.start && it.end == utterance.end }
            .takeIf { it >= 0 }
            ?: SentenceSegmenter.indexAt(spans, utterance.start)
        fun textAt(index: Int): String = spans.getOrNull(index)?.let { span ->
            body.substring(span.start, span.end).replace('\uFFFC', ' ').trim()
        }.orEmpty()
        mutableState.value = ListenState(
            bookId = book.id,
            bookTitle = book.title,
            coverPath = book.coverPath,
            chapterIndex = chapter.chapterIndex,
            chapterTitle = chapter.title,
            chapterCount = chapters.size,
            sentenceStart = utterance.start,
            sentenceEnd = utterance.end,
            previousText = textAt(spanIndex - 1),
            currentText = body.substring(utterance.start, utterance.end)
                .replace('\uFFFC', ' ')
                .trim(),
            nextText = textAt(spanIndex + 1),
            chapterProgress = if (body.isEmpty()) 0f else utterance.start.toFloat() / body.length,
            isPlaying = playing,
            engineMode = utterance.engineMode ?: mutableState.value?.engineMode ?: TtsEngineMode.AI,
            playbackMode = mutableState.value?.playbackMode ?: ListenPlaybackMode.STANDARD,
            scripted = utterance.engineMode != null,
            currentRoleName = utterance.roleName,
            currentRoleColor = utterance.roleColor,
            status = status ?: if (playing) null else mutableState.value?.status
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

    private fun prefetch(bookId: Long, upcoming: List<Utterance>, settings: TtsSettings) {
        prefetchJob?.cancel()
        val candidates = upcoming
            .filter { (it.engineMode ?: TtsEngineMode.AI) == TtsEngineMode.AI }
            .filterNot { utterance ->
                utterance.audioPath?.let { path -> path.isNotBlank() && File(path).isFile } == true
            }
            .take(settings.prefetchCount)
        if (candidates.isEmpty()) return
        prefetchJob = scope.launch(Dispatchers.IO) {
            val semaphore = Semaphore(settings.synthesisConcurrency)
            coroutineScope {
                candidates.forEach { utterance ->
                    launch {
                        semaphore.withPermit {
                            runCatching { synthesizeWithRetry(bookId, utterance, settings) }
                        }
                    }
                }
            }
        }
    }

    private suspend fun synthesizeWithRetry(
        bookId: Long,
        utterance: Utterance,
        settings: TtsSettings
    ): com.mozhi.reader.ai.media.CachedSpeech {
        var failure: Throwable? = null
        repeat(settings.retryCount + 1) { attempt ->
            try {
                return mediaService.synthesizeSpeech(
                    bookId = bookId,
                    text = utterance.text,
                    voiceId = utterance.voiceId ?: settings.aiVoiceId.takeIf(String::isNotBlank),
                    speed = settings.aiSpeed.takeIf { it != 1f },
                    volume = settings.aiVolume.takeIf { it != 1f },
                    pitch = settings.aiPitch.takeIf { it != 0 },
                    emotion = utterance.emotion,
                    instruction = utterance.instruction
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                failure = error
                if (attempt < settings.retryCount) {
                    kotlinx.coroutines.delay(RETRY_BASE_DELAY_MS shl attempt)
                }
            }
        }
        throw failure ?: IllegalStateException("语音合成失败")
    }

    private fun onChapterCompleted(): Boolean {
        val current = mutableSleepTimer.value ?: return false
        if (current.remainingChapters == null) return false
        val next = SleepTimerPlanner.onChapterCompleted(current)
        mutableSleepTimer.value = next
        return SleepTimerPlanner.isExpired(next)
    }

    private suspend fun fadeOutAndStop() {
        mutableState.value = mutableState.value?.copy(status = "定时结束", isPlaying = true)
        val activePlayer = player
        if (activePlayer != null) {
            repeat(FADE_STEPS) { step ->
                val volume = 1f - (step + 1).toFloat() / FADE_STEPS
                activePlayer.runCatching { setVolume(volume, volume) }
                kotlinx.coroutines.delay(FADE_DURATION_MS / FADE_STEPS)
            }
        }
        stop()
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

    private fun configureAudioFocus(allowMixing: Boolean) {
        if (allowMixing) {
            abandonAudioFocus()
        } else if (focusRequest == null) {
            requestAudioFocus()
        }
    }

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
                when (
                    decideListenAudioFocusAction(
                        change = change,
                        isPlaying = mutableState.value?.isPlaying == true && !paused.value,
                        resumePending = resumeAfterTransientFocusLoss
                    )
                ) {
                    ListenAudioFocusAction.PAUSE -> pause()
                    ListenAudioFocusAction.PAUSE_AND_RESUME -> {
                        resumeAfterTransientFocusLoss = true
                        pausePlayback()
                    }
                    ListenAudioFocusAction.RESUME -> resume()
                    ListenAudioFocusAction.NONE -> Unit
                }
            }
            .build()
        focusRequest = request
        // 焦点被拒极罕见；即便被拒也继续播放，交给系统混音。
        audioManager.requestAudioFocus(request)
    }

    private fun abandonAudioFocus() {
        focusRequest?.let { request ->
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            runCatching { audioManager.abandonAudioFocusRequest(request) }
        }
        focusRequest = null
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
        resumeAfterTransientFocusLoss = false
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
        abandonAudioFocus()
        currentSpans = emptyList()
        currentBodyLength = 0
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        mutableSleepTimer.value = null
    }

    private fun Throwable.listenMessage(): String = when (this) {
        is AiClientException -> message ?: "语音合成失败"
        is IllegalArgumentException -> message ?: "语音合成失败"
        else -> "朗读中断：${message ?: javaClass.simpleName}"
    }

    private companion object {
        /** 兜底超时：整章批量朗读也远小于 30 分钟；正常路径靠显式 release。 */
        const val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
        const val SLEEP_TIMER_TICK_MS = 1_000L
        const val FADE_DURATION_MS = 3_000L
        const val FADE_STEPS = 12
        const val RETRY_BASE_DELAY_MS = 400L
    }
}
