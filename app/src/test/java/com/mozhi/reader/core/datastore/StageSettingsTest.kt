package com.mozhi.reader.core.datastore

import com.mozhi.reader.core.stage.StageIntensity
import org.junit.Assert.*
import org.junit.Test

class StageSettingsTest {
    @Test fun `默认值坏JSON与未知字段`() {
        val defaults = StageSettings()
        assertEquals(StageTheme.XUAN, defaults.theme)
        assertEquals(StageIntensity.STANDARD, defaults.intensity)
        assertEquals(1f, defaults.typingSpeed)
        assertEquals(1f, defaults.autoSpeed)
        assertTrue(defaults.ghost && defaults.showAsides && defaults.showPortraits)
        assertEquals(defaults, StageSettings.decode(null))
        assertEquals(defaults, StageSettings.decode("broken"))
        assertEquals(defaults, StageSettings.decode("""{"theme":"FUTURE"}"""))
        assertEquals(defaults, StageSettings.decode("""{"future":true}"""))
    }

    @Test fun `往返并归一化持久化值`() {
        val value = StageSettings(StageTheme.THEATER, StageIntensity.LAVISH, 1.5f, .75f, false, false, false)
        assertEquals(value, StageSettings.decode(StageSettings.encode(value)))
        assertEquals(.5f, StageSettings.decode("""{"typingSpeed":0.1}""").typingSpeed)
        val bad = StageSettings(typingSpeed = Float.NaN, autoSpeed = Float.POSITIVE_INFINITY)
        assertEquals(StageSettings(), bad.normalized())
        assertEquals(StageSettings(), StageSettings.decode(StageSettings.encode(bad)))
        assertEquals(.5f, StageSettings(typingSpeed = -2f).normalized().typingSpeed)
        assertEquals(2f, StageSettings(typingSpeed = 3f).normalized().typingSpeed)
        assertEquals(.5f, StageSettings(autoSpeed = -2f).normalized().autoSpeed)
        assertEquals(2f, StageSettings(autoSpeed = 3f).normalized().autoSpeed)
        assertEquals(1f, StageSettings(typingSpeed = Float.NEGATIVE_INFINITY).normalized().typingSpeed)
    }

    @Test fun `旧版本存下的默认值换成新默认`() {
        val defaults = StageSettings()
        assertEquals(StageTextReveal.NONE, defaults.textReveal)
        assertEquals(StageHalo.NONE, defaults.halo)
        assertTrue(defaults.scenery && defaults.brush && !defaults.sound && !defaults.aiDirector)
        // 第一版：没有 revision 字段，整句淡入与顶部光晕都是当时的默认。
        val old = StageSettings.decode("""{"textReveal":"FADE","halo":"TOP","theme":"THEATER"}""")
        assertEquals(StageTextReveal.NONE, old.textReveal)
        assertEquals(StageHalo.NONE, old.halo)
        assertEquals(StageTheme.THEATER, old.theme)
        // 第二版的默认墨晕也收回到直接显示；用户在第一版里主动选的逐字保留。
        assertEquals(StageTextReveal.NONE, StageSettings.decode("""{"textReveal":"INK","revision":2}""").textReveal)
        assertEquals(StageTextReveal.TYPE, StageSettings.decode("""{"textReveal":"TYPE"}""").textReveal)
        // 新版本里主动选的显示效果与光晕不动。
        assertEquals(StageTextReveal.INK, StageSettings.decode("""{"textReveal":"INK","revision":3}""").textReveal)
        assertEquals(StageHalo.TOP, StageSettings.decode("""{"halo":"TOP","revision":3}""").halo)
        assertEquals(StageSettings.CURRENT_REVISION, StageSettings.decode(StageSettings.encode(defaults)).revision)
    }
}
