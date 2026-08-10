package com.mozhi.reader.feature.importer

import com.mozhi.reader.ai.client.AiClientFactory
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ChatMessage
import com.mozhi.reader.ai.client.ChatRole
import com.mozhi.reader.core.database.entity.ModelRole
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AiChapterRuleProposal(
    val name: String,
    val regex: String,
    val reason: String,
    val chapterCount: Int,
    val sampleTitles: List<String>
)

/**
 * 用批量任务模型探寻章节标题正则，并把每轮结果在本地全文验证；任何结果都只形成
 * 待确认提案，绝不直接改写导入会话。
 */
class AiChapterRuleAgent @Inject constructor(
    private val clientFactory: AiClientFactory,
    private val sessionStore: ImportSessionStore,
    private val chapterSplitter: TxtChapterSplitter
) {
    suspend fun propose(
        sessionId: String,
        onAttempt: (Int) -> Unit = {}
    ): AiChapterRuleProposal {
        val session = withContext(Dispatchers.IO) {
            requireNotNull(sessionStore.get(sessionId)) { "导入会话已失效" }
        }
        val structuralSample = withContext(Dispatchers.Default) {
            AiChapterRuleSampler.sample(session.text)
        }
        val resolved = clientFactory.forRole(ModelRole.CHEAP)
        val messages = mutableListOf(
            ChatMessage(ChatRole.SYSTEM, SYSTEM_PROMPT),
            ChatMessage(
                ChatRole.USER,
                buildString {
                    appendLine("请为这份 TXT 探寻章节标题正则。")
                    appendLine("当前本地规则：${session.splitResult.rule?.name ?: "按字数分节"}")
                    appendLine("当前结果：${session.splitResult.chapters.size} 节")
                    appendLine("下方仅是去除正文语义后的行结构样本，0/汉/A 是脱敏占位符：")
                    append(structuralSample)
                }
            )
        )
        var lastFailure = "模型没有返回可用规则"

        repeat(MAX_ATTEMPTS) { attemptIndex ->
            onAttempt(attemptIndex + 1)
            val response = resolved.client.chat(
                messages = messages,
                options = resolved.options.copy(
                    temperature = 0.1f,
                    maxTokens = resolved.options.maxTokens ?: 900
                )
            )
            val draft = AiChapterRuleResponseParser.parse(response)
            if (draft == null) {
                lastFailure = "返回内容不是约定的 JSON，或缺少 name、regex、reason"
            } else {
                when (val validation = withContext(Dispatchers.Default) {
                    AiChapterRuleValidator.validate(session.text, draft.regex, chapterSplitter)
                }) {
                    is AiChapterRuleValidation.Valid -> return AiChapterRuleProposal(
                        name = draft.name,
                        regex = draft.regex,
                        reason = draft.reason,
                        chapterCount = validation.result.chapters.size,
                        sampleTitles = validation.result.chapters
                            .asSequence()
                            .filterNot { it.title == "序章" }
                            .map(TxtChapter::title)
                            .take(6)
                            .toList()
                    )
                    is AiChapterRuleValidation.Invalid -> lastFailure = validation.reason
                }
            }

            messages += ChatMessage(ChatRole.ASSISTANT, response)
            messages += ChatMessage(
                ChatRole.USER,
                "本地全文验证失败：$lastFailure。请修正规则后仍只输出约定 JSON。"
            )
        }
        error("AI 连续 $MAX_ATTEMPTS 次未找到可靠规则：$lastFailure")
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
        val SYSTEM_PROMPT = """
            你是 TXT 章节结构识别代理。根据跨越开头、中部、末尾的脱敏行结构样本，推断一个 Kotlin/Java 正则。
            正则会以 MULTILINE 模式运行，必须用 ^ 和 $ 匹配完整标题行，不得匹配正文；控制在 400 字符内。
            JSON 中的反斜杠必须正确转义。若标题存在多种稳定格式，可用非捕获分组合并。
            只输出单个 JSON 对象，不要 Markdown、注释或额外文字：
            {"name":"简短规则名","regex":"^...$","reason":"一句话说明判断依据"}
        """.trimIndent()
    }
}

internal data class AiChapterRuleDraft(
    val name: String,
    val regex: String,
    val reason: String
)

