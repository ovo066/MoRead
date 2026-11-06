package com.mozhi.reader.feature.reader.stage

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.mozhi.reader.core.datastore.StageSettings
import com.mozhi.reader.core.stage.StageAtmosphere
import com.mozhi.reader.core.stage.sound.StageSoundCue
import com.mozhi.reader.core.stage.sound.StageSoundMatcher
import com.mozhi.reader.core.stage.sound.StageSoundScene

/**
 * 舞台的声音：只有打开声景时才建播放器（不开就不占音频焦点、不起线程）；
 * 按当前场景换环境声与配乐，转发开章水滴、雷声、心跳、冲击这些提示音。退到后台时停，回来接着放。
 */
@Stable
internal class StageSoundController(private val context: Context) {
    private var player: StageSoundPlayer? = null
    private var enabled = false
    private var cues = true
    private var resumed = true
    private var scene = StageSoundScene()
    private var ambience = 0.7f
    private var music = 0.45f
    private val handler = Handler(Looper.getMainLooper())

    fun configure(enabled: Boolean, cues: Boolean, ambience: Float, music: Float) {
        this.cues = cues
        this.ambience = ambience
        this.music = music
        player?.setVolumes(ambience, music)
        if (enabled != this.enabled) {
            this.enabled = enabled
            refresh()
        }
    }

    fun setScene(next: StageSoundScene) {
        scene = next
        player?.setScene(next)
    }

    fun onResume() {
        resumed = true
        refresh()
    }

    fun onPause() {
        resumed = false
        refresh()
    }

    fun cue(cue: StageSoundCue) {
        if (enabled && cues && resumed) player?.trigger(cue)
    }

    /** 闪电之后隔一会儿才响雷。 */
    fun thunder(delayMillis: Long) {
        handler.postDelayed({ cue(StageSoundCue.THUNDER) }, delayMillis.coerceAtLeast(0L))
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        // 播放器释放要等音频线程写完最后一块（最多一秒多），放到后台线程，退出演出不卡主线程。
        val p = player ?: return
        player = null
        Thread({ p.release() }, "MoRead-stage-audio-release").start()
    }

    private fun refresh() {
        if (enabled && resumed) {
            val p = player ?: StageSoundPlayer(context).also { player = it }
            p.setVolumes(ambience, music)
            p.setScene(scene)
            p.start()
        } else {
            player?.stop()
        }
    }
}

@Composable
internal fun rememberStageSound(): StageSoundController {
    val context = LocalContext.current
    val controller = remember { StageSoundController(context.applicationContext) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.release()
        }
    }
    return controller
}

/** 设置与场景 → 声音：开关、音量随设置；环境声与配乐随当前拍所在的场景（不前瞻，与布景同一份场景）。 */
@Composable
internal fun StageSoundBinding(sound: StageSoundController, settings: StageSettings, chapter: StageChapter, index: Int) {
    LaunchedEffect(sound, settings.sound, settings.soundCues, settings.ambienceVolume, settings.musicVolume) {
        sound.configure(settings.sound, settings.soundCues, settings.ambienceVolume, settings.musicVolume)
    }
    val scene = remember(chapter.script, index) { StageAtmosphere.sceneAt(chapter.script.scenes, index) }
    val soundScene = remember(scene, chapter.script.era) { StageSoundMatcher.of(scene, chapter.script.era) }
    LaunchedEffect(sound, soundScene) { sound.setScene(soundScene) }
}
