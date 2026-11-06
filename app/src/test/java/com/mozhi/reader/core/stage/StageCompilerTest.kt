package com.mozhi.reader.core.stage

import org.junit.Assert.*
import org.junit.Test

internal object StageAssertions {
    /** 图片占位与它两侧被换成空格的换行：段内行内图片合回一段后，展示文字里不再有它们。 */
    private val imageMark = Regex("\\s*(?:［图片］|￼)\\s*")
    private const val CONTINUATION = "，、；：,;:"

    fun assertScript(body: String, script: StageScript) {
        val occupied = mutableListOf<SourceRange>()
        var previous = -1
        for (beat in script.beats) {
            if (beat.kind == BeatKind.TITLE) { assertNull(beat.range); continue }
            val r = requireNotNull(beat.range)
            assertTrue("${beat.kind}: $r", r.start >= 0 && r.end <= body.length && r.start >= previous)
            previous = r.end
            val raw = body.substring(r.start, r.end)
            val plain = raw.replace(imageMark, "")
            assertFalse("${beat.kind} 跨行: $raw", plain.contains('\n'))
            occupied += r
            listOfNotNull(beat.asideBefore, beat.asideAfter).forEach {
                assertTrue(it.range.end <= body.length)
                assertEquals(it.text, body.substring(it.range.start, it.range.end).replace(imageMark, "").trim())
                occupied += it.range
            }
            // 续拍与剧本体没有外层引号，仍须逐字落在原文里。
            if (beat.voice == DialogueVoice.QUOTE && plain.firstOrNull() in listOf('“', '「', '"') && plain.lastOrNull() in listOf('”', '」', '"')) {
                // 展示文字去掉引号内的首尾空白与内联图片占位符（「“ 끼리”」）。
                assertEquals(beat.text, plain.substring(1, plain.length - 1).trim())
            } else if (beat.kind in setOf(BeatKind.CHAT, BeatKind.FORUM, BeatKind.STATUS, BeatKind.SYSTEM, BeatKind.THOUGHT, BeatKind.NOTE, BeatKind.DIALOGUE)) {
                assertTrue("$raw contains ${beat.text}", plain.contains(beat.text))
            } else assertEquals(beat.text, plain.trim())
            // 一句话不能从中间切开：旁白、对白、心声不以逗号、顿号、分号、冒号起头（原文这一行本来就这样起头的除外）。
            if (beat.containerId == null && beat.kind in setOf(BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.THOUGHT) &&
                beat.text.firstOrNull()?.let { it in CONTINUATION } == true
            ) {
                val lineStart = body.lastIndexOf('\n', r.start - 1) + 1
                assertTrue("从句中切开: ${beat.text.take(30)}", body.substring(lineStart, r.start).isBlank())
            }
            beat.spans.forEach { assertTrue(it.start >= 0 && it.end >= it.start && it.end <= beat.text.length) }
        }
        occupied.sortBy { it.start }
        occupied.zipWithNext().forEach { (a, b) -> assertTrue("overlap: $a $b", a.end <= b.start) }
    }
}

class StageCompilerTest {
    private fun compile(body: String, context: StageCompileContext = StageCompileContext()): StageScript =
        StageCompiler.compile(12, "测试章", body, context).also { StageAssertions.assertScript(body, it) }
    private fun known(vararg names: String) = StageCompileContext(knownNames = names.map { StageKnownName(it) })

