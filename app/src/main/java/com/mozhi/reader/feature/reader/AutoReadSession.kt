package com.mozhi.reader.feature.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mozhi.reader.core.datastore.AutoReadSettings
import com.mozhi.reader.core.datastore.PageMode

internal enum class AutoReadPhase { OFF, PREPARING, RUNNING, PAUSED }

internal enum class AutoReadPauseReason(val label: String) {
    TOUCH("触摸后已暂停"),
    BACKGROUND("离开阅读页后已暂停"),
    MENU("操作面板打开，已暂停"),
    SPEECH("听书或语音播放期间暂停"),
    NAVIGATION("阅读位置或排版改变，已暂停"),
    END("已到全书末尾"),
    ERROR("正文暂不可用，已暂停")
}

/** Main-thread session. Every interruption invalidates outstanding turns, with no auto-resume. */
@Stable
internal class AutoReadSession {
    var phase by mutableStateOf(AutoReadPhase.OFF)
        private set
    var settings by mutableStateOf(AutoReadSettings())
        private set
    var reason by mutableStateOf<AutoReadPauseReason?>(null)
        private set
    var generation by mutableIntStateOf(0)
        private set

    val running: Boolean get() = phase == AutoReadPhase.RUNNING
    val engaged: Boolean get() = running || phase == AutoReadPhase.PREPARING

    fun start(value: AutoReadSettings) {
        generation++
        settings = value.normalized()
        reason = null
        phase = AutoReadPhase.PREPARING
    }

    fun onReady(mode: PageMode) {
        if (phase == AutoReadPhase.PREPARING && settings.mode == mode) phase = AutoReadPhase.RUNNING
    }

    fun pause(cause: AutoReadPauseReason) {
        if (!engaged) return
        generation++
        reason = cause
        phase = AutoReadPhase.PAUSED
    }

    fun stop() {
        generation++
        phase = AutoReadPhase.OFF
        reason = null
    }

    fun owns(token: Int): Boolean = running && generation == token
}

/** A stalled UI never catches up by jumping past a screen of unread text. */
internal class AutoReadFrameClock {
    private var lastFrame: Long? = null

    fun distanceDp(frameNanos: Long, speed: Float): Float {
        val previous = lastFrame
        lastFrame = frameNanos
        if (previous == null || !speed.isFinite() || speed <= 0f) return 0f
        val elapsed = (frameNanos - previous).coerceIn(0L, 100_000_000L)
        return speed * elapsed / 1_000_000_000f
    }
}
