package com.mozhi.reader.ai.listen

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * 听书期间持续输出静音的保活轨——这是「耳机键控制不了听书」的真正修法。
 *
 * 系统把媒体按键只发给 `MediaSessionService` 选出的 media button session，而它挑会话时会遍历
 * 「近期真的在出声的 UID」（AudioPlayerStateMonitor）。用系统 TTS 时声音由 TTS 引擎进程发出，
 * 我们自己的 UID 全程不出声，于是我们永远当不上 media button session：锁屏那套走
 * MediaController 的 transport control，照常可用，但耳机键根本送不到我们这儿。
 * 让本进程持续写一路静音，系统就会认定我们在播放，按键随之回到我们的会话。
 *
 * 纪律：**暂停时不要停这条轨**。暂停期间一旦不出声就会立刻丢掉 media button session，
 * 结果是「耳机能暂停、不能继续」——比全都不能用更让人困惑。只在会话真正结束时释放。
 * 不申请音频焦点，焦点归 [ListenEngine.configureAudioFocus] 管。
 */
internal class ListenSilenceTrack {

    private var track: AudioTrack? = null
    private var writer: Thread? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start() {
        if (running) return
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return
        val bufferSize = minBuffer * 2
        val created = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            runCatching { created.release() }
            return
        }
        track = created
        running = true
        runCatching { created.play() }
        // 写零：阻塞式 write 自带节流，不需要额外 sleep。
        val silence = ShortArray(bufferSize / 4)
        writer = Thread({
            while (running) {
                val written = runCatching {
                    created.write(silence, 0, silence.size)
                }.getOrDefault(AudioTrack.ERROR)
                if (written < 0) break
            }
        }, "listen-silence").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        writer = null
        val current = track ?: return
        track = null
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.stop() }
        runCatching { current.release() }
    }

    private companion object {
        /** 静音轨只为「让系统看见我们在出声」，取最低常见采样率即可。 */
        const val SAMPLE_RATE = 8_000
    }
}
