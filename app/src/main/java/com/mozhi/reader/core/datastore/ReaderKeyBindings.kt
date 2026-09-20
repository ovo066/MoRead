package com.mozhi.reader.core.datastore

import android.view.KeyEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray

@Serializable
enum class ReaderKeyAction(val label: String) { PREVIOUS_PAGE("上一页"), NEXT_PAGE("下一页") }

/** Device ids change on reconnect. Known Android key codes are portable across devices. */
@Serializable
data class ReaderPhysicalKey(val keyCode: Int, val modifiers: Int = 0, val scanCode: Int = 0) {
    fun normalized(): ReaderPhysicalKey? {
        if (keyCode !in 0..1023 || keyCode in RESERVED) return null
        if (keyCode == KeyEvent.KEYCODE_UNKNOWN && scanCode !in 1..65535) return null
        return copy(modifiers = modifiers and MODIFIER_MASK, scanCode = if (keyCode == KeyEvent.KEYCODE_UNKNOWN) scanCode else 0)
    }

    companion object {
        const val MODIFIER_MASK = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON
        private val RESERVED = setOf(
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_SLEEP, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SOFT_SLEEP, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_FUNCTION, KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK, KeyEvent.KEYCODE_SCROLL_LOCK
        )
    }
}

@Serializable
data class ReaderKeyBinding(val key: ReaderPhysicalKey, val action: ReaderKeyAction)

object ReaderKeyBindings {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val DEFAULT = listOf(
        ReaderKeyBinding(ReaderPhysicalKey(KeyEvent.KEYCODE_VOLUME_UP), ReaderKeyAction.PREVIOUS_PAGE),
        ReaderKeyBinding(ReaderPhysicalKey(KeyEvent.KEYCODE_VOLUME_DOWN), ReaderKeyAction.NEXT_PAGE)
    )

    fun normalized(bindings: List<ReaderKeyBinding>): List<ReaderKeyBinding> = bindings.mapNotNull { binding ->
        binding.key.normalized()?.let { binding.copy(key = it) }
    }.asReversed().distinctBy { it.key }.asReversed()

    fun assign(bindings: List<ReaderKeyBinding>, key: ReaderPhysicalKey, action: ReaderKeyAction): List<ReaderKeyBinding> =
        normalized(bindings + ReaderKeyBinding(key, action))

    fun volumePreset(bindings: List<ReaderKeyBinding>, reversed: Boolean): List<ReaderKeyBinding> =
        normalized(bindings.filterNot { it.key.modifiers == 0 && it.key.keyCode in listOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN) } +
            DEFAULT.map { if (reversed) it.copy(action = if (it.action == ReaderKeyAction.PREVIOUS_PAGE) ReaderKeyAction.NEXT_PAGE else ReaderKeyAction.PREVIOUS_PAGE) else it })

    fun encode(bindings: List<ReaderKeyBinding>): String = json.encodeToString(normalized(bindings))
    fun decode(raw: String?): List<ReaderKeyBinding> {
        if (raw.isNullOrBlank()) return DEFAULT
        return runCatching {
            normalized(json.parseToJsonElement(raw).jsonArray.mapNotNull {
                runCatching { json.decodeFromJsonElement<ReaderKeyBinding>(it) }.getOrNull()
            })
        }.getOrDefault(DEFAULT)
    }
}
