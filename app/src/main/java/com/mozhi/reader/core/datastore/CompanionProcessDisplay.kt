package com.mozhi.reader.core.datastore

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 伴读「过程」（思考 + 工具调用链）的显示程度。 */
enum class CompanionProcessMode {
    /** 完全不显示；生成中只留一行状态，免得用户以为卡住了。 */
    HIDDEN,
    /** 默认：折叠成一行摘要，点开才看细节。 */
    COMPACT,
    /** 编码 agent 式逐步时间线，默认展开。 */
    DETAILED
}

@Serializable
data class CompanionProcessDisplay(
    val mode: CompanionProcessMode = CompanionProcessMode.COMPACT,
    val showReasoning: Boolean = true,
    val showArguments: Boolean = true,
    val showResults: Boolean = true,
    /** 生成时自动展开；展开区高度固定，不会把回答往下顶。 */
    val expandWhileStreaming: Boolean = false
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): CompanionProcessDisplay =
            raw?.let { runCatching { json.decodeFromString(serializer(), it) }.getOrNull() } ?: CompanionProcessDisplay()

        fun encode(value: CompanionProcessDisplay): String = json.encodeToString(serializer(), value)
    }
}
