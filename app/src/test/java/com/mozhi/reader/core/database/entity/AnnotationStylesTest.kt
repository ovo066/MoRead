package com.mozhi.reader.core.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 批注样式与色名的解析回落、以及角色配色的稳定性。 */
class AnnotationStylesTest {

    @Test
    fun styleFromWireIsCaseInsensitiveAndFallsBackToHighlight() {
        assertEquals(AnnotationStyle.WAVY, AnnotationStyle.fromWire("wavy"))
        assertEquals(AnnotationStyle.UNDERLINE, AnnotationStyle.fromWire("Underline"))
        assertEquals(AnnotationStyle.HIGHLIGHT, AnnotationStyle.fromWire("HIGHLIGHT"))
        assertEquals(AnnotationStyle.HIGHLIGHT, AnnotationStyle.fromWire("marker"))
        assertEquals(AnnotationStyle.HIGHLIGHT, AnnotationStyle.fromWire(null))
        assertEquals(AnnotationStyle.HIGHLIGHT, AnnotationStyle.fromWire(""))
    }

    @Test
    fun colorNormalizeAcceptsKnownTagsAndFallsBackToAmber() {
        assertEquals(AnnotationColors.BAMBOO, AnnotationColors.normalize("bamboo"))
        assertEquals(AnnotationColors.ROSE, AnnotationColors.normalize(" Rose "))
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize("neon"))
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize(null))
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize(""))
    }

    @Test
    fun colorNormalizeKeepsCustomHexUppercased() {
        assertEquals("#A1B2C3", AnnotationColors.normalize("#a1b2c3"))
        assertEquals("#A1B2C3", AnnotationColors.normalize("a1b2c3"))
        assertEquals("#FF0066", AnnotationColors.normalize(" #ff0066 "))
        // 非法十六进制不能污染库：长度不对/含非法字符一律回落
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize("#12345"))
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize("#GGGGGG"))
        assertEquals(AnnotationColors.AMBER, AnnotationColors.normalize("#1234567"))
    }

    @Test
    fun personaColorIsStableAndAlwaysFromPalette() {
        (1L..40L).forEach { personaId ->
            val first = AnnotationColors.forPersona(personaId)
            val second = AnnotationColors.forPersona(personaId)
            assertEquals("personaId=$personaId 两次取色必须一致", first, second)
            assertTrue(first in AnnotationColors.ALL)
        }
        // 相邻 id 落到不同色（散列在四色间轮转）
        assertTrue(AnnotationColors.forPersona(1) != AnnotationColors.forPersona(2))
    }
}
