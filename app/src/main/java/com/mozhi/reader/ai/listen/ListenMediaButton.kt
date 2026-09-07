package com.mozhi.reader.ai.listen

import android.view.KeyEvent

/** 耳机/蓝牙/车机按键落到听书上的动作。 */
internal enum class ListenMediaAction {
    NONE,
    PLAY,
    PAUSE,
    TOGGLE,
    NEXT_CHAPTER,
    PREVIOUS_CHAPTER,
    NEXT_SENTENCE,
    PREVIOUS_SENTENCE,
    STOP
}

/**
 * 媒体按键 → 听书动作。自己解码而不用框架默认实现：默认实现要等约 300ms 的双击窗口
 * 才决定单击行为，而且依赖客户端缓存的 PlaybackState，单击「没反应」的观感多半出在那里。
 *
 * 单键耳机（HEADSETHOOK）与 PLAY_PAUSE 一律按当前播放态切换；快进/快退给到句级，
 * 上一曲/下一曲给到章级——听书里「一首歌」的心理单位是一章。
 */
internal fun decodeListenMediaButton(keyCode: Int, isPlaying: Boolean): ListenMediaAction =
    when (keyCode) {
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (isPlaying) {
            ListenMediaAction.PAUSE
        } else {
            ListenMediaAction.PLAY
        }
        KeyEvent.KEYCODE_MEDIA_PLAY -> ListenMediaAction.PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> ListenMediaAction.PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> ListenMediaAction.NEXT_CHAPTER
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> ListenMediaAction.PREVIOUS_CHAPTER
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> ListenMediaAction.NEXT_SENTENCE
        KeyEvent.KEYCODE_MEDIA_REWIND -> ListenMediaAction.PREVIOUS_SENTENCE
        KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_MEDIA_CLOSE -> ListenMediaAction.STOP
        else -> ListenMediaAction.NONE
    }
