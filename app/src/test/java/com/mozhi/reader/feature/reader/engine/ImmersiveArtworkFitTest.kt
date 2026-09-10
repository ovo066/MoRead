package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImmersiveArtworkFitTest {
    @Test
    fun `tall cover on a taller phone screen is contained and centered on both axes`() {
        // 2000x2800 封面，1000x2200 可用区：宽度受限，按 0.5 缩放，纵向居中留白。
        val rect = ImmersiveArtworkFit.fit(2000f, 2800f, 1000f, 2200f)
        assertEquals(1000f, rect.width, .01f)
        assertEquals(1400f, rect.height, .01f)
        assertEquals(0f, rect.left, .01f)
        assertEquals(400f, rect.top, .01f)
    }

    @Test
    fun `wide artwork is limited by height and centered horizontally`() {
        val rect = ImmersiveArtworkFit.fit(3000f, 1000f, 1000f, 2000f)
        assertEquals(1000f, rect.width, .01f)
        assertEquals(333.33f, rect.height, .01f)
        assertEquals(833.33f, rect.top, .01f)
    }

    @Test
    fun `near matching aspect fills the page instead of leaving thin bars`() {
        // 1000x2100 的图放进 1000x2200：差 4.5%，铺满裁边，不留细窄黑边。
        val rect = ImmersiveArtworkFit.fit(1000f, 2100f, 1000f, 2200f)
        assertEquals(2200f, rect.height, .01f)
        assertEquals(1047.62f, rect.width, .01f)
        assertEquals(-23.81f, rect.left, .01f)
        // 排版层不允许裁边：滚动条带里溢出会压到相邻章。
        val contained = ImmersiveArtworkFit.fit(1000f, 2100f, 1000f, 2200f, allowFill = false)
        assertEquals(1000f, contained.width, .01f)
        assertEquals(0f, contained.left, .01f)
    }

    @Test
    fun `small CSS image boxes are not promoted to full page artwork`() {
        val small = InlineImagePlacement("small.png", 100f, 50f, "")
        assertEquals(false, ImmersiveArtworkFit.fillsContentAxis(small, 200f, 300f))
        assertEquals(true, ImmersiveArtworkFit.fillsContentAxis(small.copy(width = 200f), 200f, 300f))
        assertEquals(true, ImmersiveArtworkFit.fillsContentAxis(small.copy(height = 300f), 200f, 300f))
    }

    @Test
    fun `single artwork recognises both engines and rejects pages with text`() {
        val positioned = PositionedInlineImagePlacement("cover.jpg", left = 20f, width = 1000f, height = 1400f, altText = "cover")
        val v2Line = TextLine("［图片］", emptyList(), 0f, 1400f, 1400f, 20f, false, true, 0, 4, inlineImages = listOf(positioned))
        val legacyLine = TextLine("", emptyList(), 0f, 1400f, 1400f, 0f, false, true, 0, 4,
            inlineImage = InlineImagePlacement("cover.jpg", 1000f, 1400f, "cover"))
        val textLine = TextLine("正文", emptyList(), 0f, 24f, 24f, 0f, false, true, 0, 2)

        assertEquals(1000f, ImmersiveArtworkFit.singleArtwork(listOf(v2Line))!!.width, .01f)
        assertNotNull(ImmersiveArtworkFit.singleArtwork(listOf(legacyLine)))
        assertNull(ImmersiveArtworkFit.singleArtwork(listOf(textLine)))
        assertNull(ImmersiveArtworkFit.singleArtwork(listOf(v2Line, textLine)))
        assertNull(ImmersiveArtworkFit.singleArtwork(emptyList()))
    }
}
