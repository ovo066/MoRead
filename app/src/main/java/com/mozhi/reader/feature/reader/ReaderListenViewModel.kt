package com.mozhi.reader.feature.reader

import androidx.lifecycle.ViewModel
import com.mozhi.reader.ai.listen.ListenEngine
import com.mozhi.reader.core.speech.SleepTimerPlan
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** 阅读页的听书门面：全部逻辑在单例 [ListenEngine]，退出阅读页不打断播放。 */
@HiltViewModel
class ReaderListenViewModel @Inject constructor(
    private val engine: ListenEngine
) : ViewModel() {
    val state = engine.state
    val sleepTimer = engine.sleepTimer

    fun start(bookId: Long, chapterIndex: Int, charOffset: Int) =
        engine.start(bookId, chapterIndex, charOffset)

    fun toggle() = engine.toggle()
    fun stop() = engine.stop()
    fun prevSentence() = engine.prevSentence()
    fun nextSentence() = engine.nextSentence()
    fun prevChapter() = engine.prevChapter()
    fun nextChapter() = engine.nextChapter()
    fun seekTo(chapterIndex: Int, charOffset: Int) = engine.seekTo(chapterIndex, charOffset)
    fun setSleepTimer(plan: SleepTimerPlan?) = engine.setSleepTimer(plan)
}
