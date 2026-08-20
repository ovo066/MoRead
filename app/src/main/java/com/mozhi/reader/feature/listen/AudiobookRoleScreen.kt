package com.mozhi.reader.feature.listen

import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.database.entity.AudiobookRoleEntity
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import com.mozhi.reader.core.library.AudiobookEngine
import com.mozhi.reader.core.library.AudiobookEnginePolicy
import com.mozhi.reader.core.library.AudiobookRoleKind
import com.mozhi.reader.ui.components.MoReadMenuItem
import com.mozhi.reader.ui.components.MoReadStableDropdownMenu
import com.mozhi.reader.ui.theme.MoReadTokens
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudiobookRoleScreen(
    onBack: () -> Unit,
    onContinue: (Long, Int) -> Unit,
    viewModel: AudiobookRoleViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AudiobookRoleEntity?>(null) }
    var policyMenu by remember { mutableStateOf(false) }
    PlayGeneratedPreview(state.previewPath, viewModel::consumePreview)

    val narrators = state.roles.filter { it.kind == AudiobookRoleKind.NARRATOR.name }
    val characters = state.roles.filter { it.kind != AudiobookRoleKind.NARRATOR.name }

    AudiobookPage(
        title = "角色与音色",
        subtitle = state.book?.title,
        onBack = onBack
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                AudiobookCard {
                    Text(
                        "第 1 步 · 定角色",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    AudiobookHint(
                        "先让程序把书里的说话人扒出来，再逐个挑音色。你手动改过的角色不会被后续识别覆盖。"
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.extract(false) },
                            enabled = !state.isWorking,
                            shape = MoReadTokens.CapsuleShape
                        ) {
                            Text("规则识别", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { viewModel.extract(true) },
                            enabled = !state.isWorking,
                            shape = MoReadTokens.CapsuleShape
                        ) {
                            AudiobookSmallIcon(Icons.Outlined.AutoAwesome, null)
                            Spacer(Modifier.size(6.dp))
                            Text("AI 识别", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    if (state.isWorking) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            AudiobookHint("正在识别…")
                        }
                    }
                    state.message?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (state.roles.isEmpty()) {
                item {
                    AudiobookCard {
                        Text(
                            "还没有角色",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AudiobookHint("点上面的「规则识别」先跑一遍；对白多的书用「AI 识别」更准。")
                    }
                }
            } else {
                item { AudiobookSectionTitle("旁白") }
                items(narrators, key = { it.id }) { role ->
                    RoleCard(role, state.voices, { editing = role }, viewModel::deleteRole, viewModel::preview)
                }
                item { AudiobookSectionTitle("角色", "${characters.size} 个") }
                items(characters, key = { it.id }) { role ->
                    RoleCard(role, state.voices, { editing = role }, viewModel::deleteRole, viewModel::preview)
                }
                item {
                    OutlinedButton(
                        onClick = viewModel::addRole,
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AudiobookSmallIcon(Icons.Outlined.Add, null)
                        Spacer(Modifier.size(6.dp))
                        Text("手动添加角色", style = MaterialTheme.typography.labelLarge)
                    }
                }
                item {
                    AudiobookCard {
                        Text(
                            "引擎策略",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(6.dp))
                        AudiobookHint("系统 TTS 不花钱、AI TTS 更像人。可以一键把所有角色切到同一档。")
                        Spacer(Modifier.height(10.dp))
                        Box {
                            OutlinedButton(
                                onClick = { policyMenu = true },
                                shape = MoReadTokens.CapsuleShape
                            ) {
                                AudiobookSmallIcon(Icons.Outlined.Tune, null)
                                Spacer(Modifier.size(6.dp))
                                Text("批量设置", style = MaterialTheme.typography.labelLarge)
                            }
                            MoReadStableDropdownMenu(
                                expanded = policyMenu,
                                onDismissRequest = { policyMenu = false },
                                width = 220.dp
                            ) {
                                AudiobookEnginePolicy.entries
                                    .filter { it != AudiobookEnginePolicy.CUSTOM }
                                    .forEach { policy ->
                                        MoReadMenuItem(
                                            text = policy.label(),
                                            onClick = {
                                                policyMenu = false
                                                viewModel.applyPolicy(policy)
                                            }
                                        )
                                    }
                            }
                        }
                    }
                }
                item {
                    AudiobookHint("下一步先校对当前阅读章节的分镜；确认后可继续选择多章批量制作。")
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { onContinue(viewModel.bookId, state.book?.lastReadChapterIndex ?: 0) },
                        enabled = !state.isWorking,
                        shape = MoReadTokens.CapsuleShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .navigationBarsPadding()
                    ) {
                        Text("确认角色，去排剧本", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }

    editing?.let { role ->
        RoleEditDialog(
            role = role,
            voices = state.voices,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveRole(it)
                editing = null
            }
        )
    }
}

@Composable
private fun RoleCard(
    role: AudiobookRoleEntity,
    voices: List<TtsVoiceEntity>,
    onEdit: () -> Unit,
    onDelete: (AudiobookRoleEntity) -> Unit,
    onPreview: (String) -> Unit
) {
    val ai = role.engine == AudiobookEngine.AI.name
    val voiceName = voices.firstOrNull { it.voiceId == role.voiceId }?.displayName
        ?: role.voiceId.ifBlank { "默认音色" }
    AudiobookCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    role.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${if (ai) "AI TTS" else "系统 TTS"} · $voiceName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (role.aliases.isNotBlank()) {
                    Text(
                        "别名：${role.aliases}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (ai) {
                IconButton(onClick = { onPreview(role.voiceId) }) {
                    AudiobookSmallIcon(Icons.Outlined.PlayArrow, "试听")
                }
            }
            IconButton(onClick = onEdit) {
                AudiobookSmallIcon(Icons.Outlined.Edit, "编辑")
            }
            if (role.kind != AudiobookRoleKind.NARRATOR.name) {
                IconButton(onClick = { onDelete(role) }) {
                    AudiobookSmallIcon(Icons.Outlined.Delete, "删除")
                }
            }
        }
    }
}

@Composable
private fun RoleEditDialog(
    role: AudiobookRoleEntity,
    voices: List<TtsVoiceEntity>,
    onDismiss: () -> Unit,
    onSave: (AudiobookRoleEntity) -> Unit
) {
    var name by remember(role.id) { mutableStateOf(role.name) }
    var aliases by remember(role.id) { mutableStateOf(role.aliases) }
    var gender by remember(role.id) { mutableStateOf(role.gender) }
    var engine by remember(role.id) { mutableStateOf(role.engine) }
    var voiceId by remember(role.id) { mutableStateOf(role.voiceId) }
    var voiceMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑角色", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("角色名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    aliases,
                    { aliases = it },
                    label = { Text("别名") },
                    supportingText = { Text("用逗号分隔，识别对白时一并匹配") },
                    modifier = Modifier.fillMaxWidth()
                )
                AudiobookHint("性别")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("MALE" to "男", "FEMALE" to "女", "UNSPECIFIED" to "未指定")
                        .forEach { (value, label) ->
                            FilterChip(
                                selected = gender == value,
                                onClick = { gender = value },
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                }
                AudiobookHint("朗读引擎")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = engine == AudiobookEngine.SYSTEM.name,
                        onClick = { engine = AudiobookEngine.SYSTEM.name },
                        label = { Text("系统 TTS", style = MaterialTheme.typography.labelMedium) }
                    )
                    FilterChip(
                        selected = engine == AudiobookEngine.AI.name,
                        onClick = { engine = AudiobookEngine.AI.name },
                        label = { Text("AI TTS", style = MaterialTheme.typography.labelMedium) }
                    )
                }
                if (engine == AudiobookEngine.AI.name) {
                    Box {
                        OutlinedButton(
                            onClick = { voiceMenu = true },
                            shape = MoReadTokens.CapsuleShape
                        ) {
                            Text(
                                voices.firstOrNull { it.voiceId == voiceId }?.displayName
                                    ?: "选择音色",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        MoReadStableDropdownMenu(
                            expanded = voiceMenu,
                            onDismissRequest = { voiceMenu = false },
                            width = 240.dp
                        ) {
                            if (voices.isEmpty()) {
                                MoReadMenuItem(
                                    text = "音色库还是空的",
                                    enabled = false,
                                    onClick = {}
                                )
                            }
                            voices.forEach { voice ->
                                MoReadMenuItem(
                                    text = voice.displayName,
                                    trailingText = voice.tags.takeIf(String::isNotBlank),
                                    selected = voice.voiceId == voiceId,
                                    onClick = {
                                        voiceId = voice.voiceId
                                        voiceMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    role.copy(
                        name = name.trim().ifBlank { role.name },
                        aliases = aliases.trim(),
                        gender = gender,
                        engine = engine,
                        voiceId = voiceId
                    )
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
internal fun PlayGeneratedPreview(path: String?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    LaunchedEffect(path) {
        if (!path.isNullOrBlank() && File(path).isFile) {
            player?.release()
            player = MediaPlayer.create(context, Uri.fromFile(File(path)))?.apply {
                setOnCompletionListener { completed ->
                    completed.release()
                    if (player === completed) player = null
                }
                start()
            }
            onConsumed()
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }
}
