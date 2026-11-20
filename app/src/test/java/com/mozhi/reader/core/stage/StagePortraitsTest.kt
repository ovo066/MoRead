package com.mozhi.reader.core.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StagePortraitsTest {
    @Test fun properNamesAndDerivedAliasesResolveButCodenamesDoNot() {
        val index = StagePortraits.index(listOf(
            StagePortraitSubject("lin", "林晚", listOf("晚晚", "林姑娘", "黑衣人", "那位大人")),
            StagePortraitSubject("gu", "顾行舟", listOf("行舟", "顾公子"))
        ))
        assertEquals("lin", index["林晚"])
        assertEquals("lin", index["晚晚"])
        assertEquals("lin", index["林姑娘"])
        assertEquals("gu", index["行舟"])
        // 代称对得上档案，却会在原文揭晓之前把人认出来。
        assertNull(index["黑衣人"])
        assertNull(index["那位大人"])
    }

    @Test fun labelSharedByTwoPeopleBelongsToNeither() {
        val index = StagePortraits.index(listOf(
            StagePortraitSubject("a", "林晚", listOf("林姑娘")),
            StagePortraitSubject("b", "林雪", listOf("林姑娘", "雪儿"))
        ))
        assertNull(index["林姑娘"])
        assertEquals("a", index["林晚"])
        assertEquals("b", index["雪儿"])
    }

    @Test fun spelledNamesMatchOnWholeWordsOnly() {
        val index = StagePortraits.index(listOf(
            StagePortraitSubject("e", "Elizabeth Bennet", listOf("Miss Bennet", "Lizzy", "the stranger")),
            StagePortraitSubject("blank", "  ", listOf("nobody"))
        ))
        assertEquals("e", index["Elizabeth Bennet"])
        assertEquals("e", index["Miss Bennet"])
        assertNull(index["Lizzy"])
        assertNull(index["the stranger"])
        assertFalse("nobody" in index)
    }

    private fun line(kind: BeatKind, start: Int, end: Int, text: String = "x".repeat(end - start)) =
        StageBeat(kind, text, SourceRange(start, end))

    private val picture = StageImage("/art/one.png", 512, 768)

    @Test fun pictureFollowsTheBeatThatContainsItsAnchor() {
        val script = StageScript(0, "章", listOf(
            StageBeat(BeatKind.TITLE, "章", null), line(BeatKind.NARRATION, 0, 10), line(BeatKind.DIALOGUE, 10, 20),
            line(BeatKind.NARRATION, 20, 30)
        ), emptyList(), null, emptyList(), scenes = listOf(StageScene(1, null, null), StageScene(3, null, null)))
        val merged = StageGeneratedArt.merge(script, listOf(StageArtAnchor(12, picture)))
        assertEquals(listOf(BeatKind.TITLE, BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.IMAGE, BeatKind.NARRATION), merged.beats.map { it.kind })
        val image = merged.beats[3]
        assertEquals(SourceRange(20, 20), image.range)
        assertEquals("", image.text)
        assertTrue(image.image!!.generated)
        assertEquals("/art/one.png", image.image!!.path)
        // 场景从哪一拍起算跟着后移；图之前的不动。
        assertEquals(listOf(1, 4), merged.scenes.map { it.fromBeat })
    }

    @Test fun pictureWaitsForLongDialogueContainersAndFigureGroupsToFinish() {
        val script = StageScript(0, "章", listOf(
            line(BeatKind.DIALOGUE, 0, 10).copy(continued = true), line(BeatKind.DIALOGUE, 10, 20),
            line(BeatKind.CHAT, 20, 30).copy(containerId = 7), line(BeatKind.CHAT, 30, 40).copy(containerId = 7),
            line(BeatKind.SYSTEM, 40, 45).copy(overlayOn = 7),
            line(BeatKind.NARRATION, 45, 50).copy(figure = StageFigure.LADDER, figureGroup = 5),
            line(BeatKind.NARRATION, 50, 55).copy(figure = StageFigure.LADDER, figureGroup = 5),
            line(BeatKind.NARRATION, 55, 60)
        ), emptyList(), null, emptyList())
        val merged = StageGeneratedArt.merge(script, listOf(
            StageArtAnchor(3, picture), StageArtAnchor(22, picture.copy(path = "/art/two.png")),
            StageArtAnchor(46, picture.copy(path = "/art/three.png"))
        ))
        val kinds = merged.beats.map { it.kind }
        assertEquals(listOf(BeatKind.DIALOGUE, BeatKind.DIALOGUE, BeatKind.IMAGE, BeatKind.CHAT, BeatKind.CHAT, BeatKind.SYSTEM,
            BeatKind.IMAGE, BeatKind.NARRATION, BeatKind.NARRATION, BeatKind.IMAGE, BeatKind.NARRATION), kinds)
        // 阶梯的两拍仍然认同一组，组号是插图之后的新拍序。
        assertEquals(listOf(7, 7), merged.beats.filter { it.figure == StageFigure.LADDER }.map { it.figureGroup })
        assertEquals(StageFigure.LADDER, merged.beats[7].figure)
    }

    @Test fun anchorsPastTheLastLineLandAtTheEndAndDuplicatesAppearOnce() {
        val script = StageScript(0, "章", listOf(StageBeat(BeatKind.TITLE, "章", null), line(BeatKind.NARRATION, 0, 10)),
            emptyList(), null, emptyList())
        val merged = StageGeneratedArt.merge(script, listOf(StageArtAnchor(10, picture), StageArtAnchor(4, picture)))
        assertEquals(listOf(BeatKind.TITLE, BeatKind.NARRATION, BeatKind.IMAGE), merged.beats.map { it.kind })
        assertSame(script, StageGeneratedArt.merge(script, emptyList()))
        val titleOnly = script.copy(beats = script.beats.take(1))
        assertSame(titleOnly, StageGeneratedArt.merge(titleOnly, listOf(StageArtAnchor(0, picture))))
    }
}
