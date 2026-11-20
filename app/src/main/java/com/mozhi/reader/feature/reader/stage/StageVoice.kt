package com.mozhi.reader.feature.reader.stage

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mozhi.reader.core.stage.StageVoiceClip
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 配音跟读的播放器：一次只放一拍的那几段，翻到下一拍就停掉上一拍没放完的。
 *
 * 不另抢音频焦点——声景打开时舞台已经持有焦点，配音与环境声一起出；退到后台立刻停，
 * 回来不自动接着放（读者回来时多半已经忘了上一句说到哪儿）。
 */
@Stable
internal class StageVoiceController(private val context: Context) {
    private var player: MediaPlayer? = null
    private var resumed = true

    /** 依次放完 [clips]；被取消、退到后台或文件坏了就提前结束。 */
    suspend fun play(clips: List<StageVoiceClip>) {
        for (clip in clips) {
            if (!resumed) return
            if (!File(clip.path).isFile) continue
            if (!playOne(clip.path)) return
        }
    }

    fun onPause() {
        resumed = false
        stop()
    }

    fun onResume() {
        resumed = true
    }

    fun stop() {
        player?.runCatching { stop() }
        player?.runCatching { release() }
        player = null
    }

    private suspend fun playOne(path: String): Boolean = suspendCancellableCoroutine { continuation ->
        val media = MediaPlayer()
        player = media
        media.setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        )
        fun finish(ok: Boolean) {
            media.runCatching { release() }
            if (player === media) player = null
            if (continuation.isActive) continuation.resume(ok)
        }
        media.setOnPreparedListener { it.start() }
        media.setOnCompletionListener { finish(true) }
        media.setOnErrorListener { _, _, _ -> finish(false); true }
        continuation.invokeOnCancellation {
            media.runCatching { release() }
            if (player === media) player = null
        }
        try {
            media.setDataSource(path)
            media.prepareAsync()
        } catch (_: Exception) {
            finish(false)
        }
    }
}

@Composable
internal fun rememberStageVoice(): StageVoiceController {
    val context = LocalContext.current
    val controller = remember { StageVoiceController(context.applicationContext) }
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
            controller.stop()
        }
    }
    return controller
}
