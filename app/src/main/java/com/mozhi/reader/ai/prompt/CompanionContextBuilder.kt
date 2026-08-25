package com.mozhi.reader.ai.prompt

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.core.database.entity.ModelRole
import com.mozhi.reader.core.database.entity.PersonaEntity
import com.mozhi.reader.core.database.entity.exampleDialogs
import com.mozhi.reader.core.database.entity.worldBook
import com.mozhi.reader.core.datastore.ReaderSettingsRepository
import com.mozhi.reader.core.datastore.UserMask
import com.mozhi.reader.core.datastore.UserMaskStore
import com.mozhi.reader.core.library.LibraryRepository
import com.mozhi.reader.core.vector.Embeddings
import com.mozhi.reader.core.vector.VectorQueries
import io.objectbox.BoxStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

/** 组装系统提示词用的书籍进度快照。 */
data class BookProgress(
    val title: String,
    val author: String,
    val totalChapters: Int,
    /** 0 起的当前章节索引。 */
    val currentChapterIndex: Int,
    val currentChapterTitle: String?
)

/**
 * 本轮对话的呈现形态，决定要不要告诉模型「输出会被逐行拆成气泡」以及「可以发语音」。
 *
 * 纪律：能力关着就**一个字都不写**。告诉模型可以发语音、结果应用不给合成，
 * 只会让它输出永远兑现不了的标记（与「工具关了就不注册」同一条）。
 */
data class ConversationShape(
    val multiBubble: Boolean = false,
    /** 语音开关已开 **且** 当前角色绑定了音色，两者缺一都是 false。 */
    val voiceEnabled: Boolean = false
) {
    val active: Boolean get() = multiBubble || voiceEnabled
}

/**
 * 伴读上下文构建器（DEVELOPMENT_PLAN §4.5）：每次请求前组装系统提示词——
 * 人设 → 书籍进度 → 防剧透边界 → 主动记忆 → 场景原文，
 * 按「字符数/2 ≈ token」做预算控制，超限先弃记忆、再截场景，人设与防剧透永不裁。
 *
 * 防剧透在这里只是「声明」层；硬过滤在 search_book 的查询层与 embedding 管线两端各有一道。
 */
