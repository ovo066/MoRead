package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.speech.SpeechCacheStore
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

/**
 * 语音缓存页。缓存的意义只有一个：同一段文字不要重复付费合成。
 * 因此这一页只回答三个问题——占了多少、要不要同步到云、什么时候该清掉。
 */
@Composable
fun SpeechCacheScreen(
    onBack: () -> Unit,
    onOpenBackupSettings: () -> Unit,
    viewModel: SpeechCacheViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SpeechCacheEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    MoReadBackdrop {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "语音缓存",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${state.stats.fileCount} 段 · ${formatBytes(state.stats.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        SettingsGroup(title = "本地占用", icon = Icons.Outlined.GraphicEq) {
                            SettingsBlock(
                                title = "已用 ${formatBytes(state.stats.totalBytes)} / " +
                                    formatBytes(state.stats.budgetBytes),
                                subtitle = "AI 合成的语音会存下来，同一段文字再听不重复计费。"
                            ) {
                                LinearProgressIndicator(
                                    progress = { state.stats.usedFraction },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Slider(
                                    value = state.stats.budgetBytes.toFloat(),
                                    onValueChange = { value ->
                                        viewModel.setBudgetBytes(value.roundToLong())
                                    },
                                    valueRange = SpeechCacheStore.MIN_BUDGET_BYTES.toFloat()..
                                        SpeechCacheStore.MAX_BUDGET_BYTES.toFloat()
                                )
                                Text(
                                    "上限 ${formatBytes(state.stats.budgetBytes)}；" +
                                        "超出后按最久没听的先删。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SettingsRowDivider()
                            SettingsRow(
                                icon = Icons.Outlined.DeleteSweep,
                                title = "清空缓存",
                                subtitle = "下次朗读同样的内容会重新合成，产生新的 API 费用",
                                onClick = { confirmClear = true }
                            )
                        }
                    }

                    item {
                        SettingsGroup(title = "云端同步", icon = Icons.Outlined.CloudSync) {
                            if (!state.webDavConfigured) {
                                SettingsRow(
                                    icon = Icons.Outlined.CloudSync,
                                    title = "先配置 WebDAV",
                                    subtitle = "语音缓存与数据备份共用同一个账号，去「备份与同步」填一次即可",
                                    onClick = onOpenBackupSettings
                                )
                            } else {
                                SettingsBlock(
                                    title = "同步到云盘",
                                    subtitle = "文件名就是内容指纹，两端各补各缺的，不存在冲突。" +
                                        "换手机或重装后同一段语音不必再花一次钱。"
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = viewModel::syncNow,
                                            enabled = !state.syncing
                                        ) {
                                            Text(if (state.syncing) "正在同步…" else "立即同步")
                                        }
                                        if (state.stats.lastSyncAt > 0) {
                                            OutlinedButton(onClick = {}, enabled = false) {
                                                Text("上次 ${formatTime(state.stats.lastSyncAt)}")
                                            }
                                        }
                                    }
                                }
                                SettingsRowDivider()
                                SettingsSwitchRow(
                                    icon = Icons.Outlined.Wifi,
                                    title = "仅 Wi-Fi 自动同步",
                                    subtitle = "每 12 小时在不计费网络下同步一次；同步是为了省钱，不该吃流量",
                                    checked = state.stats.autoSyncOnWifi,
                                    onCheckedChange = viewModel::setAutoSync
                                )
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
            title = { Text("清空语音缓存？") },
            text = {
                Text(
                    "会删掉本机全部 ${state.stats.fileCount} 段合成语音（" +
                        "${formatBytes(state.stats.totalBytes)}）。" +
                        "已同步到云盘的那部分还能再拉回来，没同步的需要重新付费合成。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    confirmClear = false
                }) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
}

private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(epochMillis: Long): String = runCatching {
    timeFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}.getOrDefault("")
