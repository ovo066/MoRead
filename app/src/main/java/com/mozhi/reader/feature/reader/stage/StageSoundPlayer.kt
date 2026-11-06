package com.mozhi.reader.feature.reader.stage

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.mozhi.reader.core.stage.sound.StageSoundCue
import com.mozhi.reader.core.stage.sound.StageSoundScene
import com.mozhi.reader.core.stage.sound.StageSynth
import java.util.concurrent.atomic.AtomicIntegerArray

/** 生命周期与焦点只写控制状态，AudioTrack 和合成器始终由音频线程独占。 */
internal class StageSoundPlayer(context: Context) {
    private val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wake = Object()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
    @Volatile private var wanted = false
    @Volatile private var focused = false
    @Volatile private var duck = false
    @Volatile private var released = false
    @Volatile private var playing = false
    @Volatile private var scene = StageSoundScene()
    private data class Volumes(val ambience: Float, val music: Float)
    @Volatile private var volumes = Volumes(1f, 1f)
    private val pending = AtomicIntegerArray(StageSoundCue.entries.size)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> { focused = true; duck = false }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> { focused = true; duck = true }
                AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { focused = false; duck = false }
            }
            synchronized(wake) { wake.notifyAll() }
        }, Handler(Looper.getMainLooper())).build()
    private val thread = Thread(::playLoop, "MoRead-stage-audio").apply { isDaemon = true; start() }

    val isPlaying: Boolean get() = playing

    @Synchronized fun start() {
        if (released) return
        wanted = true
        if (!focused) focused = manager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        synchronized(wake) { wake.notifyAll() }
    }

    @Synchronized fun stop() {
        wanted = false
        manager.abandonAudioFocusRequest(focus)
        focused = false; duck = false
        synchronized(wake) { wake.notifyAll() }
    }

    @Synchronized fun release() {
        if (released) return
        wanted = false; released = true
        manager.abandonAudioFocusRequest(focus)
        synchronized(wake) { wake.notifyAll() }
        // 一块阻塞写最多约 32 ms；等待线程退出后才允许资源生命周期结束。
        thread.join(1500)
    }

    fun setScene(scene: StageSoundScene) { this.scene = scene.copy(layers = scene.layers.toMap()) }
    fun setVolumes(ambience: Float, music: Float) { volumes = Volumes(ambience, music) }
    fun trigger(cue: StageSoundCue) {
        if (released) return
        // 饱和计数限制后台积压，调用方与渲染线程都不需要持有队列锁。
        val index = cue.ordinal
        while (true) {
            val before = pending.get(index)
            if (before >= 8 || pending.compareAndSet(index, before, before + 1)) break
        }
    }

    private fun playLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val synth = StageSynth(RATE)
        val buffer = FloatArray(FRAMES * 2)
        var track: AudioTrack? = null
        var active = false
        var gain = 0f
        var appliedScene: StageSoundScene? = null
        var appliedVolumes: Volumes? = null
        var writtenSamples = 0L
        var headLast = 0L
        var headWrap = 0L
        val gainStep = 1f / (RATE * .4f)
        try {
            while (!released) {
                if ((!wanted || !focused) && gain == 0f) {
                    if (active) {
                        // 写入有缓冲提前量，先让最后的淡出真正播完，再暂停与清队列。
                        val deadline = System.nanoTime() + 500_000_000L
                        while (!released && (!wanted || !focused) && System.nanoTime() < deadline) {
                            val rawHead = requireNotNull(track).playbackHeadPosition.toLong() and 0xffffffffL
                            if (rawHead < headLast) headWrap += 0x100000000L
                            headLast = rawHead
                            if (rawHead + headWrap >= writtenSamples / 2) break
                            synchronized(wake) { wake.wait(5) }
                        }
                        if (!released && wanted && focused) continue
                        track?.pause(); track?.flush(); active = false
                        writtenSamples = 0L; headLast = 0L; headWrap = 0L
                    }
                    playing = false
                    synchronized(wake) { while (!released && (!wanted || !focused)) wake.wait() }
                    continue
                }
                if (track == null) {
                    val minimum = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
                    check(minimum > 0)
                    track = AudioTrack.Builder().setAudioAttributes(attributes)
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                        .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(maxOf(minimum * 4, FRAMES * 8 * 4)).build()
                    check(track.state == AudioTrack.STATE_INITIALIZED)
                }
                if (!active) { track.play(); active = true }
                val nextScene = scene
                if (nextScene !== appliedScene) { synth.setScene(nextScene); appliedScene = nextScene }
                val nextVolumes = volumes
                if (nextVolumes !== appliedVolumes) { synth.setVolumes(nextVolumes.ambience, nextVolumes.music); appliedVolumes = nextVolumes }
                for (i in StageSoundCue.entries.indices) {
                    val count = pending.getAndSet(i, 0)
                    repeat(count) { synth.trigger(StageSoundCue.entries[i]) }
                }
                synth.render(buffer, FRAMES)
                val target = if (wanted && focused) { if (duck) .3f else 1f } else 0f
                for (i in 0 until FRAMES) {
                    gain = when {
                        gain < target -> minOf(target, gain + gainStep)
                        gain > target -> maxOf(target, gain - gainStep)
                        else -> gain
                    }
                    buffer[i * 2] *= gain; buffer[i * 2 + 1] *= gain
                }
                playing = gain > 0f
                var offset = 0
                while (offset < buffer.size && !released) {
                    val written = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0)
                    offset += written
                    writtenSamples += written
                }
            }
        } catch (_: RuntimeException) {
            // 输出设备失效时停止，避免高优先级线程忙等或带着无效句柄继续写。
            wanted = false
            focused = false
            released = true
            manager.abandonAudioFocusRequest(focus)
        } finally {
            playing = false
            track?.let { output ->
                try {
                    if (output.state == AudioTrack.STATE_INITIALIZED) { output.pause(); output.flush() }
                } finally { output.release() }
            }
        }
    }

    private companion object { const val RATE = 32_000; const val FRAMES = 1024 }
}