    @Test fun `原创章节完整编译为三十七拍`() {
        val script = StageCompiler.compile(12, "第十二章 雨夜来信", SAMPLE, known("林晚"))
        StageAssertions.assertScript(SAMPLE, script)
        val expected = listOf(BeatKind.TITLE, BeatKind.CAPTION) + List(3) { BeatKind.NARRATION } + List(4) { BeatKind.CHAT } +
            BeatKind.THOUGHT + List(2) { BeatKind.NARRATION } + List(5) { BeatKind.LETTER } +
            listOf(BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.SFX, BeatKind.NARRATION, BeatKind.DIALOGUE, BeatKind.NARRATION) +
            List(3) { BeatKind.DIALOGUE } + listOf(BeatKind.NARRATION, BeatKind.BREAK, BeatKind.NARRATION, BeatKind.FORUM_HEAD) +
            List(4) { BeatKind.FORUM } + listOf(BeatKind.SYSTEM, BeatKind.NARRATION, BeatKind.NARRATION)
        assertEquals(37, script.beats.size)
        assertEquals(expected, script.beats.map { it.kind })
        assertEquals("林晚", script.protagonist)
        assertEquals("匿名", script.containers.first { it.type == StageContainerType.CHAT }.title)
        assertTrue(script.beats.first { it.text == "你是谁？" }.item!!.isMe)
        assertEquals(listOf(LetterLineRole.SALUTATION, LetterLineRole.BODY, LetterLineRole.BODY, LetterLineRole.VERSE, LetterLineRole.SIGNATURE), script.beats.filter { it.kind == BeatKind.LETTER }.map { it.item!!.letterRole })
        val forum = script.containers.first { it.type == StageContainerType.FORUM }
        assertEquals("江城论坛·本地生活版", forum.board)
        assertTrue(forum.title!!.startsWith("【求助】"))
        val floors = script.beats.filter { it.kind == BeatKind.FORUM }
        assertEquals(listOf("楼主", "2楼", "3楼", "4楼"), floors.map { it.item!!.floor })
        assertEquals("雨天不打伞", floors.first().item!!.author)
        val system = script.beats.first { it.kind == BeatKind.SYSTEM }
        assertEquals("该帖子已被删除。", system.text)
        assertEquals("系统提示", system.systemLabel)
        assertEquals(forum.id, system.overlayOn)
        val name = script.beats.first { it.text == "沈舟……" }
        assertEquals("她低声念出这个名字。", name.asideAfter!!.text)
        assertEquals("低声", name.instruction)
        assertNull(name.speaker)
        val closing = script.beats.first { it.text == "打烊了。" }
        assertEquals("林晚", closing.speaker!!.label)
        assertEquals("林晚攥紧了手里的信。", closing.asideAfter!!.text)
        assertEquals("男人顿了顿，", script.beats.first { it.text == "我只买一本书。" }.asideAfter!!.text)
        assertTrue(script.beats.any { it.text == "——《雨巷》。" })
    }

    @Test fun `属性系统剧本诗词拟声字幕与术语`() {
        val panel = compile("他睁开眼，眼前浮现出一块半透明的面板。\n姓名：陈默\n等级：1\n技能：无")
        assertEquals(listOf("姓名", "等级", "技能"), panel.beats.filter { it.kind == BeatKind.STATUS }.map { it.item!!.label })
        val sys = compile("【叮！新手任务已发布：活过今晚。】").beats.last()
        assertEquals(BeatKind.SYSTEM, sys.kind)
        assertEquals("叮！新手任务已发布：活过今晚。", sys.text)
        assertNull(sys.systemLabel)
        val seed = "顾长庚道：“好。”\n".repeat(3)
        val drama = compile(seed + "顾长庚：你来了。\n奇怪的是：事情结束了。")
        assertEquals("顾长庚", drama.beats.first { it.text == "你来了。" }.speaker!!.label)
        assertEquals(BeatKind.NARRATION, drama.beats.last().kind)
        val verse = compile("霜落青山外\n云归小院中\n松声穿竹影\n灯火照归鸿")
        assertEquals(List(4) { BeatKind.VERSE }, verse.beats.drop(1).map { it.kind })
        assertTrue(compile("手机还在！\n钥匙还在！").beats.none { it.kind == BeatKind.VERSE })
        val angry = compile("“你疯了吗！！”王五怒道。", known("王五")).beats.last()
        assertEquals("王五", angry.speaker!!.label)
        assertEquals(StageEmotion.ANGRY, angry.emotion)
        assertEquals(2, angry.intensity)
        assertEquals("高声", angry.instruction)
        assertEquals(BeatEnter.SLAM, StageEffectBudget.apply(listOf(angry), StageIntensity.STANDARD).single().enter)
        val captions = compile("轰——！\n三年后。\n东区，弗拉德街。\n龙怀山听到这里，心又不禁吊了起来。")
        assertEquals(ScreenEffect.SHAKE, captions.beats[1].screen)
        assertEquals(listOf(BeatKind.SFX, BeatKind.CAPTION, BeatKind.CAPTION, BeatKind.NARRATION), captions.beats.drop(1).map { it.kind })
        assertEquals(BeatKind.NARRATION, compile("他做了个“请”的手势。").beats.last().kind)
        assertEquals(BeatKind.DIALOGUE, compile("“因为你没有问。”这也是意料中的回答。").beats.last().kind)
    }

