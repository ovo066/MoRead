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
    val maxVoice: Int = 0,
    val maxImages: Int = 0
)

internal fun evaluateProactiveAnnotationQuota(
    state: ProactiveAnnotationQuotaState,
    today: Long,
    chapterKey: String,
    requestVoice: Boolean,
    requestImages: Boolean
): Pair<ProactiveAnnotationQuotaState, ProactiveAnnotationAllowance> {
    val current = if (state.epochDay == today) state else ProactiveAnnotationQuotaState(today)
    if (chapterKey in current.attemptedChapters || current.annotationCount >= 10) {
        return current to ProactiveAnnotationAllowance(accepted = false)
    }
    val allowance = ProactiveAnnotationAllowance(
        accepted = true,
        maxAnnotations = (10 - current.annotationCount).coerceAtMost(2),
        maxVoice = if (requestVoice) (3 - current.voiceCount).coerceAtLeast(0) else 0,
        maxImages = if (requestImages) (3 - current.imageCount).coerceAtLeast(0) else 0
    )
    return current.copy(attemptedChapters = current.attemptedChapters + chapterKey) to allowance
}

@Singleton
class ProactiveAnnotationQuota @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    suspend fun reserve(
        bookId: Long,
        chapterIndex: Int,
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
            preferences[ANNOTATIONS] = ((preferences[ANNOTATIONS] ?: 0) + annotations).coerceAtMost(10)
            preferences[VOICES] = ((preferences[VOICES] ?: 0) + voices).coerceAtMost(3)
            preferences[IMAGES] = ((preferences[IMAGES] ?: 0) + images).coerceAtMost(3)
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
