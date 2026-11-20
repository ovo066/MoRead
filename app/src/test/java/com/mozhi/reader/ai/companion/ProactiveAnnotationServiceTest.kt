package com.mozhi.reader.ai.companion

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.ChatApiClient
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatOptions
import com.mozhi.reader.ai.client.ResolvedChatClient
import com.mozhi.reader.ai.media.AiMediaGenerationService
import com.mozhi.reader.core.database.entity.AnnotationEntity
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.datastore.ProactiveAnnotationAllowance
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class ProactiveAnnotationServiceTest {
    @Test fun customPresetsRespectAllPositionsAndCanReplaceBuiltInWritingRules() {
        val presets = com.mozhi.reader.core.datastore.GlobalPromptInjectionPosition.entries.mapIndexed { index, position ->
            com.mozhi.reader.core.datastore.GlobalPromptPreset("$index", "preset-$index", "marker-$index", true, position)
        } + com.mozhi.reader.core.datastore.GlobalPromptPreset("off", "disabled", "never-inject", false)
        val messages = proactiveAnnotationMessages(persona, "目标段落正文", presets)
        val system = messages.first().content.orEmpty()
        val user = messages.last().content.orEmpty()
        assertTrue(system.indexOf("marker-0") < system.indexOf("你是"))
        assertTrue(system.endsWith("marker-1"))
        assertTrue(user.indexOf("marker-2") < user.indexOf("正文前缀"))
        assertTrue(user.endsWith("marker-3"))
        assertFalse(messages.any { "never-inject" in it.content.orEmpty() })
        assertFalse(system.contains("用你自己的口吻和性格写"))
        assertTrue(system.contains("只输出一个 JSON 对象"))
        assertTrue(system.contains("你和用户都在书外一起阅读"))
        assertTrue(system.contains("用户是读者，不是书中人物"))
        assertTrue(system.contains("不能把共读场景变成剧情扮演"))
        assertTrue(system.contains("即使人物与你或用户同名"))
        assertTrue(system.contains("绝不推测后文"))
    }
    private val factory = mockk<AiClientFactory>()
    private val client = mockk<ChatApiClient>()
    private val media = mockk<AiMediaGenerationService>()
    private val contextRepository = mockk<ProactiveAnnotationContextRepository>()
    private val service = ProactiveAnnotationService(factory, media, contextRepository)
    private val persona = PersonaEntity(id = 2, name = "知墨", personality = "", isRoleplay = false, createdAt = 0)
    private val first = "首段原文".repeat(15)
    private val second = "末段秘密".repeat(15)
    private val body = "$first\n$second"
    private val request = ProactiveAnnotationRequest(1, 3, body, persona, 2, emptySet())
    private val permit = ProactiveAnnotationAllowance(true, maxAnnotations = 2)
    // 两段都有画面，前一段更强；配图测试用它们，其余测试的正文没有画面。
    private val scenic = "月光洒落在湖面，她站在桥上回头，白色长裙与发丝在微风里飞舞，远处灯火倒映在水面，夜色笼罩着山峰。"
    private val scenicToo = "大雪落在长街的屋檐上，他站在门口抬头仰望夜空，黑色斗篷的衣角被狂风掀起，身后的灯火摇曳不定。"
    private val scenicBody = "$scenic\n$scenicToo"
    private val scenicRequest = request.copy(body = scenicBody, imagesPerChapter = 1)

    init {
        coEvery { factory.forRole(ModelRole.PROACTIVE_ANNOTATION) } returns ResolvedChatClient(client, ChatOptions(), mockk(), "mock")
        coEvery { contextRepository.prepare(any(), any()) } returns PreparedAnnotationContext("revision")
        coEvery { contextRepository.isCurrent(any(), any()) } returns true
        coEvery { media.illustrationBackendReady() } returns true
    }

    @Test fun eachRequestUsesOnlyTargetPrefixAndStoresExactProvenance() = runTest {
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } answers {
            val text = requests.last().last().content.orEmpty()
            val quote = if (second in text) second else first
            """{"quote":"$quote","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val ends = mutableListOf<Int>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, end, _ -> rows += row; ends += end; true })
        assertFalse(result.failed)
        assertEquals(2, requests.size)
        coVerify(exactly = 1) { contextRepository.prepare(1, 3) }
        assertFalse(requests.first().any { it.content.orEmpty().contains(second) })
        assertTrue(requests.last().last().content.orEmpty().endsWith(second))
        assertEquals(listOf(first.length, body.length), ends)
        assertEquals(ends, rows.map { it.sourceScopeCharOffset })
        assertTrue(rows.all { it.sourceScopeChapterIndex == 3 })
    }

    @Test fun lookaheadPassYieldsAfterOneSlotAndResumesWithoutReducingUnlimitedCandidates() = runTest {
        val messages = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(messages), any()) } answers {
            val quote = if (messages.last().last().content.orEmpty().endsWith(second)) second else first
            """{"quote":"$quote","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val sliced = request.copy(candidateLimit = Int.MAX_VALUE, maxSlotsPerPass = 1)
        val firstPass = service.generateForChapter(sliced, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertTrue(firstPass.hasMore)
        assertFalse(firstPass.stopped)
        assertEquals(listOf(first), rows.map { it.selectedText })
        assertEquals(setOf(first.length), firstPass.visitedParagraphEnds)
        val lastPass = service.generateForChapter(sliced.copy(doneParagraphEnds = setOf(first.length),
            visitedParagraphEnds = firstPass.visitedParagraphEnds),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertFalse(lastPass.hasMore)
        assertFalse(lastPass.failed)
        assertEquals(listOf(first, second), rows.map { it.selectedText })
        assertEquals(2, messages.size)
        coVerify(exactly = 1) { contextRepository.prepare(1, 3) }
    }

    @Test fun unlimitedSlicesReachTheTailEarlyThenFillAllMissingParagraphsWithoutDuplicateCalls() = runTest {
        val chapter = (0..39).joinToString("\n") { "第${it}段正文：" + "河岸的灯火映在水面，远处钟声缓缓响起。".repeat(5) }
        val targets = ProactiveAnnotationParagraphs.split(chapter)
        val done = targets.take(2).map { it.end }.toMutableSet()
        val visited = mutableSetOf<Int>()
        val rows = mutableListOf<AnnotationEntity>()
        val messages = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(messages), any()) } answers {
            val target = messages.last().last().content.orEmpty().substringAfterLast("唯一目标段落：\n")
            """{"quote":"$target","note":"评论"}"""
        }
        do {
            val result = service.generateForChapter(request.copy(body = chapter, candidateLimit = Int.MAX_VALUE,
                maxSlotsPerPass = 3, doneParagraphEnds = done.toSet(), visitedParagraphEnds = visited.toSet()),
                { permit }, { _, _ -> }, { row, end, _ ->
                    assertTrue(done.add(end))
                    rows += row
                    true
                })
            assertFalse(result.failed)
            assertFalse(result.stopped)
            visited += result.visitedParagraphEnds
        } while (result.hasMore)
        assertTrue(rows.take(6).any { it.startCharOffset > chapter.length * 4 / 5 })
        assertEquals(targets.map { it.end }.toSet(), done)
        assertEquals(targets.size - 2, messages.size)
        rows.zip(messages).forEach { (row, sent) ->
            val prefix = sent.last().content.orEmpty().substringAfter("正文前缀（只到目标结束）：\n")
                .substringBefore("\n\n唯一目标段落：\n")
            assertTrue(chapter.take(row.sourceScopeCharOffset!!).endsWith(prefix))
            assertEquals(chapter.substring(row.startCharOffset, row.endCharOffset), row.selectedText)
        }
    }

    @Test fun failedSlotDoesNotBlockLookaheadOrRepeatUntilTheNextGenerationRound() = runTest {
        var calls = 0
        coEvery { client.chat(any(), any()) } answers {
            if (++calls == 1) error("request failed")
            """{"quote":"$second","note":"评论"}"""
        }
        val sliced = request.copy(maxSlotsPerPass = 1)
        val firstPass = service.generateForChapter(sliced, { permit }, { _, _ -> }, { _, _, _ -> true })
        assertTrue(firstPass.failed)
        assertTrue(firstPass.hasMore)
        val rows = mutableListOf<AnnotationEntity>()
        val lastPass = service.generateForChapter(sliced.copy(visitedParagraphEnds = firstPass.visitedParagraphEnds),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertFalse(lastPass.hasMore)
        assertEquals(second, rows.single().selectedText)
        assertEquals(2, calls)
    }

    @Test fun allThreeModelSelectedStylesReachTheCommitSink() = runTest {
        val styles = listOf("HIGHLIGHT", "underline", "WAVY")
        coEvery { client.chat(any(), any()) } returnsMany styles.map { style ->
            """{"quote":"$first","note":"评论","style":"$style"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        styles.forEach {
            val result = service.generateForChapter(request.copy(candidateLimit = 1),
                { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
            assertFalse(result.failed)
        }
        assertEquals(listOf("HIGHLIGHT", "UNDERLINE", "WAVY"), rows.map { it.style })
        coVerify(exactly = 3) { client.chat(any(), any()) }
    }

    @Test fun promptExplainsSemanticStylesInsteadOfAFixedHighlightExample() = runTest {
        val messages = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(messages), any()) } returns """{"quote":"$first","note":"评论","style":"WAVY"}"""
        service.generateForChapter(request.copy(candidateLimit = 1), { permit }, { _, _ -> }, { _, _, _ -> true })
        val prompt = messages.single().first().content.orEmpty()
        assertTrue(prompt.contains("HIGHLIGHT（荧光）：金句"))
        assertTrue(prompt.contains("WAVY（波浪线）："))
        assertTrue(prompt.contains("UNDERLINE（直线）：知识点"))
        assertTrue(prompt.contains("不要随机轮换"))
        assertTrue(prompt.contains("不能据此断言未读剧情"))
        assertFalse(prompt.contains("\"style\":\"HIGHLIGHT\""))
    }

    @Test fun badQuoteAndFailedParagraphDoNotPreventNextParagraph() = runTest {
        var calls = 0
        coEvery { client.chat(any(), any()) } answers {
            if (++calls == 1) error("failed")
            """{"quote":"$second","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertTrue(result.failed)
        assertEquals(1, rows.size)
        assertEquals(second, rows.single().selectedText)
    }

    @Test fun quoteOutsideTargetIsRejectedEvenWhenItExistsElsewhereInChapter() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertEquals(1, rows.size)
        assertTrue(result.failed)
    }

    @Test fun successfulParagraphsAreSkippedOnRetryAndRevokedPermitStopsRequests() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$second","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        service.generateForChapter(request.copy(doneParagraphEnds = setOf(first.length)), { permit }, { _, _ -> },
            { row, _, _ -> rows += row; true })
        assertEquals(1, rows.size)
        val stopped = service.generateForChapter(request, { null }, { _, _ -> }, { _, _, _ -> error("no commit") })
        assertTrue(stopped.stopped)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }

    @Test fun paragraphTimeoutDoesNotBlockFollowingParagraphAndRemainsRetryable() = runTest {
        var calls = 0
        val rows = mutableListOf<AnnotationEntity>()
        coEvery { client.chat(any(), any()) } coAnswers {
            if (++calls == 1) delay(PROACTIVE_ANNOTATION_TEXT_TIMEOUT_MS + 1)
            """{"quote":"$second","note":"评论"}"""
        }
        val outcome = service.generateForChapter(request, { permit }, { _, _ -> },
            { row, _, _ -> rows += row; true })
        assertTrue(outcome.failed)
        assertFalse(outcome.stopped)
        assertEquals(2, calls)
        assertEquals(second, rows.single().selectedText)
    }

    @Test fun mediaDisabledSkipsBothPaidCalls() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论","voice":true,"image_prompt":"配图"}"""
        service.generateForChapter(request.copy(candidateLimit = 1, persona = persona.copy(voiceId = "voice")),
            { permit }, { _, _ -> error("no media charge") }, { _, _, _ -> true })
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun failedVoiceIsChargedBeforeCallAndPlainAnnotationsContinueWithinCap() = runTest {
        var voiceCharges = 0
        var calls = 0
        var commits = 0
        coEvery { client.chat(any(), any()) } answers {
            val quote = if (++calls == 1) first else second
            """{"quote":"$quote","note":"评论","voice":true}"""
        }
        coEvery { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            assertEquals(1, voiceCharges)
            error("tts unavailable")
        }
        val outcome = service.generateForChapter(request.copy(persona = persona.copy(voiceId = "voice")),
            { permit.copy(maxVoice = 1 - voiceCharges) }, { voices, _ -> voiceCharges += voices },
            { row, _, _ ->
                assertNull(com.mozhi.reader.core.library.AnnotationMedia.decode(row.mediaJson).audioPath)
                commits++; true
            })
        assertFalse(outcome.failed)
        assertEquals(2, commits)
        assertEquals(1, voiceCharges)
        coVerify(exactly = 1) { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun imageIsChargedBeforeRequestAndRollbackDeletesDetachedAsset() = runTest {
        val file = java.io.File.createTempFile("annotation-image", ".png")
        var imageCharges = 0
        try {
            coEvery { client.chat(any(), any()) } returns """{"quote":"$scenic","note":"评论","image_prompt":"配图"}"""
            coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(1, imageCharges)
                assertEquals("配图", arg<String>(4))
                assertFalse(arg<Boolean>(7)) // detached, never published by the media service
                com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "配图", imagePath = file.path, createdAt = 0)
            }
            val outcome = service.generateForChapter(scenicRequest.copy(candidateLimit = 1),
                { permit.copy(maxImages = 1 - imageCharges) }, { _, images -> imageCharges += images },
                { _, _, _ -> error("transaction rolled back") })
            assertTrue(outcome.failed)
            assertEquals(1, imageCharges) // paid attempt is not refunded by rollback
            assertFalse(file.exists())
        } finally { file.delete() }
    }

    @Test fun committedImageRemainsAndImageQuotaStopsFollowingRequests() = runTest {
        val file = java.io.File.createTempFile("annotation-image", ".png")
        var imageCharges = 0
        var calls = 0
        try {
            coEvery { client.chat(any(), any()) } answers {
                val quote = if (++calls == 1) scenic else scenicToo
                """{"quote":"$quote","note":"评论","image_prompt":"配图"}"""
            }
            coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
                assertEquals(1, imageCharges)
                com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "配图", imagePath = file.path, createdAt = 0)
            }
            val outcome = service.generateForChapter(scenicRequest.copy(imagesPerChapter = 2),
                { permit.copy(maxImages = 1 - imageCharges) }, { _, images -> imageCharges += images }, { _, _, _ -> true })
            assertFalse(outcome.failed)
            assertEquals(1, imageCharges)
            coVerify(exactly = 1) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue(file.exists())
        } finally { file.delete() }
    }

    @Test fun lateResultIsDroppedByTransactionalSink() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论"}"""
        val result = service.generateForChapter(request, { permit }, { _, _ -> }, { _, _, _ -> false })
        assertTrue(result.stopped)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }

    @Test fun promptSpeaksAsThePersonaNotAsAnAnonymousEditor() {
        // 用户反馈段评「没有角色性格」：以前系统提示词只说「你是随读段评编辑」，人设整段没进提示词。
        val roleplay = persona.copy(
            isRoleplay = true,
            personality = "冷淡的图书馆管理员，嘴上挑剔，实际很在意读者的感受",
            speakingStyle = "短句，偶尔带一点讽刺",
            exampleDialogsJson = """[{"user":"这本书好看吗","assistant":"先读到第三章再问我。"}]"""
        )
        val messages = proactiveAnnotationMessages(roleplay, "正文前缀\n最后一段")
        val system = messages.first().content.orEmpty()
        assertTrue(system.startsWith("你是「知墨」。"))
        assertTrue(system.contains(roleplay.personality))
        assertTrue(system.contains(roleplay.speakingStyle))
        assertTrue(system.contains("先读到第三章再问我。"))
        assertTrue(system.contains("用你自己的口吻和性格写"))
        assertFalse(system.contains("你是随读段评编辑"))
        // 写作规则仍然完整：样式语义、防剧透与 JSON 契约一个都不能丢。
        assertTrue(system.contains("HIGHLIGHT（荧光）：金句"))
        assertTrue(system.contains("绝不推测后文"))
        assertTrue(system.contains("只输出一个 JSON 对象"))
        // 正文只在用户消息里，且以目标段落结尾；角色资料不再混进正文消息。
        val user = messages.last().content.orEmpty()
        assertTrue(user.endsWith("最后一段"))
        assertFalse(user.contains(roleplay.personality))
        // 超长人设按成本上限收口，而不是原样塞给 CHEAP 模型。
        val huge = proactiveAnnotationMessages(roleplay.copy(personality = "长".repeat(10_000)), "前缀").first().content.orEmpty()
        assertTrue(huge.length < PROACTIVE_PERSONA_MAX_CHARS + 1_200)
    }

    @Test fun changedPreviousChaptersStopBeforePaidCall() = runTest {
        coEvery { contextRepository.isCurrent(any(), any()) } returns false
        val outcome = service.generateForChapter(request, { permit }, { _, _ -> }, { _, _, _ -> error("must not commit") })
        assertTrue(outcome.stopped)
        coVerify(exactly = 0) { client.chat(any(), any()) }
    }

    @Test fun sourceChangedDuringGenerationCannotPublishStaleContext() = runTest {
        coEvery { client.chat(any(), any()) } answers {
            coEvery { contextRepository.isCurrent(any(), any()) } returns false
            """{"quote":"$first","note":"旧版前文"}"""
        }
        val outcome = service.generateForChapter(request, { permit }, { _, _ -> }, { _, _, _ -> error("must not commit") })
        assertTrue(outcome.stopped)
    }

    @Test fun previousChapterEvidenceIsSentAlongsideTargetWithoutReadingSuffix() = runTest {
        val evidence = "首段原文的伏笔：顾衡曾许诺重回雪岭"
        coEvery { contextRepository.prepare(any(), any()) } returns PreparedAnnotationContext("revision",
            listOf(com.mozhi.reader.core.retrieval.RetrievalCandidate(1, 0, 0, evidence, 0, evidence.length)))
        val messages = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(messages), any()) } returns """{"quote":"$first","note":"呼应前文"}"""
        val result = service.generateForChapter(request.copy(candidateLimit = 1), { permit }, { _, _ -> }, { _, _, _ -> true })
        assertFalse(result.failed)
        val text = messages.single().last().content.orEmpty()
        assertTrue(text.contains(evidence))
        assertTrue(text.contains("第 1 章"))
        assertFalse(text.contains(second))
    }

    @Test fun quoteWithPunctuationDriftIsAnchoredToTheSourceText() = runTest {
        val dialogue = "她低声说：“明天，我们就走。”窗外的雨一直没有停下来，他望着灯火发呆了很久很久，直到天色完全暗下来。"
        coEvery { client.chat(any(), any()) } returns """{"quote":"她低声说: \"明天, 我们就走.\"","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(ProactiveAnnotationRequest(1, 3, dialogue, persona, 1, emptySet()),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertFalse(result.failed)
        val row = rows.single()
        assertEquals("她低声说：“明天，我们就走。”", row.selectedText)
        assertEquals(row.selectedText, dialogue.substring(row.startCharOffset, row.endCharOffset))
    }

    @Test fun badOutputOnThePrimaryParagraphFallsBackToTheSlotAlternate() = runTest {
        val strong = "“你到底想怎样？！”他吼道，声音在空荡的走廊里回响了很久，没人回答他的问题，只有远处的钟声。"
        val plain = "走廊尽头的窗户半开着，冷风吹进来，地上的落叶被卷起又落下，四周安静得出奇，像是整栋楼都睡着了。"
        val chapter = plain + "\n" + strong
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } answers {
            val target = requests.last().last().content.orEmpty().substringAfterLast("唯一目标段落：\n")
            if (target.startsWith("“")) "抱歉，我无法完成" else """{"quote":"冷风吹进来，地上的落叶","note":"评论"}"""
        }
        val rows = mutableListOf<AnnotationEntity>()
        val result = service.generateForChapter(ProactiveAnnotationRequest(1, 3, chapter, persona, 1, emptySet()),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertEquals(2, requests.size)
        assertFalse(result.failed)
        assertEquals("冷风吹进来，地上的落叶", rows.single().selectedText)
        assertEquals(plain.length, rows.single().sourceScopeCharOffset)
    }

    @Test fun generationStartsAtTheReadersCurrentPage() = runTest {
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } returns """{"quote":"$second","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        service.generateForChapter(request.copy(startAt = first.length + 1), { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertEquals(1, requests.size)
        assertEquals(second, rows.single().selectedText)
    }

    @Test fun slotsTheReaderHasAlreadyPassedAreSkippedWithoutCountingAsFailures() = runTest {
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } returns """{"quote":"$second","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        // 生成开始时读者还在章首，第一格之前读者已经翻到第二段。
        val result = service.generateForChapter(request.copy(readerOffset = { first.length + 1 }),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        assertEquals(1, requests.size)
        assertFalse(result.failed)
        assertEquals(second, rows.single().selectedText)
        assertEquals(setOf(body.length), result.visitedParagraphEnds)
    }

    @Test fun pictureGoesToTheMostVisualParagraphAndOnlyThatRequestAsksForOne() = runTest {
        val file = java.io.File.createTempFile("annotation-image", ".png")
        try {
            val requests = mutableListOf<List<ChatMessage>>()
            coEvery { client.chat(capture(requests), any()) } answers {
                val target = requests.last().last().content.orEmpty().substringAfterLast("唯一目标段落：\n")
                """{"quote":"$target","note":"评论"}"""
            }
            val prompts = mutableListOf<String>()
            coEvery { media.generateIllustration(any(), any(), any(), any(), capture(prompts), any(), any(), any(), any()) } returns
                com.mozhi.reader.core.database.entity.IllustrationEntity(bookId = 1, prompt = "配图", imagePath = file.path, createdAt = 0)
            val withImage = mutableListOf<String>()
            val mixed = "$first\n$scenic"
            val outcome = service.generateForChapter(request.copy(body = mixed, imagesPerChapter = 1),
                { permit.copy(maxImages = 1) }, { _, _ -> },
                { row, _, image -> if (image != null) withImage += row.selectedText; true })
            assertFalse(outcome.failed)
            // 模型没写画面，就用原文这一段去画；没被选中的段落连字段都不提。
            assertEquals(listOf(scenic), prompts)
            assertEquals(listOf(scenic), withImage)
            val (plainAsk, scenicAsk) = requests.map { it.first().content.orEmpty() }
            assertFalse("image_prompt" in plainAsk)
            assertTrue("image_prompt" in scenicAsk)
        } finally { file.delete() }
    }

    @Test fun pictureTheModelVolunteersForAnUnchosenParagraphIsNeverGenerated() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$first","note":"评论","image_prompt":"配图"}"""
        service.generateForChapter(request.copy(candidateLimit = 1, imagesPerChapter = 1),
            { permit.copy(maxImages = 3) }, { _, _ -> error("no media charge") }, { _, _, _ -> true })
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun missingImageServiceAsksForNoPictureAndChargesNothing() = runTest {
        coEvery { media.illustrationBackendReady() } returns false
        val requests = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(requests), any()) } answers {
            val target = requests.last().last().content.orEmpty().substringAfterLast("唯一目标段落：\n")
            """{"quote":"$target","note":"评论","image_prompt":"配图"}"""
        }
        var commits = 0
        service.generateForChapter(scenicRequest, { permit.copy(maxImages = 3) },
            { _, _ -> error("no media charge") }, { _, _, _ -> commits++; true })
        assertEquals(2, commits)
        assertFalse(requests.any { "image_prompt" in it.first().content.orEmpty() })
        coVerify(exactly = 1) { media.illustrationBackendReady() }
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun chapterPictureCapCountsPicturesOtherCompanionsAlreadyMade() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$scenic","note":"评论"}"""
        service.generateForChapter(scenicRequest.copy(candidateLimit = 1, chapterImageCount = { 1 }),
            { permit.copy(maxImages = 3) }, { _, _ -> error("no media charge") }, { _, _, _ -> true })
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun failedPictureIsReportedAndTheAnnotationStillCommits() = runTest {
        coEvery { client.chat(any(), any()) } returns """{"quote":"$scenic","note":"评论"}"""
        coEvery { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            IllegalStateException("image service down")
        var failures = 0
        var charges = 0
        val images = mutableListOf<com.mozhi.reader.core.database.entity.IllustrationEntity?>()
        val outcome = service.generateForChapter(scenicRequest.copy(candidateLimit = 1),
            { permit.copy(maxImages = 1) }, { _, count -> charges += count },
            { _, _, image -> images += image; true }, { failures++ })
        assertFalse(outcome.failed)
        assertEquals(1, charges)
        assertEquals(1, failures)
        assertEquals(listOf(null), images)
    }

    @Test fun voiceIsOnlyMentionedWhenThisCompanionCanSpeak() {
        val silent = proactiveAnnotationMessages(persona, "目标段落正文", voiceBrief = false).first().content.orEmpty()
        val speaking = proactiveAnnotationMessages(persona, "目标段落正文", voiceBrief = true, imageBrief = true).first().content.orEmpty()
        assertFalse("voice" in silent.substringAfter("【随读段评】"))
        assertFalse("image_prompt" in silent)
        assertTrue("voice（布尔值）" in speaking)
        assertTrue("image_prompt（字符串）" in speaking)
        assertTrue("不写后文" in speaking)
    }

    private fun peer(paragraph: ProactiveAnnotationParagraph, chapter: String = body, id: Long = 9) = ProactivePeerNote(
        annotationId = id, personaId = 8, personaName = "照夜", personaTraits = "敏锐 直言",
        start = paragraph.start, end = paragraph.end, quote = chapter.substring(paragraph.start, paragraph.end),
        note = "我觉得这里还有未说出口的犹豫。", hasPersonaReply = false
    )

    @Test fun interplayRepliesFirstThenWritesOnlyOnFreeParagraphsInTheNextBucket() = runTest {
        val texts = listOf("甲段原文".repeat(20), "乙段原文".repeat(20), "丙段原文".repeat(15), "丁段秘密".repeat(15))
        val chapter = texts.joinToString("\n")
        val buckets = ProactiveAnnotationParagraphs.generationBuckets(chapter, 2)
        assertEquals(listOf(2, 2), buckets.map { it.size })
        val peers = buckets.mapIndexed { index, bucket -> peer(bucket.first(), chapter, 10L + index) }
        val sent = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(sent), any()) } returnsMany listOf(
            """{"reply":"照夜，也可能是他还没等到那个约定的信号。"}""",
            """{"quote":"${texts.last()}","note":"自己的段评"}"""
        )
        val replies = mutableListOf<Pair<ProactivePeerNote, Int>>()
        val rows = mutableListOf<AnnotationEntity>()
        val outcome = service.generateForChapter(request.copy(body = chapter, interplay = true, peers = peers),
            { permit }, { _, _ -> error("no media") }, { row, _, _ -> rows += row; true },
            commitReply = { peer, text, end ->
                assertTrue(text.startsWith("照夜"))
                replies += peer to end
                true
            })
        assertFalse(outcome.failed)
        assertEquals(listOf(peers.first() to buckets.first().first().end), replies)
        assertEquals(listOf(texts.last()), rows.map { it.selectedText })
        assertTrue(rows.all { row -> peers.none { it.start < row.endCharOffset && it.end > row.startCharOffset } })
        val user = sent.first().last().content.orEmpty()
        assertTrue(user.contains(peers.first().personaName))
        assertTrue(user.contains(peers.first().note))
        assertTrue(user.contains(peers.first().quote))
        texts.drop(1).forEach { assertFalse(user.contains(it)) }
        assertFalse(sent.first().any { "voice" in it.content.orEmpty() || "image_prompt" in it.content.orEmpty() })
    }

    @Test fun explicitNoReplySkipsParagraphWithoutAnyCommitOrFailure() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        coEvery { client.chat(any(), any()) } returns """{"reply":null}"""
        val skipped = mutableListOf<Set<Int>>()
        val outcome = service.generateForChapter(request.copy(candidateLimit = 1, interplay = true, peers = listOf(peer(target))),
            { permit }, { _, _ -> error("no media") }, { _, _, _ -> error("no annotation") },
            commitReply = { _, _, _ -> error("no reply") }, skip = { skipped += it })
        assertFalse(outcome.failed)
        assertFalse(outcome.stopped)
        assertEquals(listOf(setOf(target.end)), skipped)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }

    @Test fun fullyOccupiedBucketWithPersonaRepliesSkipsAllEndsWithoutCallingModel() = runTest {
        val targets = ProactiveAnnotationParagraphs.split(body)
        val skipped = mutableListOf<Set<Int>>()
        val outcome = service.generateForChapter(request.copy(candidateLimit = 1, interplay = true,
            peers = targets.map { peer(it).copy(hasPersonaReply = true) }),
            { permit }, { _, _ -> error("no media") }, { _, _, _ -> error("no annotation") },
            commitReply = { _, _, _ -> error("no reply") }, skip = { skipped += it })
        assertEquals(listOf(targets.map { it.end }.toSet()), skipped)
        assertFalse(outcome.failed)
        assertEquals(targets.map { it.end }.toSet(), outcome.visitedParagraphEnds)
        coVerify(exactly = 0) { client.chat(any(), any()) }
        coVerify(exactly = 0) { contextRepository.prepare(any(), any()) }
    }

    @Test fun chapterReplyCapIncludesPreviousPassesAndSkipsDoNotSpendSlots() = runTest {
        val chapter = (0..11).joinToString("\n") { "段落$it：" + "原文内容。".repeat(10) }
        val targets = ProactiveAnnotationParagraphs.split(chapter)
        val peers = targets.mapIndexed { index, target -> peer(target, chapter, index.toLong()) }
        coEvery { client.chat(any(), any()) } returns """{"reply":"另一个角度是他仍在等人。"}"""
        var replies = 0
        val skipped = mutableSetOf<Int>()
        val interplay = request.copy(body = chapter, candidateLimit = Int.MAX_VALUE, interplay = true, peers = peers)
        val outcome = service.generateForChapter(interplay, { permit }, { _, _ -> }, { _, _, _ -> error("occupied") },
            commitReply = { _, _, _ -> replies++; true }, skip = { skipped += it })
        assertEquals(maxOf(1, targets.size / 4), replies)
        assertEquals(targets.size - replies, skipped.size)
        assertFalse(outcome.failed)
        skipped.clear()
        val capped = service.generateForChapter(interplay.copy(repliesSoFar = replies, maxSlotsPerPass = 1),
            { permit }, { _, _ -> }, { _, _, _ -> error("occupied") },
            commitReply = { _, _, _ -> error("cap reached") }, skip = { skipped += it })
        assertFalse(capped.hasMore)
        assertFalse(capped.failed)
        assertEquals(targets.map { it.end }.toSet(), skipped)
        coVerify(exactly = 3) { client.chat(any(), any()) }
    }

    @Test fun disabledInterplayIgnoresPeersAndKeepsTheExactOriginalMessagesAndRows() = runTest {
        val sent = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(sent), any()) } returns """{"quote":"$first","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        val plain = request.copy(candidateLimit = 1)
        service.generateForChapter(plain, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true })
        service.generateForChapter(plain.copy(peers = listOf(peer(ProactiveAnnotationParagraphs.split(body).first())),
            repliesSoFar = 10, interplay = false),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true },
            commitReply = { _, _, _ -> error("disabled") }, skip = { error("disabled") })
        assertEquals(sent.first(), sent.last())
        assertEquals(rows.first().copy(createdAt = 0), rows.last().copy(createdAt = 0))
    }

    @Test fun replyOnAnImageTargetNeverInjectsPresetsOrRequestsMedia() = runTest {
        val target = ProactiveAnnotationParagraphs.split(scenicBody).first()
        assertTrue(target.end in ProactiveAnnotationImagery.targets(scenicBody,
            ProactiveAnnotationParagraphs.generationBuckets(scenicBody, 1), 1))
        val sent = mutableListOf<List<ChatMessage>>()
        coEvery { client.chat(capture(sent), any()) } returns """{"reply":"照夜，我更在意她回头的动作。","voice":true,"image_prompt":"图"}"""
        var replies = 0
        service.generateForChapter(scenicRequest.copy(candidateLimit = 1, interplay = true, peers = listOf(peer(target, scenicBody)),
            persona = persona.copy(voiceId = "voice"),
            prompts = listOf(com.mozhi.reader.core.datastore.GlobalPromptPreset("custom", "预设", "禁止注入的预设", true))),
            { permit.copy(maxVoice = 1, maxImages = 1) }, { _, _ -> error("no media charge") }, { _, _, _ -> error("no annotation") },
            imageFailed = { error("no image attempt") }, commitReply = { _, _, _ -> replies++; true })
        assertEquals(1, replies)
        assertFalse(sent.single().any { message ->
            listOf("voice", "image_prompt", "禁止注入的预设", "段评预设").any { it in message.content.orEmpty() }
        })
        coVerify(exactly = 0) { media.illustrationBackendReady() }
        coVerify(exactly = 0) { media.generateIllustration(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { media.synthesizeSpeech(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun unparseableReplyFailsTheBucketWithoutTryingItsAlternate() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        coEvery { client.chat(any(), any()) } returns "not json"
        val outcome = service.generateForChapter(request.copy(candidateLimit = 1, interplay = true, peers = listOf(peer(target))),
            { permit }, { _, _ -> }, { _, _, _ -> error("no alternate") },
            commitReply = { _, _, _ -> error("no reply") }, skip = { error("not silence") })
        assertTrue(outcome.failed)
        assertFalse(outcome.stopped)
        assertEquals(ProactiveAnnotationParagraphs.split(body).map { it.end }.toSet(), outcome.visitedParagraphEnds)
        coVerify(exactly = 1) { client.chat(any(), any()) }
    }

    @Test fun replyObeysReaderFrontierDoneVisitedAndPassSlotLimit() = runTest {
        val targets = ProactiveAnnotationParagraphs.split(body)
        val peers = targets.mapIndexed { index, target -> peer(target, id = index.toLong()) }
        val base = request.copy(interplay = true, peers = peers, maxSlotsPerPass = 1)
        coEvery { client.chat(any(), any()) } returns """{"reply":"我还想起了前文的约定。"}"""
        val ends = mutableListOf<Int>()
        val firstPass = service.generateForChapter(base, { permit }, { _, _ -> }, { _, _, _ -> error("occupied") },
            commitReply = { _, _, end -> ends += end; true })
        // 一次接话已经到上限，下一格无空段落，直接跳过，不再占一格。
        assertFalse(firstPass.hasMore)
        assertEquals(listOf(targets.first().end), ends)
        listOf(base.copy(readerOffset = { targets.last().start }), base.copy(doneParagraphEnds = setOf(targets.first().end)),
            base.copy(visitedParagraphEnds = setOf(targets.first().end))).forEach { resumed ->
            service.generateForChapter(resumed, { permit }, { _, _ -> }, { _, _, _ -> error("occupied") },
                commitReply = { _, _, end -> ends += end; true })
        }
        assertEquals(listOf(targets.first().end) + List(3) { targets.last().end }, ends)
    }

    @Test fun skipDoesNotPreventAFreeSlotInTheSamePass() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        coEvery { client.chat(any(), any()) } returns """{"quote":"$second","note":"评论"}"""
        val rows = mutableListOf<AnnotationEntity>()
        val skipped = mutableSetOf<Int>()
        val outcome = service.generateForChapter(request.copy(interplay = true, maxSlotsPerPass = 1,
            peers = listOf(peer(target).copy(hasPersonaReply = true))), { permit }, { _, _ -> },
            { row, _, _ -> rows += row; true }, skip = { skipped += it })
        assertFalse(outcome.failed)
        assertFalse(outcome.hasMore)
        assertEquals(setOf(target.end), skipped)
        assertEquals(listOf(second), rows.map { it.selectedText })
    }

    @Test fun overlappingPeerCannotLeakItsQuoteOrSourcePastTheTarget() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        val skipped = mutableSetOf<Int>()
        val unsafe = listOf(peer(target).copy(end = body.length, quote = body),
            peer(target).copy(sourceEnd = body.length, note = second))
        for (peer in unsafe) {
            service.generateForChapter(request.copy(interplay = true, peers = listOf(peer),
                doneParagraphEnds = setOf(body.length)), { permit }, { _, _ -> }, { _, _, _ -> error("occupied") },
                commitReply = { _, _, _ -> error("future context") }, skip = { skipped += it })
        }
        assertEquals(setOf(target.end), skipped)
        coVerify(exactly = 0) { client.chat(any(), any()) }
    }

    @Test fun replyChecksAllowanceAndContextBeforePaidCallAndRejectsStaleOutput() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        val base = request.copy(interplay = true, peers = listOf(peer(target)), candidateLimit = 1)
        var checks = 0
        val revoked = service.generateForChapter(base, { if (++checks == 1) permit else null }, { _, _ -> },
            { _, _, _ -> error("no annotation") })
        assertTrue(revoked.stopped)
        coVerify(exactly = 0) { client.chat(any(), any()) }
        coEvery { client.chat(any(), any()) } answers {
            coEvery { contextRepository.isCurrent(any(), any()) } returns false
            """{"reply":"迟到的回复"}"""
        }
        val stale = service.generateForChapter(base, { permit }, { _, _ -> }, { _, _, _ -> error("no annotation") },
            commitReply = { _, _, _ -> error("stale") })
        assertTrue(stale.stopped)
    }

    @Test fun replyTimeoutAndRequestFailureUseTheSameRetryBudgetAsAnnotations() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        val base = request.copy(interplay = true, peers = listOf(peer(target)))
        for (timeout in listOf(true, false)) {
            var calls = 0
            coEvery { client.chat(any(), any()) } coAnswers {
                if (++calls == 1) {
                    if (timeout) delay(PROACTIVE_ANNOTATION_TEXT_TIMEOUT_MS + 1) else error("request failed")
                }
                """{"quote":"$second","note":"评论"}"""
            }
            val rows = mutableListOf<AnnotationEntity>()
            val outcome = service.generateForChapter(base, { permit }, { _, _ -> }, { row, _, _ -> rows += row; true },
                commitReply = { _, _, _ -> error("failed reply") })
            assertTrue(outcome.failed)
            assertFalse(outcome.stopped)
            assertEquals(2, calls)
            assertEquals(listOf(second), rows.map { it.selectedText })
        }
    }

    @Test fun replyMessagesKeepBackgroundOrderAndOmitEmptyTraitsParentheses() {
        val peer = peer(ProactiveAnnotationParagraphs.split(body).first()).copy(personaTraits = "")
        val messages = proactiveReplyMessages(persona, peer, first, first, "前文证据")
        val user = messages.last().content.orEmpty()
        assertTrue(user.startsWith("相关前文资料（均早于本章）：\n前文证据\n\n正文前缀"))
        assertTrue(user.contains("「照夜」划的原文：\n$first"))
        assertFalse(user.contains("（）"))
        assertTrue(messages.first().content.orEmpty().contains("你、「照夜」和用户都在书外一起阅读"))
    }

    @Test fun successfulReplyConsumesOnePassSlotAndResumesWithAnOwnAnnotation() = runTest {
        val target = ProactiveAnnotationParagraphs.split(body).first()
        val base = request.copy(interplay = true, peers = listOf(peer(target)), maxSlotsPerPass = 1)
        coEvery { client.chat(any(), any()) } returnsMany listOf("""{"reply":"再看看前文的约定。"}""",
            """{"quote":"$second","note":"自己的段评"}""")
        val firstPass = service.generateForChapter(base, { permit }, { _, _ -> }, { _, _, _ -> error("must yield") },
            commitReply = { _, _, _ -> true })
        assertTrue(firstPass.hasMore)
        assertEquals(setOf(target.end), firstPass.visitedParagraphEnds)
        val rows = mutableListOf<AnnotationEntity>()
        val next = service.generateForChapter(base.copy(doneParagraphEnds = setOf(target.end),
            visitedParagraphEnds = firstPass.visitedParagraphEnds, repliesSoFar = 1),
            { permit }, { _, _ -> }, { row, _, _ -> rows += row; true }, commitReply = { _, _, _ -> error("already replied") })
        assertFalse(next.hasMore)
        assertFalse(next.failed)
        assertEquals(listOf(second), rows.map { it.selectedText })
        coVerify(exactly = 1) { contextRepository.prepare(1, 3) }
    }
}
