package com.mozhi.reader.ai.audiobook

import android.media.MediaMetadataRetriever
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AudiobookChapterEntity
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.AudiobookSegmentEntity
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.audiobookRevision
import com.mozhi.reader.core.datastore.purifyForListening
import com.mozhi.reader.core.library.AudiobookChapterState
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookRepository
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.speech.TtsSettingsStore
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

data class AudiobookProductionProgress(
    val chapterIndex: Int,
    val chapterTitle: String,
    val completedSegments: Int,
    val totalSegments: Int
)

data class AudiobookProductionSummary(
    val completedSegments: Int,
    val totalSegments: Int,
    val readyChapters: Int
)

@Singleton
class AudiobookProducer @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val audiobookRepository: AudiobookRepository,
    private val readerSettingsRepository: ReaderSettingsRepository,
    private val ttsSettingsStore: TtsSettingsStore,
    private val mediaService: AiMediaGenerationService
) {
    suspend fun produce(
        bookId: Long,
        chapterIndices: List<Int>,
        onProgress: suspend (AudiobookProductionProgress) -> Unit = {}
    ): AudiobookProductionSummary {
        val chapterMap = libraryRepository.getChapters(bookId).associateBy { it.chapterIndex }
        val roles = audiobookRepository.getRoles(bookId).associateBy(AudiobookRoleEntity::id)
        val rules = readerSettingsRepository.settings.first().textReplacementRules
        val settings = ttsSettingsStore.current()
        val selected = chapterIndices.distinct().sorted().mapNotNull(chapterMap::get)
        val totalAiSegments = selected.sumOf { chapter ->
            audiobookRepository.getSegments(bookId, chapter.chapterIndex)
                .count { segment -> roles[segment.roleId]?.engine == AudiobookEngine.AI.name }
        }
        var completedOverall = 0
        var readyChapters = 0

        selected.forEach { chapter ->
            val body = libraryRepository.readChapterText(bookId, chapter)
            val segments = audiobookRepository.getSegments(bookId, chapter.chapterIndex)
            require(segments.isNotEmpty()) { "第 ${chapter.chapterIndex + 1} 章尚未生成剧本" }
            if (segments.any { it.revision != audiobookRevision(body, rules) }) {
                audiobookRepository.markStale(bookId, chapter.chapterIndex)
                error("第 ${chapter.chapterIndex + 1} 章正文已变化，请重新排剧本")
            }
            val current = audiobookRepository.getChapter(bookId, chapter.chapterIndex)
                ?: error("第 ${chapter.chapterIndex + 1} 章尚未确认剧本")
            require(
                current.state in setOf(
                    AudiobookChapterState.CONFIRMED.name,
                    AudiobookChapterState.SYNTHESIZING.name,
                    AudiobookChapterState.READY.name
                )
            ) { "第 ${chapter.chapterIndex + 1} 章剧本尚未确认" }
            val aiSegments = segments.filter { segment ->
                roles[segment.roleId]?.engine == AudiobookEngine.AI.name
            }
            val existingReady = aiSegments.count(AudiobookProducer::hasUsableAudio)
            completedOverall += existingReady
            audiobookRepository.updateChapter(
                current.copy(
                    state = AudiobookChapterState.SYNTHESIZING.name,
                    readySegmentCount = existingReady
                )
            )
            onProgress(
                AudiobookProductionProgress(
                    chapter.chapterIndex,
                    chapter.title,
                    completedOverall,
                    totalAiSegments
                )
            )

            var chapterReady = existingReady
            aiSegments.filterNot(AudiobookProducer::hasUsableAudio).forEach { segment ->
                val role = roles[segment.roleId] ?: return@forEach
                val cleaned = purifyForListening(
                    body,
                    segment.startCharOffset,
                    segment.endCharOffset,
                    rules
                ).text.replace('\uFFFC', ' ').trim()
                val updated = if (cleaned.isEmpty()) {
                    segment.copy(audioPath = "", audioMillis = 0)
                } else {
                    val speech = synthesizeWithRetry(
                        bookId = bookId,
                        text = cleaned,
                        role = role,
                        segment = segment,
                        retryCount = settings.retryCount,
                        speed = settings.aiSpeed,
                        volume = settings.aiVolume,
                        pitch = settings.aiPitch
                    )
                    segment.copy(
                        audioPath = speech.path,
                        audioMillis = mediaDurationMillis(speech.path)
                    )
                }
                audiobookRepository.updateProducedSegment(updated)
                chapterReady += 1
                completedOverall += 1
                audiobookRepository.updateChapter(
                    current.copy(
                        state = AudiobookChapterState.SYNTHESIZING.name,
                        readySegmentCount = chapterReady
                    )
                )
                onProgress(
                    AudiobookProductionProgress(
                        chapter.chapterIndex,
                        chapter.title,
                        completedOverall,
                        totalAiSegments
                    )
                )
            }
            val refreshed = audiobookRepository.getSegments(bookId, chapter.chapterIndex)
            val refreshedAi = refreshed.filter { roles[it.roleId]?.engine == AudiobookEngine.AI.name }
            val readyCount = refreshedAi.count(AudiobookProducer::hasUsableAudio)
            val isReady = readyCount == refreshedAi.size
            audiobookRepository.updateChapter(
                AudiobookChapterEntity(
                    bookId = bookId,
                    chapterIndex = chapter.chapterIndex,
                    state = if (isReady) {
                        AudiobookChapterState.READY.name
                    } else {
                        AudiobookChapterState.SYNTHESIZING.name
                    },
                    scriptedAt = current.scriptedAt,
                    confirmedAt = current.confirmedAt,
                    synthesizedAt = if (isReady) System.currentTimeMillis() else current.synthesizedAt,
                    segmentCount = refreshed.size,
                    readySegmentCount = readyCount,
                    totalMillis = refreshed.sumOf { it.audioMillis.toLong() }
                )
            )
            if (isReady) readyChapters += 1
        }
        return AudiobookProductionSummary(completedOverall, totalAiSegments, readyChapters)
    }

    private suspend fun synthesizeWithRetry(
        bookId: Long,
        text: String,
        role: AudiobookRoleEntity,
        segment: AudiobookSegmentEntity,
        retryCount: Int,
        speed: Float,
        volume: Float,
        pitch: Int
    ): com.mozhi.reader.ai.media.CachedSpeech {
        var lastError: Throwable? = null
        repeat(retryCount.coerceIn(0, 5) + 1) { attempt ->
            try {
                return mediaService.synthesizeSpeech(
                    bookId = bookId,
                    text = text,
                    voiceId = role.voiceId.takeIf(String::isNotBlank),
                    speed = speed.takeIf { it != 1f },
                    volume = volume.takeIf { it != 1f },
                    pitch = pitch.takeIf { it != 0 },
                    emotion = segment.emotion,
                    instruction = segment.instruction
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                lastError = error
                if (attempt < retryCount) delay(RETRY_BASE_DELAY_MS shl attempt)
            }
        }
        throw lastError ?: IllegalStateException("语音合成失败")
    }

    private fun mediaDurationMillis(path: String): Int = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: 0
        }
    }.getOrDefault(0)

    private companion object {
        const val RETRY_BASE_DELAY_MS = 500L

        fun hasUsableAudio(segment: AudiobookSegmentEntity): Boolean = when {
            segment.audioPath == null -> false
            segment.audioPath.isEmpty() -> true
            else -> File(segment.audioPath).isFile
        }
    }
}
