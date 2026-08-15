package com.mozhi.reader.core.importer.lan

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
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 传书前台服务：熄屏或切到别的应用时也别把正在传的书掐断。
 * 监听逻辑全在 [LanBookServer]，这里只管前台生命周期与通知。
 */
@AndroidEntryPoint
class LanTransferService : Service() {

    @Inject
    lateinit var server: LanBookServer

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        server.start()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(server.state.value),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        serviceScope.launch {
            server.state.collect { state ->
                if (!state.running) {
                    ServiceCompat.stopForeground(
                        this@LanTransferService,
                        ServiceCompat.STOP_FOREGROUND_REMOVE
                    )
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) server.stop()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(state: LanServerState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val incoming = state.incoming
        val text = when {
            incoming != null && incoming.totalBytes > 0 ->
                "正在接收 ${incoming.name} · ${percentOf(incoming)}%"
            incoming != null -> "正在接收 ${incoming.name}"
            state.received.isNotEmpty() -> "已接收 ${state.received.size} 个文件"
            else -> state.address ?: "等待电脑连接"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("局域网传书进行中")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "停止服务",
                PendingIntent.getService(
                    this,
                    1,
                    Intent(this, LanTransferService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    private fun percentOf(incoming: LanIncomingFile): Int =
        ((incoming.receivedBytes * 100) / incoming.totalBytes.coerceAtLeast(1)).toInt().coerceIn(0, 100)

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "局域网传书",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "传书服务开启期间常驻，可从这里停止"
                setShowBadge(false)
            }
        )
    }

    companion object {
        private const val CHANNEL_ID = "lan-transfer"
        private const val NOTIFICATION_ID = 0x4C41
        private const val ACTION_STOP = "com.mozhi.reader.lan.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LanTransferService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LanTransferService::class.java))
        }
    }
}
