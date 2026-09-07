package com.mozhi.reader.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

data class ProactiveAnnotationQuotaState(
    val epochDay: Long,
    val attemptedChapters: Set<String> = emptySet(),
    val annotationCount: Int = 0,
    val voiceCount: Int = 0,
    val imageCount: Int = 0
)

data class ProactiveAnnotationAllowance(
    val accepted: Boolean,
    val maxAnnotations: Int = 0,
    /** 写进提示词的下限请求；配额层不做强制。 */
    val minAnnotations: Int = 0,
    val maxVoice: Int = 0,
    val maxImages: Int = 0
) {
    val annotationsUnlimited: Boolean get() = maxAnnotations == Int.MAX_VALUE
}

internal fun evaluateProactiveAnnotationQuota(
    state: ProactiveAnnotationQuotaState,
    today: Long,
    chapterKey: String,
    limits: ProactiveAnnotationLimits,
    requestVoice: Boolean,
    requestImages: Boolean
): Pair<ProactiveAnnotationQuotaState, ProactiveAnnotationAllowance> {
    val effective = limits.normalized()
    val current = if (state.epochDay == today) state else ProactiveAnnotationQuotaState(today)
    val dailyExhausted = !effective.dailyUnlimited && current.annotationCount >= effective.dailyMax
    if (chapterKey in current.attemptedChapters || dailyExhausted) {
        return current to ProactiveAnnotationAllowance(accepted = false)
    }
    val allowance = ProactiveAnnotationAllowance(
        accepted = true,
        maxAnnotations = remaining(
            unlimited = effective.dailyUnlimited,
            cap = effective.dailyMax,
            used = current.annotationCount,
            perChapter = if (effective.chapterUnlimited) Int.MAX_VALUE else effective.maxPerChapter
        ),
        minAnnotations = effective.minPerChapter,
        maxVoice = if (requestVoice) {
            remaining(effective.dailyVoiceMax == ProactiveAnnotationLimits.UNLIMITED, effective.dailyVoiceMax, current.voiceCount)
        } else {
            0
        },
        maxImages = if (requestImages) {
            remaining(effective.dailyImageMax == ProactiveAnnotationLimits.UNLIMITED, effective.dailyImageMax, current.imageCount)
        } else {
            0
        }
    )
    return current.copy(attemptedChapters = current.attemptedChapters + chapterKey) to allowance
}

private fun remaining(
    unlimited: Boolean,
    cap: Int,
    used: Int,
    perChapter: Int = Int.MAX_VALUE
): Int {
    val left = if (unlimited) Int.MAX_VALUE else (cap - used).coerceAtLeast(0)
    return minOf(left, perChapter)
}

@Singleton
class ProactiveAnnotationQuota @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    suspend fun reserve(
        bookId: Long,
        chapterIndex: Int,
        limits: ProactiveAnnotationLimits,
        requestVoice: Boolean,
        requestImages: Boolean,
        today: Long = LocalDate.now().toEpochDay()
    ): ProactiveAnnotationAllowance {
        val chapterKey = "$bookId:$chapterIndex"
        var result = ProactiveAnnotationAllowance(false)
        dataStore.edit { preferences ->
            val state = ProactiveAnnotationQuotaState(
                epochDay = preferences[DAY] ?: today,
                attemptedChapters = preferences[ATTEMPTED].orEmpty(),
                annotationCount = preferences[ANNOTATIONS] ?: 0,
                voiceCount = preferences[VOICES] ?: 0,
                imageCount = preferences[IMAGES] ?: 0
            )
            val (updated, allowance) = evaluateProactiveAnnotationQuota(
                state = state,
                today = today,
                chapterKey = chapterKey,
                limits = limits,
                requestVoice = requestVoice,
                requestImages = requestImages
            )
            result = allowance
            preferences[DAY] = updated.epochDay
            preferences[ATTEMPTED] = updated.attemptedChapters
            preferences[ANNOTATIONS] = updated.annotationCount
            preferences[VOICES] = updated.voiceCount
            preferences[IMAGES] = updated.imageCount
        }
        return result
    }

    suspend fun recordCreated(
        annotations: Int,
        voices: Int,
        images: Int,
        today: Long = LocalDate.now().toEpochDay()
    ) {
        dataStore.edit { preferences ->
            if ((preferences[DAY] ?: today) != today) {
                preferences[DAY] = today
                preferences[ATTEMPTED] = emptySet()
                preferences[ANNOTATIONS] = 0
                preferences[VOICES] = 0
                preferences[IMAGES] = 0
            }
            // 计数只累加、按天归零。旧版本会 coerceAtMost 到硬编码的 10/3/3——
            // 限额一旦可配，那种夹值就是错的。
            preferences[ANNOTATIONS] = (preferences[ANNOTATIONS] ?: 0) + annotations
            preferences[VOICES] = (preferences[VOICES] ?: 0) + voices
            preferences[IMAGES] = (preferences[IMAGES] ?: 0) + images
        }
    }

    suspend fun snapshot(today: Long = LocalDate.now().toEpochDay()): ProactiveAnnotationQuotaState {
        val preferences = dataStore.data.first()
        if ((preferences[DAY] ?: today) != today) return ProactiveAnnotationQuotaState(today)
        return ProactiveAnnotationQuotaState(
            epochDay = today,
            attemptedChapters = preferences[ATTEMPTED].orEmpty(),
            annotationCount = preferences[ANNOTATIONS] ?: 0,
            voiceCount = preferences[VOICES] ?: 0,
            imageCount = preferences[IMAGES] ?: 0
        )
    }

    private companion object {
        val DAY = longPreferencesKey("proactive_annotation_epoch_day")
        val ATTEMPTED = stringSetPreferencesKey("proactive_annotation_attempted_chapters")
        val ANNOTATIONS = intPreferencesKey("proactive_annotation_daily_count")
        val VOICES = intPreferencesKey("proactive_annotation_daily_voice_count")
        val IMAGES = intPreferencesKey("proactive_annotation_daily_image_count")
    }
}
