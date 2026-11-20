package com.mozhi.reader.feature.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedArtLayoutTest {
    private fun spec(height: Float = 600f) =
        TypesetSpec(220f, height, 25f, 32f, 8f, 8f, 0f, 12f, contentFontSizePx = 20f, bottomAlign = false)
    private val measure = FakeMeasure()
    private val first = "第一段写码头的晨光。"
    private val second = "第二段写她把信折好。"
    private val third = "第三段写船终于靠岸。"
    private val body = "$first\n$second\n$third"
    private fun art(anchor: Int, width: Int = 1024, height: Int = 768, caption: String = "AI 配图 · 观澜") =
        EmbeddedArt(anchor, "/art/a.png", width, height, caption)
    private fun typeset(art: List<EmbeddedArt>, height: Float = 600f) =
        ChapterTypesetter(spec(height), measure).typeset(0, "", body, art = art)

    @Test fun pictureSitsAfterItsParagraphAndConsumesNoSourceText() {
        val anchor = body.indexOf(second) + 3
        val chapter = typeset(listOf(art(anchor)))
        val lines = chapter.pages.flatMap { it.lines }
        val image = lines.indexOfFirst { it.inlineImage != null }
        assertTrue(image > 0)
        val secondEnd = body.indexOf(second) + second.length
        // 图前面是第二段的最后一行，图后面先是图注，再是第三段。
        assertEquals(secondEnd, lines[image - 1].chapterPosition + lines[image - 1].charLength)
        assertEquals(0, lines[image].charLength)
        assertEquals(secondEnd, lines[image].chapterPosition)
        assertEquals("/art/a.png", lines[image].inlineImage!!.imagePath)
        val caption = lines[image + 1]
        assertEquals(0, caption.charLength)
        assertEquals("AI 配图 · 观澜", caption.columns.joinToString("") { it.charData })
        assertTrue(caption.columns.all { it.sourceLength == 0 })
        assertEquals(body.indexOf(third), lines[image + 2].chapterPosition)
        // 原文一个字不多、一个字不少，坐标照旧。
        assertEquals(body.length, chapter.bodyLength)
        assertEquals(body.replace("\n", ""), lines.filter { it.charLength > 0 }.joinToString("") { it.text })
        lines.filter { it.charLength > 0 }.forEach {
            assertEquals(body.substring(it.chapterPosition, it.chapterPosition + it.charLength), it.text)
        }
    }

    @Test fun pictureKeepsItsShapeIsCentredAndCanBeHitAsAnImage() {
        val chapter = typeset(listOf(art(1, width = 1200, height = 800)))
        val page = chapter.pages.first { page -> page.lines.any { it.inlineImage != null } }
        val line = page.lines.first { it.inlineImage != null }
        val placement = line.inlineImage!!
        assertEquals(1.5f, placement.width / placement.height, 0.01f)
        assertTrue(placement.width <= 220f + 0.01f)
        assertEquals((220f - placement.width) / 2f, line.startX, 0.01f)
        val hit = page.imageAt(line.startX + placement.width / 2f, line.lineTop + placement.height / 2f, 4)
        assertNotNull(hit)
        assertEquals("/art/a.png", hit!!.imagePath)
        assertEquals(4, hit.chapterIndex)
        // 图注和图上都选不出原文。
        assertNull(page.hitTextPos(line.startX + 1f, line.lineTop + 1f, exact = true))
    }

    @Test fun tallPicturesAreCappedSoTextStillFitsOnThePage() {
        val pageHeight = 600f
        val landscape = typeset(listOf(art(1, width = 800, height = 800)), pageHeight)
            .pages.flatMap { it.lines }.first { it.inlineImage != null }.inlineImage!!
        val portrait = typeset(listOf(art(1, width = 600, height = 1800)), pageHeight)
            .pages.flatMap { it.lines }.first { it.inlineImage != null }.inlineImage!!
        val unknown = typeset(listOf(art(1, width = 0, height = 0)), pageHeight)
            .pages.flatMap { it.lines }.first { it.inlineImage != null }.inlineImage!!
        assertTrue(landscape.height <= pageHeight * 0.48f + 0.01f)
        assertTrue(portrait.height <= pageHeight * 0.6f + 0.01f)
        assertTrue(portrait.height > landscape.height)
        // 极端的长条图按下限比例收住，不会变成一根细线。
        assertTrue(portrait.width / portrait.height >= 0.45f - 0.01f)
        assertEquals(1f, unknown.width / unknown.height, 0.01f)
    }

    @Test fun pictureAndCaptionMoveToTheNextPageTogetherWhenTheyDoNotFit() {
        // 页很矮：三段文字各占一两行，图块在第一页剩下的地方放不下。
        val chapter = typeset(listOf(art(1)), height = 150f)
        chapter.pages.forEach { page ->
            assertTrue(page.lines.last().lineBottom <= 150f + 0.5f || page.lines.size == 1)
            val image = page.lines.indexOfFirst { it.inlineImage != null }
            if (image >= 0) {
                // 图注紧跟在同一页。
                assertTrue(image + 1 < page.lines.size)
                assertEquals(0, page.lines[image + 1].charLength)
                assertTrue(page.lines[image + 1].columns.isNotEmpty())
            }
        }
        assertEquals(1, chapter.pages.sumOf { page -> page.lines.count { it.inlineImage != null } })
    }

    @Test fun severalPicturesFollowAnchorOrderAndStrayAnchorsAreIgnored() {
        val plain = typeset(emptyList()).pages.flatMap { it.lines }
        val later = art(body.indexOf(third)).copy(imagePath = "/art/late.png")
        val earlier = art(0).copy(imagePath = "/art/early.png")
        val stray = art(body.length + 50).copy(imagePath = "/art/stray.png")
        val lines = typeset(listOf(later, stray, earlier)).pages.flatMap { it.lines }
        assertEquals(listOf("/art/early.png", "/art/late.png"), lines.mapNotNull { it.inlineImage?.imagePath })
        assertEquals(plain.map { it.text }, lines.filter { it.charLength > 0 }.map { it.text })
        // 没有图注的图只占一行。
        val bare = typeset(listOf(art(0, caption = "  "))).pages.flatMap { it.lines }
        assertEquals(plain.size + 1, bare.size)
    }

    @Test fun anchorHelperMatchesTheParagraphThatContainsIt() {
        val list = listOf(art(5), art(12), art(20))
        assertEquals(listOf(5), list.anchoredIn(0, 10).map { it.anchor })
        assertEquals(listOf(12), list.anchoredIn(10, 20).map { it.anchor })
        // 空段落（起止相同）仍认落在它起点上的锚点。
        assertEquals(listOf(20), list.anchoredIn(20, 20).map { it.anchor })
        assertTrue(emptyList<EmbeddedArt>().anchoredIn(0, 100).isEmpty())
    }
}
