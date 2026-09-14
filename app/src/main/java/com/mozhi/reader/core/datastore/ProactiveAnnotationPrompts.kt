package com.mozhi.reader.core.datastore

import kotlinx.serialization.json.Json

/** User-editable writing instructions, separate from the quote/JSON contract needed by the reader. */
object ProactiveAnnotationPrompts {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val DEFAULTS = listOf(
        GlobalPromptPreset(
            id = "annotation-voice", name = "随读口吻", enabled = true, builtIn = true,
            prompt = "用你自己的口吻和性格写：像你平时和用户说话那样，带着你的语气、态度和关注点，可以感慨、吐槽、提问或联想；不要写成客观的编辑评语，也不要复述或概括段落内容。一两句话，中文。"
        ),
        GlobalPromptPreset(
            id = "annotation-style", name = "划线风格", enabled = true, builtIn = true,
            prompt = """
                style 必须按这一处内容的语义选择一个，不能把整批段评固定为同一种，也不要随机轮换或为了凑齐三种而强行选择：
                HIGHLIGHT（荧光）：金句、精彩描写、值得回味的段落。
                WAVY（波浪线）：当前正文前缀中已能看出的线索、伏笔、暗线或前后呼应；不能据此断言未读剧情。
                UNDERLINE（直线）：知识点、典故、术语、需要记住的事实或解释。
                没有明显线索或知识点时才选 HIGHLIGHT，不要把它当作所有段评的固定样式。
            """.trimIndent()
        )
    )

    fun decode(raw: String?): List<GlobalPromptPreset> = raw?.let {
        runCatching { json.decodeFromString<List<GlobalPromptPreset>>(it) }.getOrNull()
    }?.distinctBy { it.id } ?: DEFAULTS

    fun encode(presets: List<GlobalPromptPreset>): String = json.encodeToString(presets)
}
