package com.mozhi.reader.core.stage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 精排书的字色落到演出拍上：色块只包住引号里的字也要算作整句的颜色。 */
class StageBookInkTest {
    private val red = StageInk(colorArgb = 0xFFC0392B.toInt())
    private val blue = StageInk(colorArgb = 0xFF2E6DB4.toInt())

    private fun compile(body: String, vararg inks: StageBookInk): StageScript =
        StageCompiler.compile(0, "第一章", body, StageCompileContext(book = StageBookLayout(styles = StageBookStyles(inks = inks.toList()))))

    private fun dialogue(script: StageScript, text: String): StageBeat =
        script.beats.first { it.kind == BeatKind.DIALOGUE && it.text == text }

    @Test fun `色块只包住引号里的字时整句对白带上书里的颜色`() {
        val body = "“你来得正好，我等你很久了。”她说。"
        val inner = body.indexOf("你来")
        val end = body.indexOf('”')
        val beat = dialogue(compile(body, StageBookInk(inner, end, red, block = false)), "你来得正好，我等你很久了。")
        assertEquals(red, beat.ink)
        assertTrue(beat.spans.none { it.ink != null })
    }

    @Test fun `引号后跟句末标点时色块照样算整句`() {
        val body = "“走吧。”。他没有回头。"
        val inner = body.indexOf("走吧")
        val beat = dialogue(compile(body, StageBookInk(inner, inner + 3, blue, block = false)), "走吧。")
        assertEquals(blue, beat.ink)
    }

    @Test fun `只盖住几个字的色块仍是行内样式`() {
        val body = "“那个叫阿青的姑娘，今天又来了吗？”老板问。"
        val at = body.indexOf("阿青")
        val beat = dialogue(compile(body, StageBookInk(at, at + 2, red, block = false)), "那个叫阿青的姑娘，今天又来了吗？")
        assertNull(beat.ink)
        val span = beat.spans.single { it.ink != null }
        assertEquals("阿青", beat.text.substring(span.start, span.end))
    }

    @Test fun `整段着色的旁白仍取段落颜色`() {
        val body = "雨下了一整夜，街上空无一人。"
        val script = compile(body, StageBookInk(0, body.length, blue, block = true))
        val narration = script.beats.first { it.kind == BeatKind.NARRATION }
        assertEquals(blue, narration.ink)
    }
}
