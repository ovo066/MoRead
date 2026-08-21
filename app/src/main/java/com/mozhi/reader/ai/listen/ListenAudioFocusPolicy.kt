package com.mozhi.reader.ai.listen

import android.media.AudioManager

internal enum class ListenAudioFocusAction {
    NONE,
    PAUSE,
    PAUSE_AND_RESUME,
    RESUME
}

internal fun decideListenAudioFocusAction(
    change: Int,
    isPlaying: Boolean,
    resumePending: Boolean
): ListenAudioFocusAction = when (change) {
    AudioManager.AUDIOFOCUS_GAIN -> {
        if (resumePending) ListenAudioFocusAction.RESUME else ListenAudioFocusAction.NONE
    }
    AudioManager.AUDIOFOCUS_LOSS -> ListenAudioFocusAction.PAUSE
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
        if (isPlaying) ListenAudioFocusAction.PAUSE_AND_RESUME else ListenAudioFocusAction.NONE
    }
    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> ListenAudioFocusAction.NONE
    else -> ListenAudioFocusAction.NONE
}
