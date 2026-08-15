package com.mozhi.reader.core.database.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaChatAppearanceCodecTest {

    @Test
    fun roundTripsEveryField() {
        val appearance = PersonaChatAppearance(
            backgroundImageId = "img-1",
            backgroundDim = 0.3f,
            fontId = "font-2",
            fontScale = 1.25f,
            bubbleShape = ChatBubbleShape.GLASS.wire,
            assistantColorArgb = 0xFF112233.toInt(),
            userColorArgb = 0xFF445566.toInt()
        )

        val decoded = PersonaChatAppearanceCodec.decode(
            PersonaChatAppearanceCodec.encode(appearance)
        )

        assertEquals(appearance, decoded)
        assertEquals(ChatBubbleShape.GLASS, decoded.shape)
    }

    /** 空列、空串与坏 JSON 都不能让聊天页打不开。 */
    @Test
    fun badInputFallsBackToDefault() {
        listOf(null, "", "   ", "{", "not json", "[]").forEach { raw ->
            assertEquals(
                "输入「$raw」应降级为默认外观",
                PersonaChatAppearance.DEFAULT,
                PersonaChatAppearanceCodec.decode(raw)
            )
        }
    }

    /** v17 迁移给老角色写的是 "{}"，必须解析成「跟随阅读主题」。 */
    @Test
    fun emptyObjectMeansFollowTheReadingTheme() {
        val decoded = PersonaChatAppearanceCodec.decode("{}")

        assertTrue(decoded.isDefault)
        assertEquals(null, decoded.backgroundImageId)
        assertEquals(null, decoded.fontId)
        assertEquals(ChatBubbleShape.ROUNDED, decoded.shape)
    }

    @Test
    fun outOfRangeValuesAreClamped() {
        val decoded = PersonaChatAppearanceCodec.decode(
            """{"background_dim":9.0,"font_scale":42.0}"""
        )

        assertEquals(1f, decoded.backgroundDim, 0.0001f)
        assertEquals(PersonaChatAppearance.MAX_FONT_SCALE, decoded.fontScale, 0.0001f)

        val small = PersonaChatAppearanceCodec.decode(
            """{"background_dim":-3.0,"font_scale":0.1}"""
        )
        assertEquals(0f, small.backgroundDim, 0.0001f)
        assertEquals(PersonaChatAppearance.MIN_FONT_SCALE, small.fontScale, 0.0001f)
    }

    @Test
    fun unknownShapeFallsBackToRounded() {
        val decoded = PersonaChatAppearanceCodec.decode("""{"bubble_shape":"HOLOGRAM"}""")

        assertEquals(ChatBubbleShape.ROUNDED, decoded.shape)
        assertEquals(ChatBubbleShape.ROUNDED.wire, decoded.bubbleShape)
    }

    @Test
    fun unknownKeysAreIgnoredSoOlderBuildsStayReadable() {
        val decoded = PersonaChatAppearanceCodec.decode(
            """{"font_scale":1.1,"future_field":{"nested":true}}"""
        )

        assertEquals(1.1f, decoded.fontScale, 0.0001f)
    }

    @Test
    fun customisedAppearanceIsNotReportedAsDefault() {
        assertFalse(
            PersonaChatAppearance(backgroundImageId = "img-9").isDefault
        )
        assertTrue(PersonaChatAppearance().isDefault)
    }
}
