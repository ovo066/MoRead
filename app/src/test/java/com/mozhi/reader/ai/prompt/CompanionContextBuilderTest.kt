package com.mozhi.reader.ai.prompt

import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.PersonaExampleDialog
import com.mozhi.reader.core.database.entity.PersonaLoreEntry
import com.mozhi.reader.core.database.entity.encodeExampleDialogs
import com.mozhi.reader.core.database.entity.encodeWorldBook
import com.mozhi.reader.core.datastore.UserMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionContextBuilderTest {

    private val progress = BookProgress(
        title = "长安十二时辰",
        author = "马伯庸",
        totalChapters = 48,
        currentChapterIndex = 11,
        currentChapterTitle = "午正"
    )

    private fun persona(isRoleplay: Boolean) = PersonaEntity(
        id = 1,
        name = "阿翎",
        personality = "旅行写作者，敏感而温柔。",
        speakingStyle = "多用画面与比喻。",
        exampleDialogsJson = encodeExampleDialogs(
            listOf(PersonaExampleDialog(user = "这段为何紧张？", assistant = "因为时间在被收走。"))
        ),
        isRoleplay = isRoleplay,
        createdAt = 0
    )

    @Test
    fun roleplayPersonaKeepsCharacterAndRendersFewShot() {
        val prompt = CompanionContextBuilder.assemble(
            persona = persona(isRoleplay = true),
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertTrue(prompt.contains("你是「阿翎」。旅行写作者"))
        assertTrue(prompt.contains("说话风格：多用画面与比喻。"))
        assertTrue(prompt.contains("不要跳出人设"))
        assertTrue(prompt.contains("【示例对话】"))
        assertTrue(prompt.contains("阿翎：因为时间在被收走。"))
    }

    @Test
    fun toolPersonaStaysAssistantToned() {
        val prompt = CompanionContextBuilder.assemble(
            persona = persona(isRoleplay = false),
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertTrue(prompt.contains("墨知阅读器的伴读助手"))
        assertTrue(prompt.contains("不代入虚构人格"))
        assertFalse(prompt.contains("不要跳出人设"))
    }

    @Test
    fun spoilerRuleStatesCurrentChapterBound() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertTrue(prompt.contains("《长安十二时辰》"))
        assertTrue(prompt.contains("当前读到第 12 章「午正」"))
        assertTrue(prompt.contains("【防剧透铁律】你的知识范围截止到第 12 章"))
    }

    @Test
    fun noBookMeansNoProgressOrSpoilerBlocks() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = null,
            scene = null,
            memories = emptyList()
        )
        assertFalse(prompt.contains("防剧透"))
        assertFalse(prompt.contains("正在阅读"))
        assertTrue(prompt.contains("回答使用简体中文。"))
    }

    @Test
    fun userMaskDescribesUserWithoutReplacingAssistantPersona() {
        val prompt = CompanionContextBuilder.assemble(
            persona = persona(isRoleplay = true),
            userMask = UserMask(
                id = 2,
                name = "陆教授",
                description = "研究城市史，希望被称为教授。"
            ),
            progress = progress,
            scene = null,
            memories = emptyList()
        )

        assertTrue(prompt.contains("你是「阿翎」"))
        assertTrue(prompt.contains("【用户面具】"))
        assertTrue(prompt.contains("用户以「陆教授」的身份参与"))
        assertTrue(prompt.contains("不是你的角色设定"))
    }

    @Test
    fun toolGuidanceOnlyMentionsRegisteredTools() {
        val withSearch = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList(),
            toolNames = setOf("search_book")
        )
        assertTrue(withSearch.contains("search_book"))
        assertFalse(withSearch.contains("recall_memory"))

        val without = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertFalse(without.contains("【工具使用】"))
    }

    @Test
    fun webSearchGuidancePreservesSpoilerBoundary() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList(),
            toolNames = setOf("web_search")
        )

        assertTrue(prompt.contains("来源链接"))
        assertTrue(prompt.contains("严禁借联网搜索绕过防剧透范围"))
    }

    @Test
    fun webScrapeGuidanceUsesFullPageOnlyWhenNeeded() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList(),
            toolNames = setOf("web_search", "web_scrape")
        )

        assertTrue(prompt.contains("web_scrape"))
        assertTrue(prompt.contains("搜索摘要不足"))
    }

    @Test
    fun memoriesRenderAsBulletList() {
        val prompt = CompanionContextBuilder.assemble(
            persona = persona(isRoleplay = true),
            progress = progress,
            scene = null,
            memories = listOf("用户最喜欢的角色是张小敬", "用户不喜欢被剧透")
        )
        assertTrue(prompt.contains("【长期记忆】"))
        assertTrue(prompt.contains("- 用户最喜欢的角色是张小敬"))
        assertTrue(prompt.contains("- 用户不喜欢被剧透"))
    }

    @Test
    fun worldBookInjectsOnlyEnabledEntries() {
        val persona = persona(isRoleplay = true).copy(
            worldBookJson = encodeWorldBook(
                listOf(
                    PersonaLoreEntry(name = "真身", content = "角色是店主的化身。", enabled = true),
                    PersonaLoreEntry(name = "", content = "无名条目也要注入。", enabled = true),
                    PersonaLoreEntry(name = "秘密", content = "关掉的条目不得出现。", enabled = false)
                )
            )
        )
        val prompt = CompanionContextBuilder.assemble(
            persona = persona,
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertTrue(prompt.contains("【设定集】"))
        assertTrue(prompt.contains("- 真身：角色是店主的化身。"))
        assertTrue(prompt.contains("- 无名条目也要注入。"))
        assertFalse(prompt.contains("关掉的条目不得出现"))
    }

    @Test
    fun worldBookMasterSwitchDisablesAllEntries() {
        val persona = persona(isRoleplay = true).copy(
            worldBookEnabled = false,
            worldBookJson = encodeWorldBook(
                listOf(PersonaLoreEntry(name = "真身", content = "常驻条目。", enabled = true))
            )
        )
        val prompt = CompanionContextBuilder.assemble(
            persona = persona,
            progress = progress,
            scene = null,
            memories = emptyList()
        )
        assertFalse(prompt.contains("【设定集】"))
        assertFalse(prompt.contains("常驻条目"))
    }

    @Test
    fun keywordEntriesInjectOnlyWhenTriggerHits() {
        val persona = persona(isRoleplay = true).copy(
            worldBookJson = encodeWorldBook(
                listOf(
                    PersonaLoreEntry(
                        name = "望楼",
                        content = "望楼是长安的信息网络。",
                        constant = false,
                        keys = listOf("望楼", "烽燧")
                    )
                )
            )
        )
        val hit = CompanionContextBuilder.assemble(
            persona = persona,
            progress = progress,
            scene = "他抬头看见望楼上的旗语。",
            memories = emptyList(),
            loreTrigger = "他抬头看见望楼上的旗语。"
        )
        assertTrue(hit.contains("- 望楼：望楼是长安的信息网络。"))

        val miss = CompanionContextBuilder.assemble(
            persona = persona,
            progress = progress,
            scene = "街市喧闹如常。",
            memories = emptyList(),
            loreTrigger = "街市喧闹如常。"
        )
        assertFalse(miss.contains("望楼是长安的信息网络"))
    }

    @Test
    fun overBudgetDropsMemoriesBeforeTruncatingScene() {
        val scene = "场".repeat(1_000)
        val memories = List(5) { "记忆${it}".padEnd(200, '忆') }
        val fullLength = CompanionContextBuilder.assemble(
            persona = null, progress = progress, scene = scene,
            memories = memories
        ).length

        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = scene,
            memories = memories,
            budgetChars = fullLength - 100 // 挤掉一点：先弃记忆就够
        )
        assertFalse(prompt.contains("【长期记忆】"))
        assertTrue(prompt.contains("【当前场景】"))
        assertTrue(prompt.contains(scene.take(100)))
    }

    @Test
    fun sceneTruncatesToFitTightBudget() {
        val scene = "景".repeat(3_000)
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = scene,
            memories = emptyList(),
            budgetChars = 1_200
        )
        assertTrue(prompt.length <= 1_300) // 预算按分隔符近似，允许小抖动
        assertTrue(prompt.contains("【当前场景】"))
        // 防剧透与进度永不被裁。
        assertTrue(prompt.contains("【防剧透铁律】"))
        assertTrue(prompt.contains("当前读到第 12 章"))
    }

    @Test
    fun sceneCapsAtTwoThousandCharsEvenWithRoomySpace() {
        val scene = "多".repeat(5_000)
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = scene,
            memories = emptyList()
        )
        assertFalse(prompt.contains("多".repeat(2_001)))
        assertTrue(prompt.contains("多".repeat(2_000)))
    }

    @Test
    fun userProfileIsInjectedRightAfterThePersona() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList(),
            userProfile = "称呼：老周。偏爱冷峻的叙述，讨厌被剧透。"
        )

        assertTrue(prompt.contains("【关于用户】"))
        assertTrue(prompt.contains("称呼：老周"))
        // 画像排在进度之前——它属于「你在跟谁说话」，先于「读到哪了」。
        assertTrue(prompt.indexOf("【关于用户】") < prompt.indexOf("用户正在阅读"))
    }

    @Test
    fun blankProfileAddsNoBlock() {
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = null,
            memories = emptyList(),
            userProfile = "   "
        )

        assertFalse(prompt.contains("【关于用户】"))
    }

    /** 画像与人设同级：预算再紧也不能裁掉它，否则角色瞬间对用户变得陌生。 */
    @Test
    fun profileSurvivesEvenWhenSceneAndMemoriesAreSacrificed() {
        val profile = "称呼：老周。共读过《三体》与《活着》。"
        val prompt = CompanionContextBuilder.assemble(
            persona = null,
            progress = progress,
            scene = "景".repeat(3_000),
            memories = List(5) { "记忆${it}".padEnd(200, '忆') },
            userProfile = profile,
            budgetChars = 800
        )

        assertTrue(prompt.contains(profile))
        assertFalse(prompt.contains("【长期记忆】"))
    }
}
