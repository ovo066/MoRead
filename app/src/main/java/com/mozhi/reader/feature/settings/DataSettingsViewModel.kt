package com.mozhi.reader.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.database.entity.BookEntity
import com.mozhi.reader.core.storage.StorageCleanup
import com.mozhi.reader.core.storage.StorageRepository
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
