package com.mozhi.reader.ai.agent

import android.util.Log
import com.mozhi.reader.ai.client.AiJson
import com.mozhi.reader.ai.client.ToolCall
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Shared by persistent and detached agent rounds. Only this boundary reports tool failures. */
class AgentToolExecutor internal constructor(private val report: (String) -> Unit) {
    @Inject constructor() : this({ diagnostic -> Log.w("AgentToolExecutor", diagnostic); Unit })

    suspend fun execute(call: ToolCall, tool: AgentTool?): ToolResult {
        if (tool == null) return ToolResult.Failure("UNKNOWN_TOOL", "未知工具：${call.name}")
        val arguments = try {
            AiJson.parseToJsonElement(call.arguments) as? JsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return ToolResult.Failure("INVALID_ARGUMENT", "工具参数必须是有效的 JSON 对象，请检查后重试。")

        val result = try {
            tool.execute(arguments)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult.Failure("EXECUTION_FAILED", "工具执行失败：${error.message ?: "未知错误"}", error)
        }
        when (result) {
            is ToolResult.Failure -> result.cause?.let { report(diagnostic(call, result.code, it)) }
            is ToolResult.Success -> result.diagnostics.forEach { report(diagnostic(call, it.code, it.cause)) }
        }
        return result
    }

    private fun diagnostic(call: ToolCall, code: String, error: Throwable): String = buildString {
        // Exception messages and tool arguments may contain book text, URLs or credentials.
        // Keep exception types and stack locations; payloads remain in the tool result only.
        append("call=").append(call.id.take(80).replace('\n', ' '))
        append(" tool=").append(call.name.take(80).replace('\n', ' '))
        append(" code=").append(code)
        var cause: Throwable? = error
        repeat(4) {
            val current = cause ?: return@buildString
            append('\n').append(current.javaClass.name)
            current.stackTrace.take(20).forEach { append("\n  at ").append(it) }
            cause = current.cause?.takeUnless { it === current }
        }
    }
}