    @Test fun `书信与跨段提示语以及主语陷阱`() {
        val letter = compile("他打开一封信。\n\n见字如面：\n一切安好，勿念。\n——母字")
        assertEquals(List(3) { BeatKind.LETTER }, letter.beats.takeLast(3).map { it.kind })
        assertEquals(LetterLineRole.SIGNATURE, letter.beats.last().item!!.letterRole)
        assertTrue(compile("他打开一封信。\n作者有话说：\n欢迎明天再来。").beats.none { it.kind == BeatKind.LETTER })
        val cross = compile("宁照雪略作沉吟道：\n\n“再等一会。”", known("宁照雪"))
        assertEquals(2, cross.beats.size)
        assertEquals("宁照雪略作沉吟道：", cross.beats.last().asideBefore!!.text)
        assertEquals("宁照雪", cross.beats.last().speaker!!.label)
        val seed = ("顾长庚道：“好。”\n宁照雪道：“好。”\n").repeat(3)
        val traps = compile(seed + "顾长庚还想问什么时，突然有人插话道：“够了。”\n见顾长庚要走，宁照雪拉住了他，道：“等等。”")
        assertNull(traps.beats.first { it.text == "够了。" }.speaker)
        assertEquals("宁照雪", traps.beats.first { it.text == "等等。" }.speaker!!.label)
    }

    @Test fun `词表边界音译名与档案别名`() {
        val body = ("顾长庚高声回应道：“好。”\n顾长庚慌忙追问道：“什么？”\n顾长庚含笑道：“等着。”\n").repeat(3) +
            "艾琳·维克多在院子里等候。\n艾琳·维克多站到门边。\n艾琳说：“好。”\n艾琳说：“来吧。”\n"
        val script = compile(body)
        assertTrue("顾长庚" in script.names)
        assertFalse("顾长" in script.names)
        assertTrue("艾琳·维克多" in script.names)
        assertTrue("艾琳" in script.names)
        val alias = compile("南南笑道：“来了。”", StageCompileContext(knownNames = listOf(StageKnownName("温以南", listOf("南南"), true))))
        assertEquals("温以南", alias.protagonist)
        assertEquals("南南", alias.beats.last().speaker!!.label)
        assertEquals(StageSource.DOSSIER, alias.beats.last().speaker!!.source)
        assertTrue(alias.beats.last().speaker!!.isProtagonist)
    }

    @Test fun `分行论坛保留作者时间及容器内旁白`() {
        val body = "本地论坛\n【求助】今天会下雨吗？\n#1\n***\n2分钟前\n天色很暗。\n宁照雪看向窗外。\n#2\n某ID\n1分钟前\n会下雨。"
        val script = compile(body, known("宁照雪"))
        val first = script.beats.first { it.kind == BeatKind.FORUM }
        val item = requireNotNull(first.item)
        assertEquals("***", item.author)
        assertEquals("2分钟前", item.time)
        assertTrue(script.beats.none { it.kind == BeatKind.BREAK })
        val embedded = script.beats.first { it.embedded }
        assertEquals(BeatKind.NARRATION, embedded.kind)
        assertEquals(first.containerId, embedded.containerId)
    }

    @Test fun `长对白续拍只在首尾保留提示语`() {
        val inner = "院子里的花又开了，今夜你可以留下来等雨停。".repeat(8)
        val script = compile("宁照雪道：“$inner”她低声合上门。", known("宁照雪"))
        val chunks = script.beats.filter { it.kind == BeatKind.DIALOGUE }
        assertTrue(chunks.size > 1)
        assertEquals(inner, chunks.joinToString("") { it.text })
        assertTrue(chunks.dropLast(1).all { it.continued })
        assertFalse(chunks.last().continued)
        assertTrue(chunks.all { it.speaker?.label == "宁照雪" })
        assertNotNull(chunks.first().asideBefore)
        assertTrue(chunks.drop(1).all { it.asideBefore == null })
        assertNotNull(chunks.last().asideAfter)
        assertTrue(chunks.dropLast(1).all { it.asideAfter == null })
    }

    @Test fun `方括号传音保持系统声线`() {
        val script = compile("[不是。]系统顿了一下，道：[想知道什么，都可以问我。]", known("系统"))
        val quotes = script.beats.drop(1)
        assertEquals(2, quotes.size)
        assertTrue(quotes.all { it.voice == DialogueVoice.SYSTEM && it.speaker?.label == "系统" })
    }

