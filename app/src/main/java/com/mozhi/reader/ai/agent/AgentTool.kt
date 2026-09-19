package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolSpec
import kotlinx.serialization.json.JsonObject

/**
 * A local capability the agent can invoke. [spec] is what the model sees; [displayName] is the
 * user-facing status line ("正在查询阅读进度"); [execute] runs on the caller's dispatcher and
 * returns an explicit outcome with a separate payload for the model.
 */
interface AgentTool {
    val spec: ToolSpec
    val displayName: String

    suspend fun execute(arguments: JsonObject): ToolResult
}
