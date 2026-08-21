package com.mozhi.reader.ai.listen

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenAudioFocusPolicyTest {
    @Test
    fun transientLossPausesAndResumesOnGain() {
        assertEquals(
            ListenAudioFocusAction.PAUSE_AND_RESUME,
            decideListenAudioFocusAction(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                isPlaying = true,
                resumePending = false
            )
        )
        assertEquals(
            ListenAudioFocusAction.RESUME,
            decideListenAudioFocusAction(
                AudioManager.AUDIOFOCUS_GAIN,
                isPlaying = false,
                resumePending = true
            )
        )
    }

    @Test
    fun duckingDoesNotPermanentlyPausePlayback() {
        assertEquals(
            ListenAudioFocusAction.NONE,
            decideListenAudioFocusAction(
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
                isPlaying = true,
                resumePending = false
            )
        )
    }

    @Test
    fun permanentLossPausesWithoutAutomaticResume() {
        assertEquals(
            ListenAudioFocusAction.PAUSE,
            decideListenAudioFocusAction(
                AudioManager.AUDIOFOCUS_LOSS,
                isPlaying = true,
                resumePending = true
            )
        )
    }
}