@Singleton
class CompanionContextBuilder @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val clientFactory: dagger.Lazy<AiClientFactory>,
    private val vectorStore: dagger.Lazy<BoxStore>,
    private val userMaskStore: UserMaskStore,
    private val settingsRepository: ReaderSettingsRepository
) {
    /**
     * @param scene 调用方备好的场景原文（选段及其邻域，或章节开头），可空
     * @param memoryQuery 用于主动记忆检索的文本（通常是用户最新一条输入），可空
     */
    suspend fun build(
        persona: PersonaEntity?,
        bookId: Long?,
        scene: String? = null,
        memoryQuery: String? = null,
        spoilerProtectionEnabled: Boolean = true,
        conversationShape: ConversationShape = ConversationShape(),
        budgetChars: Int = DEFAULT_BUDGET_CHARS
    ): String {
        val progress = bookId?.let { id ->
            libraryRepository.getBook(id)?.let { book ->
                BookProgress(
                    title = book.title,
                    author = book.author,
                    totalChapters = book.totalChapters,
                    currentChapterIndex = book.lastReadChapterIndex,
                    currentChapterTitle =
                        libraryRepository.getChapterTitle(id, book.lastReadChapterIndex)
                )
            }
        }
        val userMask = userMaskStore.activeMask()
        val memorySettings = settingsRepository.companionMemorySettings.first()
        val memories = if (
            persona != null &&
            persona.memoryEnabled &&
            memorySettings.longTermEnabled &&
            !memoryQuery.isNullOrBlank()
        ) {
            retrieveMemories(
                personaId = persona.id,
                query = memoryQuery,
                // 关闭跨书记忆＝把召回收窄到当前书；跨书的全局记忆（bookId 为 null）
                // 同样不参与，这正是该开关的语义。
                bookId = bookId.takeUnless { memorySettings.crossBookEnabled },
                maskId = userMask?.id ?: 0L
            )
        } else {
            emptyList()
        }
        return assemble(
            persona = persona,
            userMask = userMask,
            progress = progress,
            scene = scene,
            memories = memories,
            userProfile = persona
                ?.takeIf { it.memoryEnabled && memorySettings.longTermEnabled }
                ?.userProfile
                .orEmpty(),
            spoilerProtectionEnabled = spoilerProtectionEnabled,
            conversationShape = conversationShape,
            // 关键词触发的世界书条目拿「场景原文 + 用户最新输入」当命中材料。
            loreTrigger = listOfNotNull(scene, memoryQuery).joinToString("\n"),
            budgetChars = budgetChars
        )
    }

    /** 记忆是增益项：embedding 未配置、检索失败一律静默降级为空。 */
    private suspend fun retrieveMemories(
        personaId: Long,
        query: String,
        bookId: Long?,
        maskId: Long
    ): List<String> = try {
        val resolved = clientFactory.get().forRole(ModelRole.EMBEDDING)
        val vector = Embeddings.conformToIndex(resolved.client.embed(listOf(query)).first())
        VectorQueries.searchMemories(
            vectorStore.get(),
            personaId,
            vector,
            MEMORY_TOP_K,
            bookId,
            maskId
        ).map { it.get().summary }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    companion object {
        /** ≈ 8k token（按字符数/2 估算）。 */
        const val DEFAULT_BUDGET_CHARS = 16_000
        const val SCENE_MAX_CHARS = 2_000
        const val MEMORY_TOP_K = 5

        private const val SEPARATOR = "\n\n"

        /** 纯组装，便于单测。[loreTrigger] 是关键词触发条目的命中材料。 */
        fun assemble(
            persona: PersonaEntity?,
            userMask: UserMask? = null,
            progress: BookProgress?,
            scene: String?,
            memories: List<String>,
            userProfile: String = "",
                spoilerProtectionEnabled: Boolean = true,
            conversationShape: ConversationShape = ConversationShape(),
            loreTrigger: String = "",
            budgetChars: Int = DEFAULT_BUDGET_CHARS
        ): String {
            val fixedBlocks = listOfNotNull(
                personaBlock(persona, loreTrigger),
                // 画像与人设同级：它是「你认识的这个人是谁」，裁掉它角色立刻变得陌生。
                userProfileBlock(userProfile),
                userMaskBlock(userMask),
                progressBlock(progress),
                spoilerBlock(progress, spoilerProtectionEnabled),
                // 形态说明和人设同属「怎么说话」，永不参与预算裁剪——
                // 裁掉它模型就会写回长段落，用户看到的是开关时灵时不灵。
                conversationShapeBlock(conversationShape, progress)
            )
            val closing = buildString {
                append("回答使用简体中文。")
                if (progress != null) {
                    append("\n如需逐字引用书中原文，只能使用格式")
                    append("〔原文 第N章〕「逐字引文」；")
                    append("仅可标记从当前场景或书籍工具结果中逐字复制、确认存在的内容，")
                    append("转述、概括、角色对白示例和普通强调不得使用此标记。")
                }
            }
            var memoryBlock = memoryBlock(memories)
            var sceneBlock = sceneBlock(scene, SCENE_MAX_CHARS)

            fun render(): String =
                (fixedBlocks + listOfNotNull(memoryBlock, sceneBlock) + closing)
                    .joinToString(SEPARATOR)

            if (render().length > budgetChars) memoryBlock = null
            if (render().length > budgetChars && sceneBlock != null) {
                val roomForScene = budgetChars -
                    (fixedBlocks + closing).sumOf { it.length + SEPARATOR.length } -
                    SCENE_HEADER.length
                sceneBlock = if (roomForScene > MIN_SCENE_CHARS) {
                    sceneBlock(scene, roomForScene)
                } else {
                    null
                }
            }
            return render()
        }

        private fun personaBlock(persona: PersonaEntity?, loreTrigger: String): String {
            if (persona == null) {
                return "你是「墨知」阅读器的伴读助手，陪伴用户阅读并帮助他理解已读内容。"
            }
            return buildString {
                if (persona.isRoleplay) {
                    append("你是「").append(persona.name).append("」。").append(persona.personality)
                    if (persona.speakingStyle.isNotBlank()) {
                        append("\n说话风格：").append(persona.speakingStyle)
                    }
                    append("\n始终以这个身份说话，不要跳出人设谈论系统、模型或提示词。")
                } else {
                    append("你是「").append(persona.name).append("」，墨知阅读器的伴读助手。")
                    append(persona.personality)
                    if (persona.speakingStyle.isNotBlank()) {
                        append("\n回应风格：").append(persona.speakingStyle)
                    }
                    append("\n不代入虚构人格，聚焦帮助用户理解与整理已读内容。")
                }
                val dialogs = persona.exampleDialogs()
                if (dialogs.isNotEmpty()) {
                    append("\n【示例对话】")
                    dialogs.forEach { dialog ->
                        append("\n用户：").append(dialog.user)
                        append("\n").append(persona.name).append("：").append(dialog.assistant)
                    }
                }
                // 世界书：总开关 → 条目开关 → 注入方式（常驻直接进；触发式要命中材料）。
                val lore = if (persona.worldBookEnabled) {
                    persona.worldBook().filter { entry ->
                        entry.enabled && entry.content.isNotBlank() && (
                            entry.constant || entry.keys.any { key ->
                                key.isNotBlank() && loreTrigger.contains(key, ignoreCase = true)
                            }
                            )
                    }
                } else {
                    emptyList()
                }
                if (lore.isNotEmpty()) {
                    append("\n【设定集】")
                    lore.forEach { entry ->
                        append("\n- ")
                        if (entry.name.isNotBlank()) append(entry.name).append("：")
                        append(entry.content)
                    }
                }
            }
        }

        private fun userProfileBlock(userProfile: String): String? = userProfile
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let { "【关于用户】你在过往交流中积累的了解：\n" + it }

        private fun userMaskBlock(mask: UserMask?): String? = mask?.let {
            buildString {
                append("【用户面具】本次对话中，用户以「")
                    .append(it.name)
                    .append("」的身份参与。")
                if (it.description.isNotBlank()) {
                    append("\n用户人设：").append(it.description)
                }
                append("\n这是用户的身份设定，不是你的角色设定；称呼、理解和回应用户时应尊重它，")
                append("但不要替用户擅自决定言行，也不要主动复述这段系统说明。")
            }
        }

        private fun progressBlock(progress: BookProgress?): String? = progress?.let {
            buildString {
                append("用户正在阅读《").append(it.title).append("》")
                if (it.author.isNotBlank()) append("（作者 ").append(it.author).append("）")
                append("，全书共 ").append(it.totalChapters).append(" 章，")
                append("当前读到第 ").append(it.currentChapterIndex + 1).append(" 章")
                it.currentChapterTitle?.takeIf(String::isNotBlank)?.let { title ->
                    append("「").append(title).append("」")
                }
                append("。")
            }
        }

        private fun spoilerBlock(
            progress: BookProgress?,
            spoilerProtectionEnabled: Boolean
        ): String? = progress?.takeIf { spoilerProtectionEnabled }?.let {
            "【防剧透铁律】仅讨论用户已读至第 ${it.currentChapterIndex + 1} 章的内容，不得透露或暗示后续情节。"
        }

        /**
         * 输出形态说明。两项能力各自独立成句：只开语音时不该逼模型分行，
         * 只开多气泡时也不该让它以为可以发语音。
         */
        private fun conversationShapeBlock(
            shape: ConversationShape,
            progress: BookProgress?
        ): String? {
            if (!shape.active) return null
            return buildString {
                append("【对话形态】你正在和用户共读")
                append(progress?.title?.let { "《$it》" } ?: "这本书")
                append("，像真人在聊天窗口里说话。")
                if (shape.multiBubble) {
                    append("\n- 你的输出会按行拆成独立气泡：一行 = 一个气泡，每行 1~3 句，不要写大段落。")
                    append("\n- 需要整段呈现的内容（长篇分析、列表、表格、代码），用 [整段] 和 [/整段] 各占一行包起来，")
                    append("其间的换行不会拆泡，你照常分行写就行。")
                }
                if (shape.voiceEnabled) {
                    append("\n- 想用语音说的那一行，在行首加 [语音]，它会合成为语音消息。")
                    append("整段回复最多两行这样标记，情绪浓、适合说出口的短句才用，分析和罗列一律用文字。")
                }
            }
        }

        private fun memoryBlock(memories: List<String>): String? =
            memories.takeIf { it.isNotEmpty() }?.let { list ->
                "【长期记忆】你与用户过往交流中的相关记忆：\n" +
                    list.joinToString("\n") { "- $it" }
            }

        private const val SCENE_HEADER = "【当前场景】用户所读位置附近的原文：\n"
        private const val MIN_SCENE_CHARS = 200

        private fun sceneBlock(scene: String?, maxChars: Int): String? =
            scene?.trim()?.takeIf(String::isNotEmpty)?.let { text ->
                SCENE_HEADER + text.take(maxChars)
            }
    }
}
