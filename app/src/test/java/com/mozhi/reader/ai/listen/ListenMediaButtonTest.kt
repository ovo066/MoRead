package com.mozhi.reader.ai.listen

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ListenMediaButtonTest {

    @Test
    fun singleButtonHeadsetTogglesByCurrentState() {
        assertEquals(
            ListenMediaAction.PAUSE,
            decodeListenMediaButton(KeyEvent.KEYCODE_HEADSETHOOK, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.PLAY,
            decodeListenMediaButton(KeyEvent.KEYCODE_HEADSETHOOK, isPlaying = false)
        )
        assertEquals(
            ListenMediaAction.PAUSE,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, isPlaying = true)
        )
    }

    @Test
    fun explicitKeysIgnorePlaybackState() {
        assertEquals(
            ListenMediaAction.PLAY,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.PAUSE,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_PAUSE, isPlaying = false)
        )
        assertEquals(
            ListenMediaAction.STOP,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_STOP, isPlaying = true)
        )
    }

    @Test
    fun trackKeysMoveByChapterAndSeekKeysBySentence() {
        assertEquals(
            ListenMediaAction.NEXT_CHAPTER,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.PREVIOUS_CHAPTER,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.NEXT_SENTENCE,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.PREVIOUS_SENTENCE,
            decodeListenMediaButton(KeyEvent.KEYCODE_MEDIA_REWIND, isPlaying = true)
        )
    }

    @Test
    fun unrelatedKeysAreNotConsumed() {
        assertEquals(
            ListenMediaAction.NONE,
            decodeListenMediaButton(KeyEvent.KEYCODE_VOLUME_UP, isPlaying = true)
        )
        assertEquals(
            ListenMediaAction.NONE,
            decodeListenMediaButton(KeyEvent.KEYCODE_UNKNOWN, isPlaying = false)
        )
    }
}
