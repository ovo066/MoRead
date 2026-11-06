package com.mozhi.reader.core.stage

import com.mozhi.reader.core.epub.EpubLayoutDocumentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StageBookLayoutReaderTest {
    private fun compile(xhtml: String): Pair<String, StageScript> {
        val parsed = EpubLayoutDocumentParser().parseWithText(xhtml.toByteArray(), 0, "Text/c1.xhtml", emptyMap())
        val images = parsed.images.map { StageBookImageSource(it.charOffset, "img:" + it.href) }
        val layout = StageBookLayoutReader.read(parsed.dom.bodyNode, parsed.text, images)
        val script = StageCompiler.compile(0, "第一章 测试", parsed.text, StageCompileContext(book = layout))
        StageAssertions.assertScript(parsed.text, script)
        return parsed.text to script
    }

    private fun page(body: String) = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>t</title></head><body>$body</body></html>"""

    @Test fun `书内聊天框直接成手机消息并带头像与左右`() {
        val (_, script) = compile(page("""
            <div class="logo"><img class="logo" src="../Images/logo.png"/></div>
            <p>手机响了一下。</p>
            <div class="lt_div">
              <div class="lt_divzu2"><img class="lt-avatar" src="../Images/a.png"/><div class="lt_name">阿岚</div>
                <div class="lt_border lt-bubble"><p>你到了吗</p></div></div>
              <div class="lt_divzu2 lt_right"><img class="lt-avatar" src="../Images/b.png"/><div class="lt_name">我</div>
                <div class="lt_border lt-bubble"><p>马上</p></div></div>
            </div>
            <p>他收起手机。</p>
        """))
        val chat = script.beats.filter { it.kind == BeatKind.CHAT }
        assertEquals(listOf("你到了吗", "马上"), chat.map { it.text })
        assertEquals("阿岚", chat[0].item?.sender)
        assertFalse(chat[0].item!!.isMe)
        assertTrue(chat[1].item!!.isMe)
        assertNotNull(chat[0].item?.avatar)
        assertTrue(script.containers.single().fromBook)
        // 章首题图不成拍，交给章名卡。
        assertNotNull(script.titleArt)
        assertTrue(script.beats.none { it.kind == BeatKind.IMAGE })
    }

    @Test fun `弹幕与论坛楼层按书内结构出拍`() {
        val (_, script) = compile(page("""
            <p>直播间开了。</p>
            <div class="danmaku-area"><span class="danmaku">来了来了</span><span class="danmaku">主播好</span></div>
            <div class="forum-thread">
              <div class="post-title"><p>求助帖</p></div>
              <div class="reply-post"><div class="reply-meta"><span class="reply-floor">#1</span><span class="reply-author">路人</span><span class="reply-time">2分钟前</span></div>
                <div class="reply-content"><p>顶一下</p></div></div>
            </div>
        """))
        assertEquals(listOf("来了来了", "主播好"), script.beats.filter { it.kind == BeatKind.DANMAKU }.map { it.text })
        val floor = script.beats.single { it.kind == BeatKind.FORUM }
        assertEquals("顶一下", floor.text)
        assertEquals("#1", floor.item?.floor)
        assertEquals("路人", floor.item?.author)
        assertEquals("2分钟前", floor.item?.time)
    }

    @Test fun `插图带图注成拍，分隔图成场景分隔，删除线保留`() {
        val (_, script) = compile(page("""
            <p>他翻开地图。</p>
            <div class="duokan-image-single"><img src="../Images/map.jpg"/><p class="ziti">大陆全图</p></div>
            <div class="fengexian"><img class="fengexian" src="../Images/line.png"/></div>
            <div class="border2"><p>我最<span class="deltxt">讨厌</span>喜欢的人</p></div>
        """))
        val image = script.beats.single { it.kind == BeatKind.IMAGE }
        assertEquals("大陆全图", image.image?.caption)
        assertTrue(script.beats.none { it.text == "大陆全图" })
        assertTrue(script.beats.any { it.kind == BeatKind.BREAK })
        assertNotNull(script.breakArt)
        val note = script.beats.single { it.kind == BeatKind.CARD }
        val strike = note.spans.single { SpanStyle.STRIKE in it.styles }
        assertEquals("讨厌", note.text.substring(strike.start, strike.end))
    }

    @Test fun `没有精排结构时回落到本地规则`() {
        val (_, script) = compile(page("<p>轰然一声巨响，山门崩塌。</p><p>“快走！！”</p>"))
        assertTrue(script.beats.map { it.kind to it.text }.toString(), script.containers.isEmpty() && script.beats.any { it.kind == BeatKind.NARRATION })
        assertEquals(ScreenEffect.SHAKE, script.beats.first { it.kind == BeatKind.NARRATION }.screen)
        val shout = script.beats.first { it.kind == BeatKind.DIALOGUE }
        assertEquals(BeatEnter.SLAM, shout.enter)
    }
}
