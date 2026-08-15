package com.mozhi.reader.feature.importer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mozhi.reader.core.importer.BatchImportScheduler
import com.mozhi.reader.core.importer.lan.LanBookServer
import com.mozhi.reader.core.importer.lan.LanReceivedFile
import com.mozhi.reader.core.importer.lan.LanServerState
import com.mozhi.reader.core.importer.lan.LanTransferService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import androidx.core.net.toUri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface LanTransferEvent {
    data class Message(val text: String) : LanTransferEvent
    data object Imported : LanTransferEvent
}

@HiltViewModel
class LanTransferViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: LanBookServer,
    private val batchImportScheduler: BatchImportScheduler
) : ViewModel() {

    val state: kotlinx.coroutines.flow.StateFlow<LanServerState> = server.state

    private val eventChannel = Channel<LanTransferEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    fun start() = LanTransferService.start(context)

    fun stop() = LanTransferService.stop(context)

    fun discard(file: LanReceivedFile) = server.removeReceived(file.path)

    /**
     * 把收件箱里的文件交给批量导入。导入成功后 Worker 会删掉临时文件，
     * 所以这里先把列表清空——它们已经不属于「待处理」了。
     */
    fun importAll() {
        val files = state.value.received
        if (files.isEmpty()) return
        viewModelScope.launch {
            batchImportScheduler.enqueue(
                uris = files.map { File(it.path).toUri() },
                deleteSourceAfterImport = true
            )
            // 只从界面列表移除，文件删除交给 Worker：导入失败时文件还在收件箱里。
            server.forgetReceived()
            eventChannel.send(LanTransferEvent.Message("已开始导入 ${files.size} 个文件"))
            eventChannel.send(LanTransferEvent.Imported)
        }
    }

    override fun onCleared() {
        // 页面离开不自动停服务：用户可能想切到别的应用等电脑传完，停止由通知栏或页面按钮控制。
        super.onCleared()
    }
}
