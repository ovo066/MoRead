package com.mozhi.reader.ai.agent

import com.mozhi.reader.ai.client.ToolCall
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * 同一轮里模型请求的多个工具调用：相邻的只读调用并发执行，写入类调用逐个执行。
 *
 * 结果按模型给出的调用顺序返回（各家协议都要求工具结果与调用一一对应），
 * 进度回调则按真实发生的先后到达——界面看得到哪几个查询在同时跑。
 * [progress] 始终在调用方协程里执行，所以 `flow {}` 里可以直接 emit。
 */
internal class ParallelToolRunner(private val maxParallel: Int = MAX_PARALLEL_TOOLS) {

    sealed interface Progress {
        val index: Int

        data class Started(override val index: Int) : Progress
        data class Finished(override val index: Int, val result: ToolResult) : Progress
    }

    suspend fun run(
        calls: List<ToolCall>,
        concurrencySafe: (ToolCall) -> Boolean,
        execute: suspend (ToolCall) -> ToolResult,
        progress: suspend (Progress) -> Unit
    ): List<ToolResult> {
        val results = arrayOfNulls<ToolResult>(calls.size)
        for (batch in batches(calls, concurrencySafe)) {
            batch.forEach { progress(Progress.Started(it)) }
            if (batch.size == 1) {
                val index = batch.single()
                val result = execute(calls[index])
                results[index] = result
                progress(Progress.Finished(index, result))
                continue
            }
            coroutineScope {
                val permits = Semaphore(maxParallel.coerceAtLeast(1))
                val done = Channel<Pair<Int, ToolResult>>(batch.size)
                batch.forEach { index ->
                    launch { done.send(index to permits.withPermit { execute(calls[index]) }) }
                }
                // 在本协程里收结果并回调；某个调用抛出取消会连带取消整段。
                repeat(batch.size) {
                    val (index, result) = done.receive()
                    results[index] = result
                    progress(Progress.Finished(index, result))
                }
            }
        }
        return results.map { requireNotNull(it) }
    }

    companion object {
        const val MAX_PARALLEL_TOOLS = 4

        /** 相邻的并发安全调用合成一段；其余调用各自成段，保持模型给出的先后。 */
        fun batches(calls: List<ToolCall>, concurrencySafe: (ToolCall) -> Boolean): List<List<Int>> {
            val batches = mutableListOf<List<Int>>()
            var current = mutableListOf<Int>()
            calls.forEachIndexed { index, call ->
                if (concurrencySafe(call)) {
                    current += index
                } else {
                    if (current.isNotEmpty()) batches += current
                    current = mutableListOf()
                    batches += listOf(index)
                }
            }
            if (current.isNotEmpty()) batches += current
            return batches
        }
    }
}

/**
 * 只读工具：可以与同一轮的其他只读调用并发执行。写入类工具（批注、笔记、梗概、
 * 生成媒体、阅读计划、书库整理）不在名单里，一律串行，避免两次写入交错。
 */
internal object ConcurrencySafeTools {
    val NAMES = setOf(
        "get_reading_progress", "search_book", "grep_book", "read_book_section",
        "list_chapters", "list_annotations", "list_notes", "recall_memory",
        "web_search", "web_scrape", "find_books", "count_mentions", "create_chart"
    )
}