internal object AiChapterRuleResponseParser {
    fun parse(response: String): AiChapterRuleDraft? = runCatching {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        require(start >= 0 && end > start)
        val root = AiJson.parseToJsonElement(response.substring(start, end + 1)).jsonObject
        val name = root["name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(40)
        val regex = root["regex"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val reason = root["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().take(240)
        require(name.isNotBlank() && regex.isNotBlank() && reason.isNotBlank())
        AiChapterRuleDraft(name, regex, reason)
    }.getOrNull()
}

internal sealed interface AiChapterRuleValidation {
    data class Valid(val result: TxtSplitResult) : AiChapterRuleValidation
    data class Invalid(val reason: String) : AiChapterRuleValidation
}

internal object AiChapterRuleValidator {
    fun validate(
        text: String,
        regex: String,
        splitter: TxtChapterSplitter
    ): AiChapterRuleValidation {
        if (regex.length > 400) return AiChapterRuleValidation.Invalid("正则超过 400 字符")
        if (!regex.trimStart().startsWith("^") || !regex.trimEnd().endsWith('$')) {
            return AiChapterRuleValidation.Invalid("正则必须以 ^ 开头并以 $ 结尾")
        }
        if (NESTED_QUANTIFIER.containsMatchIn(regex)) {
            return AiChapterRuleValidation.Invalid("正则包含可能造成灾难性回溯的嵌套量词")
        }
        val compiled = runCatching { Regex(regex, RegexOption.MULTILINE) }.getOrElse {
            return AiChapterRuleValidation.Invalid("正则无法编译：${it.message.orEmpty()}")
        }
        val firstMatches = runCatching { compiled.findAll(text).take(MAX_CHAPTERS + 1).toList() }
            .getOrElse { return AiChapterRuleValidation.Invalid("正则执行失败：${it.message.orEmpty()}") }
        if (firstMatches.size < 2) return AiChapterRuleValidation.Invalid("全文只匹配到 ${firstMatches.size} 个标题")
        if (firstMatches.size > MAX_CHAPTERS) return AiChapterRuleValidation.Invalid("匹配结果超过 $MAX_CHAPTERS 个，疑似误中正文")
        if (firstMatches.any { it.value.trim().length !in 1..80 }) {
            return AiChapterRuleValidation.Invalid("规则命中了空行或超过 80 字的正文行")
        }

        val result = splitter.splitWithCustomRegex(text, regex)
            ?: return AiChapterRuleValidation.Invalid("规则未形成至少两个有效章节")
        val contentChapters = result.chapters.filterNot { it.title == "序章" }
        val tooShortRatio = contentChapters.count { it.charCount < 20 }.toFloat() /
            contentChapters.size.coerceAtLeast(1)
        if (contentChapters.size >= 5 && tooShortRatio > 0.35f) {
            return AiChapterRuleValidation.Invalid("超过三分之一章节正文不足 20 字，疑似匹配过宽")
        }
        val distinctRatio = contentChapters.map { it.title }.distinct().size.toFloat() /
            contentChapters.size.coerceAtLeast(1)
        if (distinctRatio < 0.6f) {
            return AiChapterRuleValidation.Invalid("章节标题重复过多，规则不可靠")
        }
        return AiChapterRuleValidation.Valid(result)
    }

    private val NESTED_QUANTIFIER = Regex("\\([^)]*[+*][^)]*\\)[+*{]")
    private const val MAX_CHAPTERS = 20_000
}

/** 只向模型发送行的结构形状，不发送原始正文或标题语义。 */
internal object AiChapterRuleSampler {
    fun sample(text: String): String {
        if (text.isBlank()) return "（空文本）"
        val anchors = listOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        return anchors.mapIndexed { index, fraction ->
            val center = (text.length * fraction).toInt().coerceIn(0, text.length)
            val start = (center - WINDOW_CHARS / 2).coerceIn(0, text.length)
            val end = (start + WINDOW_CHARS).coerceAtMost(text.length)
            val candidates = text.substring(start, end)
                .lineSequence()
                .map(String::trim)
                .filter { it.length in 1..MAX_LINE_CHARS }
                .map { line -> line to headingLikelihood(line) }
                .sortedByDescending { it.second }
                .take(LINES_PER_WINDOW)
                .map { (line, _) -> redact(line) }
                .toList()
            "【样本 ${index + 1}/5 · 位置 ${(fraction * 100).toInt()}%】\n" +
                candidates.joinToString("\n")
        }.joinToString("\n\n")
    }

    private fun headingLikelihood(line: String): Int {
        var score = 0
        if (line.length <= 40) score += 3
        if (HEADING_MARKERS.containsMatchIn(line)) score += 6
        if (line.any(Char::isDigit)) score += 2
        if (line.firstOrNull()?.let { it in "=-—●◆【〔（(" } == true) score += 2
        if (line.lastOrNull()?.let { it in "=】〕）)" } == true) score += 2
        if (line.lastOrNull()?.let { it in "。！？；.!?;" } == true) score -= 5
        return score
    }

    private fun redact(line: String): String {
        val maskedWords = WORD.findAll(line).associate { match ->
            match.range.first to if (match.value.lowercase() in ENGLISH_MARKERS) match.value else "A"
        }
        val output = StringBuilder(line.length)
        var index = 0
        while (index < line.length) {
            val word = maskedWords[index]
            if (word != null) {
                val originalLength = WORD.find(line, index)?.value?.length ?: 1
                output.append(word)
                index += originalLength
                continue
            }
            val char = line[index]
            output.append(
                when {
                    char.isDigit() -> '0'
                    char in STRUCTURAL_CJK -> char
                    char.code in 0x4E00..0x9FFF -> '汉'
                    char.isLetter() -> 'A'
                    else -> char
                }
            )
            index++
        }
        return output.toString()
    }

    private val HEADING_MARKERS = Regex("第.+[章节卷回部篇集]|chapter|part|volume|prologue|epilogue", RegexOption.IGNORE_CASE)
    private val WORD = Regex("[A-Za-z]+")
    private val ENGLISH_MARKERS = setOf("chapter", "part", "volume", "book", "prologue", "epilogue")
    private const val STRUCTURAL_CJK = "第章节卷回部篇集序幕终楔引后前番话一二三四五六七八九十百千万零两"
    private const val WINDOW_CHARS = 4_000
    private const val LINES_PER_WINDOW = 18
    private const val MAX_LINE_CHARS = 80
}
