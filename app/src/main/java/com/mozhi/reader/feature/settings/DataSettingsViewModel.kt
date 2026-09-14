package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.storage.StorageCleanup
import com.mozhi.reader.core.storage.StorageRepository
import com.mozhi.reader.core.storage.StorageTextCompactionResult
import com.mozhi.reader.core.library.BookTextCompactionOutcome
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class DataSettingsViewModel @Inject constructor(private val storage: StorageRepository) : ViewModel() {
    val usage = storage.snapshot
    private val busy = MutableStateFlow(false)
    val working = busy.asStateFlow()
    private val messages = Channel<String>(Channel.BUFFERED)
    val events = messages.receiveAsFlow()

    fun refresh() = run { storage.refresh() }

    fun compactText() = run {
        messages.send(textCompactionMessage(storage.compactText()))
    }

    fun clean(kind: StorageCleanup) = run {
        val result = storage.clean(kind)
        messages.send("已释放 ${formatBytes(result.freedBytes)}" +
            if (result.skippedFiles > 0) "；${result.skippedFiles} 个文件已变化或未能删除，已跳过" else "")
    }

    fun clearSpeech(bookId: Long) = run {
        messages.send("已释放 ${formatBytes(storage.clearBookSpeech(bookId))} 语音文件，原文与记录保留")
    }

    fun disableIndex(bookId: Long) = run {
        storage.disableBookIndex(bookId)
        messages.send("已停用并删除本书索引；数据库空闲空间可供后续复用，不一定立即缩小")
    }

    fun remove(book: BookEntity, deleteRecords: Boolean) = run {
        storage.removeBook(book, deleteRecords)
        messages.send(if (deleteRecords) "书籍与个人记录已删除" else "正文已移除，个人记录已保留")
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { messages.send(error.message ?: "操作未完成，请重试") }
            finally { busy.value = false }
        }
    }
}

internal fun textCompactionMessage(result: StorageTextCompactionResult): String {
    val outcomes = result.books.map { it.outcome }
    val message = when {
        result.freedBytes > 0 -> "正文压缩完成，本次释放 ${formatBytes(result.freedBytes)}"
        outcomes.isEmpty() && result.failedBooks > 0 -> "正文压缩未完成，请稍后重试"
        outcomes.isEmpty() || outcomes.all { it == BookTextCompactionOutcome.MISSING } -> "没有可压缩的阅读正文"
        outcomes.all { it == BookTextCompactionOutcome.ALREADY_COMPRESSED } -> "阅读正文已经压缩，无需重复压缩"
        BookTextCompactionOutcome.TOO_LARGE in outcomes -> "部分正文超出单本压缩上限（512 MB），本次未释放空间"
        outcomes.all { it == BookTextCompactionOutcome.TOO_SMALL } -> "正文文件很小，压缩不会节省空间"
        else -> "正文已压缩或继续压缩不会更小，本次未额外释放空间"
    }
    return message + if (result.failedBooks > 0) "；${result.failedBooks} 本未完成，可重试" else ""
}
