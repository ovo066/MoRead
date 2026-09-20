package com.mozhi.reader.feature.reader

import android.app.Application
import android.view.KeyEvent
import com.mozhi.reader.core.datastore.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReaderHardwareKeysTest {
    private fun event(code: Int, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0, meta: Int = 0, scan: Int = 0,
        device: Int = 1, time: Long = 100, flags: Int = 0) = KeyEvent(time, time + repeat, action, code, repeat, meta, device, scan, flags)

    @Test fun defaultVolumeKeysOwnPressAndReleaseWithoutRepeatingOrChangingSystemVolume() {
        val dispatcher = ReaderHardwareKeyDispatcher()
        val actions = mutableListOf<ReaderKeyAction>()
        fun send(e: KeyEvent, enabled: Boolean = true, allowed: Boolean = true) = dispatcher.dispatch(e, enabled, allowed, ReaderKeyBindings.DEFAULT, actions::add)
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN)))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 1)))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 2), allowed = false))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP), enabled = false))
        assertEquals(listOf(ReaderKeyAction.NEXT_PAGE), actions)
        assertFalse(send(event(KeyEvent.KEYCODE_VOLUME_UP), enabled = false))
        assertFalse(send(event(KeyEvent.KEYCODE_VOLUME_UP), allowed = false))
        assertFalse(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, repeat = 3)))
        assertFalse(send(event(KeyEvent.KEYCODE_A)))
    }

    @Test fun remappedKeysAndReconnectsWorkWhileUnboundKeysKeepTheirOriginalFunction() {
        val dispatcher = ReaderHardwareKeyDispatcher()
        val actions = mutableListOf<ReaderKeyAction>()
        val bindings = ReaderKeyBindings.assign(ReaderKeyBindings.volumePreset(ReaderKeyBindings.DEFAULT, true),
            ReaderPhysicalKey(KeyEvent.KEYCODE_PAGE_DOWN), ReaderKeyAction.NEXT_PAGE)
        fun send(e: KeyEvent) = dispatcher.dispatch(e, true, true, bindings, actions::add)
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_UP, scan = 115)))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP, scan = 115)))
        assertTrue(send(event(KeyEvent.KEYCODE_PAGE_DOWN, device = 7, time = 200)))
        assertTrue(send(event(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP, device = 7, time = 200)))
        assertTrue(send(event(KeyEvent.KEYCODE_PAGE_DOWN, device = 19, time = 300)))
        assertFalse(send(event(KeyEvent.KEYCODE_PAGE_UP)))
        assertEquals(List(3) { ReaderKeyAction.NEXT_PAGE }, actions)
    }

    @Test fun recordingNormalizesModifiersAndScanCodesAndRejectsSystemOrSoftwareKeys() {
        assertEquals(ReaderPhysicalKey(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
            event(KeyEvent.KEYCODE_K, meta = KeyEvent.META_CTRL_LEFT_ON or KeyEvent.META_CAPS_LOCK_ON, scan = 37).readerPhysicalKey())
        assertEquals(ReaderPhysicalKey(KeyEvent.KEYCODE_UNKNOWN, scanCode = 188), event(0, scan = 188).readerPhysicalKey())
        assertNull(event(KeyEvent.KEYCODE_UNKNOWN).readerPhysicalKey())
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_CTRL_LEFT))
            assertNull(event(key).readerPhysicalKey())
        assertNull(event(KeyEvent.KEYCODE_A, flags = KeyEvent.FLAG_SOFT_KEYBOARD).readerPhysicalKey())
    }

    @Test fun chordsAreExactButModifierReleaseOrderDoesNotLeakKeyUp() {
        val dispatcher = ReaderHardwareKeyDispatcher()
        val actions = mutableListOf<ReaderKeyAction>()
        val bindings = listOf(ReaderKeyBinding(ReaderPhysicalKey(KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON), ReaderKeyAction.NEXT_PAGE))
        fun send(e: KeyEvent) = dispatcher.dispatch(e, true, true, bindings, actions::add)
        assertFalse(send(event(KeyEvent.KEYCODE_K)))
        assertFalse(send(event(KeyEvent.KEYCODE_K, meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
        assertTrue(send(event(KeyEvent.KEYCODE_K, meta = KeyEvent.META_CTRL_RIGHT_ON)))
        assertTrue(send(event(KeyEvent.KEYCODE_K, KeyEvent.ACTION_UP)))
        assertEquals(1, actions.size)
    }

    @Test fun aCapturedPressIsNotReadyUntilEveryRecordedKeyHasBeenReleased() {
        val capture = ReaderKeyCaptureSession()
        val events = mutableListOf<Pair<ReaderPhysicalKey, Boolean>>()
        fun send(e: KeyEvent) = capture.dispatch(e) { key, held -> events += key to held }
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_UP)))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_UP, repeat = 1)))
        assertEquals(1, events.size)
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, time = 150)))
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.ACTION_UP, time = 150)))
        assertTrue(events.last().second)
        assertTrue(send(event(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_UP)))
        assertEquals(ReaderPhysicalKey(KeyEvent.KEYCODE_VOLUME_DOWN) to false, events.last())
        assertFalse(send(event(KeyEvent.KEYCODE_BACK)))
    }

    @Test fun rawScanCodesAreIndependentAndCanceledOrStalePressesNeverTurn() {
        val dispatcher = ReaderHardwareKeyDispatcher()
        var count = 0
        val bindings = listOf(ReaderKeyBinding(ReaderPhysicalKey(0, scanCode = 188), ReaderKeyAction.NEXT_PAGE))
        fun send(e: KeyEvent) = dispatcher.dispatch(e, true, true, bindings) { count++ }
        assertFalse(send(event(0, scan = 189)))
        assertFalse(send(event(0, scan = 188, flags = KeyEvent.FLAG_CANCELED)))
        assertTrue(send(event(0, scan = 188)))
        dispatcher.clear()
        assertFalse(send(event(0, KeyEvent.ACTION_UP, scan = 188)))
        assertEquals(1, count)
    }
}
