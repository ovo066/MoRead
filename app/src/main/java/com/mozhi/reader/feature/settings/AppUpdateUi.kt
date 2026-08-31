package com.mozhi.reader.feature.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.BuildConfig
import com.mozhi.reader.core.update.AppUpdateInstaller

@Composable
fun AppUpdatePrompt(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var dismissedTag by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppUpdateEvent.Install -> {
                    if (!AppUpdateInstaller.launch(context, event.file)) {
                        Toast.makeText(context, "授权后请再次点击安装", Toast.LENGTH_LONG).show()
                    }
                }
                is AppUpdateEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }
    val release = state.update.available?.takeIf { it.tag != dismissedTag } ?: return
    AlertDialog(
        onDismissRequest = { dismissedTag = release.tag },
        title = { Text("发现新版本 ${release.tag}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(release.title, style = MaterialTheme.typography.titleSmall)
                if (release.notes.isNotBlank()) {
                    Text(
                        release.notes,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.update.downloading) {
                    val progress = state.update.downloadProgress
                    if (progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !state.update.downloading,
                onClick = viewModel::downloadOrInstall
            ) {
                Text(if (state.update.downloadedApk?.isFile == true) "安装" else "下载并安装")
            }
        },
        dismissButton = {
            TextButton(onClick = { dismissedTag = release.tag }) { Text("稍后") }
        }
    )
}

@Composable
internal fun AppUpdateCard(viewModel: AppUpdateViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is AppUpdateEvent.Install -> {
                    if (!AppUpdateInstaller.launch(context, event.file)) {
                        Toast.makeText(context, "授权后请再次点击安装", Toast.LENGTH_LONG).show()
                    }
                }
                is AppUpdateEvent.Message -> Toast.makeText(context, event.text, Toast.LENGTH_LONG).show()
            }
        }
    }
    val update = state.update
    // 外层素面卡由 MoReadSection 提供，这里只出内容，免得卡中套卡。
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("应用更新", style = MaterialTheme.typography.titleSmall)
                    Text(
                        when {
                            update.available != null -> "可更新到 ${update.available.tag}"
                            update.upToDate -> "当前 ${BuildConfig.VERSION_NAME} 已是最新版本"
                            update.error != null -> update.error
                            else -> "当前版本 ${BuildConfig.VERSION_NAME}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (update.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    enabled = !update.checking && !update.downloading,
                    onClick = if (update.available != null) viewModel::downloadOrInstall else viewModel::checkNow
                ) {
                    Text(
                        when {
                            update.checking -> "检查中"
                            update.downloading -> "下载中"
                            update.downloadedApk?.isFile == true -> "安装"
                            update.available != null -> "更新"
                            else -> "检查"
                        }
                    )
                }
            }
            if (update.checking || update.downloading) {
                val progress = update.downloadProgress
                if (progress != null && update.downloading) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("启动时自动检查", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = state.autoCheck, onCheckedChange = viewModel::setAutoCheck)
        }
    }
}