    @Test fun `历史词表不前瞻且坐标保留代理对空白与换行`() {
        val history = listOf("顾长庚道：“请坐。”\n".repeat(3))
        val body = "  😀风吹过窗边。\r\n\r\n  顾长庚道：“来了。”  \r\n"
        val script = compile(body, StageCompileContext(history = history))
        assertEquals("顾长庚", script.beats.last().speaker!!.label)
        assertEquals(body.indexOf('“'), script.beats.last().range!!.start)
        assertEquals(2, script.beats[1].range!!.start)
        assertTrue(compile("“别等我。”").names.isEmpty())
        assertTrue(history.single().startsWith("顾长庚道："))
    }

    @Test fun `全角方括号整行是系统消息且不从句号处拆开`() {
        val script = compile("我盯着屏幕。\n［星痕‘云端系统’准备发动中。］\n［星座「深渊的黑焰龙」对你的选择很满意。］［星座「秘密谋略家」点头。］")
        val systems = script.beats.filter { it.kind == BeatKind.SYSTEM }
        assertEquals(listOf("星痕‘云端系统’准备发动中。"), systems.take(1).map { it.text })
        assertTrue(script.beats.none { it.text.startsWith("］") || it.text == "］" })
        assertTrue(script.beats.drop(1).all { it.text.count { c -> c == '「' } == it.text.count { c -> c == '」' } })
    }

    @Test fun `句中短引号与并列引号词留在叙述里`() {
        val lines = listOf(
            "他问“什么”，怀姣却好像没听见一样。",
            "“滴”，门开了。他走进去。",
            "掌握着各种法术：“造雾”、“刮风”、“闪光”和让人脚下打滑的“摔倒术”。",
            "陶真人有一件宝物，名曰“元坤壤精”，此宝能生土精之气。",
            "“小丑”？密修会……克莱恩险些没能保持住状态。",
            "【非凡特性不灭定律】：非凡特性不会毁灭，不会减少。",
            "“呸，别给我提鲜血！我需要的是美丽的少女的鲜血！”地下室内的吸血鬼突然暴躁。"
        )
        for (line in lines) {
            val beats = compile(line).beats.drop(1)
            assertTrue("$line → ${beats.map { it.text }}", beats.none { it.text.firstOrNull()?.let { c -> c in "，、；：" } == true })
        }
        assertEquals(BeatKind.DIALOGUE, compile(lines.last()).beats[1].kind)
        assertEquals(listOf(BeatKind.NARRATION), compile(lines[0]).beats.drop(1).map { it.kind })
    }

    @Test fun `引号后的标点并进引语且反向引号照样认`() {
        val script = compile("吕空昀想，“你为什么会找上我”。这个问题不好回答。")
        assertTrue(script.beats.none { it.text == "。" })
        val reversed = compile("章明低笑两声，说：”老婆。“ 听到这个词，闻青睁开眼。")
        assertEquals("老婆。", reversed.beats.first { it.kind == BeatKind.DIALOGUE }.text)
        val nested = compile("小护士小声说：“通讯录里只有一个叫“啸啸”的人，我们以为是女朋友！”")
        assertEquals(1, nested.beats.count { it.kind == BeatKind.DIALOGUE })
        val brackets = compile("8701告诉怀姣：[你下个本还是B级本，比较合适的就[冥婚]和[溶洞探险]两个。]")
        assertTrue(brackets.beats.any { it.kind == BeatKind.DIALOGUE && it.text.contains("[冥婚]和[溶洞探险]") })
    }

    @Test fun `括号里的句号不切开整段括注`() {
        val script = compile("（小声跟吕耳语。吕红着脸挣大了点眼睛，然后脸色又恢复了平静）")
        assertEquals(1, script.beats.size - 1)
        val narration = compile("他点点头（其实没听懂。也不想懂）。然后走了。")
        assertTrue(narration.beats.drop(1).all { it.text.count { c -> c == '（' } == it.text.count { c -> c == '）' } })
    }

    @Test fun `没有落款的信不吞后面的旁白`() {
        val body = "他展开那张纸条，上面写着：\n晚晚：\n见字如面。\n我走了。\n他把纸条揉成一团，扔进了垃圾桶。\n她始终没有回来。"
        val script = compile(body)
        val letter = script.beats.filter { it.kind == BeatKind.LETTER }
        assertTrue(letter.isNotEmpty())
        assertTrue(letter.none { it.text.startsWith("他把纸条") || it.text.startsWith("她始终") })
        assertTrue(compile("吕空昀的信息来了：\n今晚不回去。").beats.none { it.kind == BeatKind.LETTER })
    }

