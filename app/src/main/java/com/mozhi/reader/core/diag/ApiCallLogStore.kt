package com.mozhi.reader.core.diag

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * API 调用日志仓库：开关存 DataStore（默认关），记录保留最近 [MAX_ENTRIES] 条，
 * 内存态供界面订阅，同时以 JSONL 追加落盘（应用私有目录），后台任务发出的请求
 * 重启后也能回看。写入都在 IO 线程串行化，拦截器只投递不阻塞请求。
 */
@Singleton
class ApiCallLogStore @Inject constructor(
    @ApplicationContext context: Context,
    private val dataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ioMutex = Mutex()
    private val logFile = File(context.filesDir, "diagnostics/api-calls.jsonl")
    private var appendsSinceCompact = 0

    /** 拦截器热路径读的易失缓存；启动后由 DataStore 回填，切换开关时立即生效。 */
    @Volatile
    var recordingEnabled: Boolean = false
        private set

    val enabled: Flow<Boolean> = dataStore.data
        .map { it[Keys.Enabled] ?: false }
        .distinctUntilChanged()

    private val _entries = MutableStateFlow<List<ApiCallLogEntry>>(emptyList())

    /** 最近记录，旧 → 新；界面倒序展示。 */
    val entries: StateFlow<List<ApiCallLogEntry>> = _entries.asStateFlow()

    init {
        scope.launch { enabled.collect { recordingEnabled = it } }
        scope.launch {
            ioMutex.withLock {
                val loaded = ApiCallLogCodec
                    .decodeLines(runCatching { logFile.readText() }.getOrNull())
                    .takeLast(MAX_ENTRIES)
                // 锁内读文件：先于 record 则被其看到，晚于 record 则文件已含新行，两序皆全。
                _entries.value = loaded
            }
        }
    }

    /** 拦截器投递入口：立即返回，不在请求线程上做 IO。 */
    fun record(entry: ApiCallLogEntry) {
        scope.launch {
            ioMutex.withLock {
                _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
                runCatching {
                    logFile.parentFile?.mkdirs()
                    if (appendsSinceCompact >= COMPACT_EVERY || !logFile.exists()) {
                        logFile.writeText(
                            _entries.value.joinToString("\n", postfix = "\n") {
                                ApiCallLogCodec.encodeLine(it)
                            }
                        )
                        appendsSinceCompact = 0
                    } else {
                        logFile.appendText(ApiCallLogCodec.encodeLine(entry) + "\n")
                        appendsSinceCompact++
                    }
                }
            }
        }
    }

    suspend fun setEnabled(value: Boolean) {
        recordingEnabled = value
        dataStore.edit { it[Keys.Enabled] = value }
    }

    suspend fun clear() {
        ioMutex.withLock {
            _entries.value = emptyList()
            appendsSinceCompact = 0
            runCatching { logFile.delete() }
        }
    }

    private object Keys {
        val Enabled = booleanPreferencesKey("api_call_log_enabled")
    }

    private companion object {
        const val MAX_ENTRIES = 200
        /** 追加这么多行后重写整个文件，把超出上限的旧行裁掉。 */
        const val COMPACT_EVERY = 200
    }
}
