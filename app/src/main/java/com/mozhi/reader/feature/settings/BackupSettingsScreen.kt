package com.mozhi.reader.feature.settings

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.MainActivity
import com.mozhi.reader.core.backup.BackupArchiveManager
import com.mozhi.reader.core.backup.BackupManifest
import com.mozhi.reader.core.backup.BackupSettings
import com.mozhi.reader.core.backup.RemoteBackup
import com.mozhi.reader.ui.components.FrostedSurface
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.text.DateFormat
import java.util.Date

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var directory by remember { mutableStateOf("MoRead") }
    var formDirty by remember { mutableStateOf(false) }
    var restoreCandidate by remember { mutableStateOf<RemoteBackup?>(null) }
    var restoreReady by remember { mutableStateOf<BackupManifest?>(null) }

    LaunchedEffect(
        state.settings.webDavUrl,
        state.settings.username,
        state.settings.remoteDirectory
    ) {
        if (!formDirty) {
            url = state.settings.webDavUrl
            username = state.settings.username
            directory = state.settings.remoteDirectory
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is BackupSettingsEvent.Message -> snackbar.showSnackbar(event.text)
                is BackupSettingsEvent.RestoreReady -> restoreReady = event.manifest
            }
        }
    }
    val localExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::exportLocal) }
    val localImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::restoreLocal)
    }
    fun form() = BackupSettings(
        webDavUrl = url,
        username = username,
        remoteDirectory = directory,
        autoBackup = state.settings.autoBackup,
        lastBackupAt = state.settings.lastBackupAt
    )

    MoReadBackdrop {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text("数据备份", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    Text(
                        "备份包含书库、进度、批注、对话、预设、书籍文件与插图。API Key、WebDAV 密码和可重建的向量索引不会写入备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                item {
                    LocalBackupCard(
                        enabled = !state.working,
                        onExport = { localExport.launch(BackupArchiveManager.backupFileName()) },
                        onRestore = { localImport.launch(arrayOf("application/zip", "application/octet-stream")) }
                    )
                }
                item {
                    WebDavConfigCard(
                        url = url,
                        username = username,
                        password = password,
                        directory = directory,
                        hasPassword = state.hasPassword,
                        autoBackup = state.settings.autoBackup,
                        enabled = !state.working,
                        onUrl = { url = it; formDirty = true },
                        onUsername = { username = it; formDirty = true },
                        onPassword = { password = it; formDirty = true },
                        onDirectory = { directory = it; formDirty = true },
                        onAutoBackup = viewModel::setAutoBackup,
                        onClearPassword = viewModel::clearPassword,
                        onSave = {
                            viewModel.save(form(), password)
                            password = ""
                            formDirty = false
                        },
                        onTest = { viewModel.test(form(), password) }
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            enabled = !state.working,
                            onClick = { viewModel.backupNow(form(), password) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("立即上传", modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(
                            enabled = !state.working,
                            onClick = viewModel::refreshRemote,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("云端备份", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
                if (state.working) item { CircularProgressIndicator(Modifier.size(28.dp)) }
                items(state.remoteBackups, key = RemoteBackup::name) { backup ->
                    RemoteBackupRow(backup = backup, onRestore = { restoreCandidate = backup })
                }
            }
        }
        SnackbarHost(hostState = snackbar, modifier = Modifier.padding(20.dp))
    }

    restoreCandidate?.let { backup ->
        AlertDialog(
            onDismissRequest = { restoreCandidate = null },
            title = { Text("恢复云端备份？") },
            text = { Text("恢复后会替换当前书库与设置，并自动重启应用。建议先导出一份当前数据。") },
            confirmButton = {
                TextButton(onClick = { restoreCandidate = null; viewModel.restoreRemote(backup) }) {
                    Text("继续恢复")
                }
            },
            dismissButton = { TextButton(onClick = { restoreCandidate = null }) { Text("取消") } }
        )
    }
    restoreReady?.let { manifest ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("备份已准备好") },
            text = {
                Text("备份来自墨知 ${manifest.appVersion}。应用将重启并恢复数据，请勿在重启过程中强制关闭。")
            },
            confirmButton = {
                TextButton(onClick = { scheduleRestart(context) }) { Text("立即重启") }
            }
        )
    }
}

@Composable
private fun LocalBackupCard(enabled: Boolean, onExport: () -> Unit, onRestore: () -> Unit) {
    FrostedSurface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), shadowElevation = 5.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("本地备份", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = enabled, onClick = onExport, modifier = Modifier.weight(1f)) { Text("导出备份") }
                OutlinedButton(enabled = enabled, onClick = onRestore, modifier = Modifier.weight(1f)) { Text("从文件恢复") }
            }
        }
    }
}

@Composable
private fun WebDavConfigCard(
    url: String,
    username: String,
    password: String,
    directory: String,
    hasPassword: Boolean,
    autoBackup: Boolean,
    enabled: Boolean,
    onUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onDirectory: (String) -> Unit,
    onAutoBackup: (Boolean) -> Unit,
    onClearPassword: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit
) {
    FrostedSurface(Modifier.fillMaxWidth(), RoundedCornerShape(22.dp), shadowElevation = 5.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WebDAV", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, onUrl, label = { Text("服务器地址（HTTPS）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(username, onUsername, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                password,
                onPassword,
                label = { Text(if (hasPassword) "密码（已保存，留空不修改）" else "密码或应用专用密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(directory, onDirectory, label = { Text("远程目录") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("每日自动备份", modifier = Modifier.weight(1f))
                Switch(checked = autoBackup, onCheckedChange = onAutoBackup)
            }
            if (hasPassword) {
                TextButton(onClick = onClearPassword, modifier = Modifier.align(Alignment.End)) { Text("清除已保存密码") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled = enabled, onClick = onSave, modifier = Modifier.weight(1f)) { Text("保存") }
                OutlinedButton(enabled = enabled, onClick = onTest, modifier = Modifier.weight(1f)) { Text("测试连接") }
            }
        }
    }
}

@Composable
private fun RemoteBackupRow(backup: RemoteBackup, onRestore: () -> Unit) {
    FrostedSurface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onRestore),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 3.dp
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(backup.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    buildString {
                        if (backup.modifiedAt > 0) append(DateFormat.getDateTimeInstance().format(Date(backup.modifiedAt)))
                        if (backup.size > 0) append(" · ").append(formatBytes(backup.size))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("恢复", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun scheduleRestart(context: Context) {
    val intent = Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    val pending = PendingIntent.getActivity(
        context,
        9021,
        intent,
        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val alarm = context.getSystemService(AlarmManager::class.java)
    alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 500, pending)
    Process.killProcess(Process.myPid())
}
