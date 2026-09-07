package com.mozhi.reader.ai.listen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * 媒体按键的兜底接收路径。系统在「当前没有 media button session」时会退回广播这条路
 * （会话上通过 setMediaButtonBroadcastReceiver / setMediaButtonReceiver 登记的那个），
 * 所以静音保活轨之外再留这一手，耳机键就不会整条链路都指望同一个机制。
 */
class ListenMediaButtonReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = @Suppress("DEPRECATION")
        intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        ListenService.dispatchMediaKey(context, event.keyCode)
    }
}
