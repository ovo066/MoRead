package com.mozhi.reader.ai.listen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mozhi.reader.MainActivity
import com.mozhi.reader.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 听书前台服务：让熄屏/退到后台时朗读继续，通知栏提供 暂停/继续、下一章、停止。
 * 播放逻辑全部在 [ListenEngine]；服务只负责前台生命周期与通知展示。
 */
@AndroidEntryPoint
class ListenService : Service() {

    @Inject
    lateinit var engine: ListenEngine

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(engine.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        serviceScope.launch {
            engine.state.collect { state ->
                if (state == null) {
                    ServiceCompat.stopForeground(this@ListenService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> engine.toggle()
            ACTION_NEXT_CHAPTER -> engine.nextChapter()
            ACTION_STOP -> engine.stop()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
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
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
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
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
        if (state != null) {
            builder.addAction(
                if (state.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (state.isPlaying) "暂停" else "继续",
                actionIntent(ACTION_TOGGLE, 1)
            )
            builder.addAction(
                android.R.drawable.ic_media_next,
                "下一章",
                actionIntent(ACTION_NEXT_CHAPTER, 2)
            )
        }
        builder.addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "停止",
            actionIntent(ACTION_STOP, 3)
        )
        return builder.build()
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
        private const val ACTION_TOGGLE = "com.mozhi.reader.listen.TOGGLE"
        private const val ACTION_NEXT_CHAPTER = "com.mozhi.reader.listen.NEXT_CHAPTER"
        private const val ACTION_STOP = "com.mozhi.reader.listen.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ListenService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenService::class.java))
        }
    }
}
