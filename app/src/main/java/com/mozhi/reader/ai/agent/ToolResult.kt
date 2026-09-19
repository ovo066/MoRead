package com.mozhi.reader.ai.agent

/** Application control flow never depends on the language or contents of [content]. */
sealed interface ToolResult {
    val content: String

    data class Success(
        override val content: String,
        val partial: Boolean = false,
        val diagnostics: List<ToolDiagnostic> = emptyList()
    ) : ToolResult

    data class Failure(
        val code: String,
        override val content: String,
        val cause: Throwable? = null
    ) : ToolResult
}

/** A recovered failure can be diagnosed without turning useful fallback results into errors. */
data class ToolDiagnostic(val code: String, val cause: Throwable)

internal fun ToolResult.mapContent(transform: (String) -> String): ToolResult = when (this) {
    is ToolResult.Success -> copy(content = transform(content))
    is ToolResult.Failure -> copy(content = transform(content))
}
