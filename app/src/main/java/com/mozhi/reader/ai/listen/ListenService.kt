package com.mozhi.reader.ai.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mozhi.reader.MainActivity
import com.mozhi.reader.R
import com.mozhi.reader.core.library.LibraryRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 前台媒体服务：维持后台朗读，并向锁屏、耳机、车机和手表发布播放控制。 */
@AndroidEntryPoint
class ListenService : Service() {

    @Inject
    lateinit var engine: ListenEngine

    @Inject
    lateinit var libraryRepository: LibraryRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private var restoreJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        mediaSession = MediaSession(this, "MoReadListen").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = engine.resume()
                override fun onPause() = engine.pause()
                override fun onStop() = engine.stop()
                override fun onSkipToNext() = engine.nextChapter()
                override fun onSkipToPrevious() = engine.prevChapter()
                override fun onSeekTo(pos: Long) {
                    engine.seekToChapterFraction(
                        (pos / MEDIA_POSITION_RANGE.toFloat()).coerceIn(0f, 1f)
                    )
                }
            })
            isActive = true
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(engine.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        serviceScope.launch {
            engine.state.collect { state ->
                if (state != null) {
                    updateMediaSession(state)
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> restorePlaybackIfNeeded(intent, startId)
            ACTION_TOGGLE -> engine.toggle()
            ACTION_PREVIOUS_CHAPTER -> engine.prevChapter()
            ACTION_NEXT_CHAPTER -> engine.nextChapter()
            ACTION_STOP -> engine.stop()
        }
        return START_REDELIVER_INTENT
    }

    private fun restorePlaybackIfNeeded(intent: Intent, startId: Int) {
        if (engine.isActive || restoreJob?.isActive == true) return
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L)
        if (bookId <= 0L) {
            stopSelfResult(startId)
            return
        }
        val fallbackChapter = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0).coerceAtLeast(0)
        val fallbackOffset = intent.getIntExtra(EXTRA_CHAR_OFFSET, 0).coerceAtLeast(0)
        val playbackMode = runCatching {
            ListenPlaybackMode.valueOf(
                intent.getStringExtra(EXTRA_PLAYBACK_MODE).orEmpty()
            )
        }.getOrDefault(ListenPlaybackMode.STANDARD)
        restoreJob = serviceScope.launch {
            val book = libraryRepository.getBook(bookId)
            if (book == null) {
                stopSelfResult(startId)
                return@launch
            }
            val started = engine.startFromSavedProgress(
                bookId = bookId,
                chapterIndex = book.lastReadChapterIndex.takeIf { it >= 0 } ?: fallbackChapter,
                fallbackOffset = book.lastReadCharOffset.takeIf { it >= 0 } ?: fallbackOffset,
                locatorJson = book.lastReadLocator,
                playbackMode = playbackMode
            )
            if (!started) stopSelfResult(startId)
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: ListenState?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_listen)
            .setContentTitle(state?.bookTitle ?: "墨知听书")
            .setContentText(
                state?.let { current ->
                    current.status ?: current.chapterTitle.ifBlank { "正在朗读" }
                } ?: "正在准备听书…"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        if (state != null) {
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_previous,
                    "上一章",
                    actionIntent(ACTION_PREVIOUS_CHAPTER, 1)
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    if (state.isPlaying) android.R.drawable.ic_media_pause
                    else android.R.drawable.ic_media_play,
                    if (state.isPlaying) "暂停" else "继续",
                    actionIntent(ACTION_TOGGLE, 2)
                ).build()
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_media_next,
                    "下一章",
                    actionIntent(ACTION_NEXT_CHAPTER, 3)
                ).build()
            )
        }
        builder.addAction(
            Notification.Action.Builder(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止",
                actionIntent(ACTION_STOP, 4)
            ).build()
        )
        return builder.build()
    }

    private fun updateMediaSession(state: ListenState) {
        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SEEK_TO
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (state.isPlaying) PlaybackState.STATE_PLAYING
                    else PlaybackState.STATE_PAUSED,
                    (state.chapterProgress * MEDIA_POSITION_RANGE).toLong(),
                    if (state.isPlaying) 1f else 0f
                )
                .build()
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, state.chapterTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.bookTitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, MEDIA_POSITION_RANGE)
                .apply {
                    state.coverPath?.takeIf(String::isNotBlank)?.let { path ->
                        BitmapFactory.decodeFile(path)?.let { cover ->
                            putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                        }
                    }
                }
                .build()
        )
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, ListenService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "听书播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "连续朗读时的播放控制"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "listen-playback"
        private const val NOTIFICATION_ID = 0x4C54
        private const val ACTION_START = "com.mozhi.reader.listen.START"
        private const val ACTION_TOGGLE = "com.mozhi.reader.listen.TOGGLE"
        private const val ACTION_PREVIOUS_CHAPTER = "com.mozhi.reader.listen.PREVIOUS_CHAPTER"
        private const val ACTION_NEXT_CHAPTER = "com.mozhi.reader.listen.NEXT_CHAPTER"
        private const val ACTION_STOP = "com.mozhi.reader.listen.STOP"
        private const val MEDIA_POSITION_RANGE = 100_000L
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_CHAPTER_INDEX = "chapter_index"
        private const val EXTRA_CHAR_OFFSET = "char_offset"
        private const val EXTRA_PLAYBACK_MODE = "playback_mode"

        fun start(
            context: Context,
            bookId: Long,
            chapterIndex: Int,
            charOffset: Int,
            playbackMode: ListenPlaybackMode
        ) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ListenService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_BOOK_ID, bookId)
                    .putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                    .putExtra(EXTRA_CHAR_OFFSET, charOffset)
                    .putExtra(EXTRA_PLAYBACK_MODE, playbackMode.name)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}