    @Test fun `章首题图后面重复的章名不再念一遍`() {
        val body = "［图片］\n第68章 线索\n北区偏西的郊外，一座快被废弃的三层房屋内。"
        val image = body.indexOf("［图片］")
        val script = StageCompiler.compile(0, "第68章 线索", body, StageCompileContext(book = StageBookLayout(decorativeImages = setOf(image))))
        assertEquals(BeatKind.TITLE, script.beats.first().kind)
        assertTrue(script.beats.none { it.kind == BeatKind.NARRATION && it.text.contains("线索") })
        assertEquals("北区偏西的郊外，一座快被废弃的三层房屋内。", script.beats.first { it.kind == BeatKind.NARRATION }.text)
    }

    @Test fun `行内图片拆开的段落合回一段脚注成小字注`() {
        val body = "中国的飞虎一声令下，由他领军的势力“阿Q\n［图片］\n”的化身整齐划一地拿出兵器。\n［图片］\n阿Q：鲁迅笔下的人物。"
        val first = body.indexOf("中国")
        val image = body.indexOf("［图片］")
        val noteImage = body.lastIndexOf("［图片］")
        val layout = StageBookLayout(
            joinRanges = listOf(SourceRange(first, body.indexOf("兵器。") + 3)),
            decorativeImages = setOf(image, noteImage),
            noteRanges = listOf(SourceRange(noteImage, body.length))
        )
        val script = compile(body, StageCompileContext(book = layout))
        val prose = script.beats.filter { it.kind == BeatKind.NARRATION }
        assertEquals(listOf("中国的飞虎一声令下，由他领军的势力“阿Q”的化身整齐划一地拿出兵器。"), prose.map { it.text })
        assertEquals("阿Q：鲁迅笔下的人物。", script.beats.last { it.kind == BeatKind.NOTE }.text)
    }

    @Test fun `上一段点名的说话人归给紧跟的引语`() {
        val history = listOf("李智慧道：“走吧。”\n李智慧说：“好。”\n李智慧问：“去哪？”\n")
        val script = compile("这时，静静听着对话的李智慧开了口。\n“所以说，你是要把小说送过去？”", StageCompileContext(history = history))
        assertEquals("李智慧", script.beats.last().speaker?.label)
    }

    companion object {
        val SAMPLE = """
            江城，深夜十一点。

            雨下了整整一夜，旧书店的玻璃门上，水痕一道压着一道。

            林晚把最后一摞旧书搬进里间，正要拉下卷帘门，手机忽然亮了。

            是一条匿名私信。

            【匿名】：林小姐，你店里那本《雨巷》还在吗？
            【匿名】：第七十三页，夹着一样东西。
            林晚：你是谁？
            【匿名】：明晚零点之前，别让任何人买走它。

            （……他怎么会知道那本书？）

            她从柜台最底层翻出那本《雨巷》。书页早已泛黄，第七十三页里，果然夹着一封信。

            晚晚：
            见字如面。
            若你读到这封信，说明我已经不在江城了。
            山有木兮木有枝，心悦君兮君不知。
            ——舟，三年前

            落款的字迹，她认得。

            “沈舟……”她低声念出这个名字。

            咚。咚。咚。

            有人在敲卷帘门。

            “请问，还营业吗？”

            门外站着一个撑黑伞的男人，伞沿压得很低，看不清脸。

            “打烊了。”林晚攥紧了手里的信。

            “我只买一本书。”男人顿了顿，“——《雨巷》。”

            林晚的心跳漏了一拍。

            ※

            男人走后，林晚在本地论坛里搜到了一个三年前的旧帖。

            江城论坛·本地生活版
            【求助】老城区那家“晚来书店”的传说是真的吗？
            楼主 雨天不打伞：听说那家店每逢雨夜，都会有人去买同一本书。
            2楼 江城百事通：真的，我舅舅说三年前就出过这事。
            3楼 匿名用户：别去。买过那本书的人，后来都不见了。
            4楼 雨天不打伞：楼上别吓人……

            【系统提示】该帖子已被删除。

            林晚关掉手机。屏幕暗下去的那一瞬，她看见玻璃上映出的，是两个人影。
        """.trimIndent()
    }
}
