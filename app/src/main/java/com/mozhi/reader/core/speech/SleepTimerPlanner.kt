package com.mozhi.reader.core.speech

sealed interface SleepTimerPlan {
    data class Minutes(val minutes: Int) : SleepTimerPlan
    data class Chapters(val chapters: Int) : SleepTimerPlan
    data object EndOfChapter : SleepTimerPlan
}

data class SleepTimerState(
    val plan: SleepTimerPlan,
    val remainingMillis: Long? = null,
    val remainingChapters: Int? = null,
    val running: Boolean = true
)

object SleepTimerPlanner {
    fun start(plan: SleepTimerPlan): SleepTimerState = when (plan) {
        is SleepTimerPlan.Minutes -> SleepTimerState(
            plan = plan,
            remainingMillis = plan.minutes.coerceAtLeast(1) * 60_000L
        )
        is SleepTimerPlan.Chapters -> SleepTimerState(
            plan = plan,
            remainingChapters = plan.chapters.coerceAtLeast(1)
        )
        SleepTimerPlan.EndOfChapter -> SleepTimerState(plan, remainingChapters = 1)
    }

    fun tick(state: SleepTimerState, elapsedMillis: Long, playing: Boolean): SleepTimerState {
        if (!playing || !state.running || state.remainingMillis == null) return state
        return state.copy(remainingMillis = (state.remainingMillis - elapsedMillis).coerceAtLeast(0))
    }

    fun onChapterCompleted(state: SleepTimerState): SleepTimerState {
        val remaining = state.remainingChapters ?: return state
        return state.copy(remainingChapters = (remaining - 1).coerceAtLeast(0))
    }

    fun isExpired(state: SleepTimerState): Boolean = if (state.remainingMillis != null) {
        state.remainingMillis <= 0L
    } else {
        (state.remainingChapters ?: 1) <= 0
    }

    fun label(state: SleepTimerState): String = when {
        state.remainingMillis != null -> {
            val totalSeconds = state.remainingMillis / 1_000
            "%02d:%02d".format(totalSeconds / 60, totalSeconds % 60)
        }
        state.remainingChapters != null -> "还剩 ${state.remainingChapters} 章"
        else -> "定时"
    }
}
