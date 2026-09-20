package com.mozhi.reader.feature.reader

import android.view.KeyEvent
import android.view.Window
import com.mozhi.reader.core.datastore.ReaderKeyAction
import com.mozhi.reader.core.datastore.ReaderKeyBinding
import com.mozhi.reader.core.datastore.ReaderPhysicalKey

internal fun KeyEvent.readerPhysicalKey(): ReaderPhysicalKey? {
    if (flags and KeyEvent.FLAG_SOFT_KEYBOARD != 0) return null
    return ReaderPhysicalKey(keyCode, KeyEvent.normalizeMetaState(metaState), scanCode).normalized()
}

internal fun ReaderPhysicalKey.displayName(): String {
    val base = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> "音量加"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "音量减"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "静音"
        KeyEvent.KEYCODE_PAGE_UP -> "Page Up"
        KeyEvent.KEYCODE_PAGE_DOWN -> "Page Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "方向左"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "方向右"
        KeyEvent.KEYCODE_DPAD_UP -> "方向上"
        KeyEvent.KEYCODE_DPAD_DOWN -> "方向下"
        KeyEvent.KEYCODE_DPAD_CENTER -> "方向确认"
        KeyEvent.KEYCODE_SPACE -> "空格"
        KeyEvent.KEYCODE_ENTER -> "回车"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "媒体下一曲"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "媒体上一曲"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放 / 暂停"
        KeyEvent.KEYCODE_HEADSETHOOK -> "耳机按键"
        KeyEvent.KEYCODE_CAMERA -> "相机按键"
        KeyEvent.KEYCODE_UNKNOWN -> "物理按键 $scanCode"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A'.code + keyCode - KeyEvent.KEYCODE_A).toChar().toString()
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> (keyCode - KeyEvent.KEYCODE_0).toString()
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").replace('_', ' ')
    }
    return listOfNotNull(
        "Ctrl".takeIf { modifiers and KeyEvent.META_CTRL_ON != 0 },
        "Alt".takeIf { modifiers and KeyEvent.META_ALT_ON != 0 },
        "Shift".takeIf { modifiers and KeyEvent.META_SHIFT_ON != 0 },
        "Meta".takeIf { modifiers and KeyEvent.META_META_ON != 0 }, base
    ).joinToString(" + ")
}

/** A recorder owns only its dialog window, and releases it before Save or Cancel dismisses it. */
internal class ReaderKeyCaptureWindow {
    private var window: Window? = null
    private var previous: Window.Callback? = null
    private var installed: Window.Callback? = null
    private val session = ReaderKeyCaptureSession()

    fun attach(target: Window, captured: (ReaderPhysicalKey, Boolean) -> Unit) {
        close()
        val delegate = target.callback ?: return
        val callback = object : Window.Callback by delegate {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean = session.dispatch(event, captured) || delegate.dispatchKeyEvent(event)
        }
        window = target
        previous = delegate
        installed = callback
        target.callback = callback
    }

    fun close() {
        val target = window
        if (target != null && target.callback === installed) target.callback = previous
        window = null
        previous = null
        installed = null
    }
}

/** Own both halves of a handled press, even if the menu or bindings change before key-up. */
internal class ReaderHardwareKeyDispatcher {
    private data class Press(val device: Int, val code: Int, val scan: Int, val downTime: Long)
    private val claimed = mutableSetOf<Press>()

    fun dispatch(event: KeyEvent, enabled: Boolean, inputAllowed: Boolean, bindings: List<ReaderKeyBinding>,
        turn: (ReaderKeyAction) -> Unit): Boolean {
        val press = Press(event.deviceId, event.keyCode, event.scanCode, event.downTime)
        if (event.action == KeyEvent.ACTION_UP) return claimed.remove(press)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (press in claimed) return true
        if (!enabled || !inputAllowed || event.repeatCount != 0 || event.isCanceled) return false
        val key = event.readerPhysicalKey() ?: return false
        val binding = bindings.firstOrNull { it.key == key } ?: return false
        claimed.removeAll { it.device == press.device && it.code == press.code && it.scan == press.scan }
        claimed += press
        turn(binding.action)
        return true
    }

    fun clear() = claimed.clear()
}

/** Recording waits for release before enabling Save, so a held key cannot leak into reading. */
internal class ReaderKeyCaptureSession {
    private data class Press(val device: Int, val code: Int, val scan: Int, val downTime: Long)
    private val held = mutableMapOf<Press, ReaderPhysicalKey>()
    private var candidate: ReaderPhysicalKey? = null

    fun dispatch(event: KeyEvent, captured: (ReaderPhysicalKey, Boolean) -> Unit): Boolean {
        val press = Press(event.deviceId, event.keyCode, event.scanCode, event.downTime)
        if (event.action == KeyEvent.ACTION_UP) {
            held.remove(press) ?: return false
            candidate?.let { captured(it, held.isNotEmpty()) }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (press in held) return true
        if (event.repeatCount != 0 || event.isCanceled) return false
        val key = event.readerPhysicalKey() ?: return false
        held[press] = key
        candidate = key
        captured(key, true)
        return true
    }
}
