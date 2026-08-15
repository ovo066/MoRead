package com.mozhi.reader.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mozhi.reader.core.speech.TtsApiProvider
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.ui.components.MoReadBackdrop
import java.util.Locale
import androidx.compose.ui.text.input.PasswordVisualTransformation

/**
 * 独立「语音朗读」设置页：SegmentedButton 切换系统 TTS / AI TTS，
 * 各引擎的参数表单化（不再手写 extraJson），可即时试听。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsSettingsScreen(
    onBack: () -> Unit,
    onOpenSpeechCache: () -> Unit = {},
    viewModel: TtsSettingsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings

    MoReadBackdrop {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "语音朗读",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onOpenSpeechCache) { Text("语音缓存") }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = settings.engineMode == TtsEngineMode.SYSTEM,
                        onClick = { viewModel.setEngineMode(TtsEngineMode.SYSTEM) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) { Text("系统 TTS") }
                    SegmentedButton(
                        selected = settings.engineMode == TtsEngineMode.AI,
                        onClick = { viewModel.setEngineMode(TtsEngineMode.AI) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) { Text("AI TTS") }
                }

                if (settings.engineMode == TtsEngineMode.SYSTEM) {
                    Text(
                        "使用手机上已安装的语音引擎朗读（如 Multi TTS、系统自带引擎），不消耗 API 额度。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var engineMenuExpanded by remember { mutableStateOf(false) }
                    val engineLabel = state.systemEngines
                        .firstOrNull { it.packageName == settings.systemEnginePackage }
                        ?.label
                        ?: if (settings.systemEnginePackage.isBlank()) "系统默认引擎" else settings.systemEnginePackage
                    ExposedDropdownMenuBox(
                        expanded = engineMenuExpanded,
                        onExpandedChange = { engineMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = engineLabel,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("语音引擎") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineMenuExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = engineMenuExpanded,
                            onDismissRequest = { engineMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("系统默认引擎") },
                                onClick = {
                                    engineMenuExpanded = false
                                    viewModel.setSystemEngine("")
                                }
                            )
                            state.systemEngines.forEach { engine ->
                                DropdownMenuItem(
                                    text = { Text(engine.label) },
                                    onClick = {
                                        engineMenuExpanded = false
                                        viewModel.setSystemEngine(engine.packageName)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = settings.systemLanguageTag,
                        onValueChange = viewModel::setSystemLanguage,
                        label = { Text("语言标签（可选）") },
                        supportingText = { Text("BCP-47，如 zh-CN；留空用引擎默认语言") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LabeledSlider(
                        label = "语速",
                        value = settings.systemRate,
                        range = 0.5f..2.5f,
                        onChange = viewModel::setSystemRate
                    )
                    LabeledSlider(
                        label = "音调",
                        value = settings.systemPitch,
                        range = 0.5f..2f,
                        onChange = viewModel::setSystemPitch
                    )
                } else {
                    Text(
                        "在此独立配置云端语音 API（优先生效）；留空 Base URL 或模型时，" +
                            "回落到「设置 › 模型分配」的语音模型。Key 加密存储在本机。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    var apiProviderMenuExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = apiProviderMenuExpanded,
                        onExpandedChange = { apiProviderMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = settings.aiProvider.label(),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("服务商") },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(
                                    expanded = apiProviderMenuExpanded
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = apiProviderMenuExpanded,
                            onDismissRequest = { apiProviderMenuExpanded = false }
                        ) {
                            TtsApiProvider.entries.forEach { provider ->
                                DropdownMenuItem(
                                    text = { Text(provider.label()) },
                                    onClick = {
                                        apiProviderMenuExpanded = false
                                        viewModel.setAiProvider(provider)
                                    }
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = settings.aiBaseUrl,
                        onValueChange = viewModel::setAiBaseUrl,
                        label = { Text("Base URL") },
                        supportingText = {
                            Text(
                                when (settings.aiProvider) {
                                    TtsApiProvider.MINIMAX_CN -> "MiniMax 国内：https://api.minimaxi.com/v1"
                                    TtsApiProvider.MINIMAX_INTL -> "MiniMax 海外：https://api.minimax.io/v1"
                                    TtsApiProvider.OPENAI_COMPAT -> "OpenAI 官方或任意兼容中转"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    var apiKeyInput by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = { apiKeyInput = it },
                        label = { Text("API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        supportingText = {
                            Text(if (state.hasApiKey) "已保存，重新输入可覆盖" else "尚未保存")
                        },
                        singleLine = true,
                        trailingIcon = {
                            TextButton(
                                onClick = {
                                    viewModel.saveApiKey(apiKeyInput)
                                    apiKeyInput = ""
                                },
                                enabled = apiKeyInput.isNotBlank()
                            ) { Text("保存") }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (settings.aiProvider != TtsApiProvider.OPENAI_COMPAT) {
                        OutlinedTextField(
                            value = settings.aiGroupId,
                            onValueChange = viewModel::setAiGroupId,
                            label = { Text("GroupId（可选）") },
                            supportingText = { Text("MiniMax 控制台的 GroupId；部分账号必填") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    OutlinedTextField(
                        value = settings.aiModel,
                        onValueChange = viewModel::setAiModel,
                        label = { Text("模型") },
                        supportingText = {
                            Text(
                                if (settings.aiProvider == TtsApiProvider.OPENAI_COMPAT) {
                                    "如 gpt-4o-mini-tts / tts-1"
                                } else {
                                    "如 speech-02-turbo / speech-02-hd"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.aiVoiceId,
                        onValueChange = viewModel::setAiVoice,
                        label = { Text("音色 ID（可选）") },
                        supportingText = { Text("OpenAI 如 alloy / nova；MiniMax 可填系统或克隆音色 ID") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LabeledSlider(
                        label = "语速",
                        value = settings.aiSpeed,
                        range = 0.5f..2f,
                        onChange = viewModel::setAiSpeed
                    )
                    LabeledSlider(
                        label = "音量（MiniMax）",
                        value = settings.aiVolume,
                        range = 0.5f..2f,
                        onChange = viewModel::setAiVolume
                    )
                    Column {
                        Text(
                            "音调（MiniMax）：${settings.aiPitch}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Slider(
                            value = settings.aiPitch.toFloat(),
                            onValueChange = { viewModel.setAiPitch(it.toInt()) },
                            valueRange = -12f..12f,
                            steps = 23
                        )
                    }
                    Text(
                        "OpenAI 会忽略音量与音调；相同参数会复用本地语音缓存。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = viewModel::preview, enabled = !state.isPreviewing) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Text(if (state.isPreviewing) "正在试听…" else "试听")
                    }
                    state.message?.let { message ->
                        Text(
                            message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Text(
            "$label：${String.format(Locale.ROOT, "%.2f", value)}×",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

private fun TtsApiProvider.label(): String = when (this) {
    TtsApiProvider.MINIMAX_CN -> "MiniMax（国内）"
    TtsApiProvider.MINIMAX_INTL -> "MiniMax（海外）"
    TtsApiProvider.OPENAI_COMPAT -> "OpenAI 兼容"
}
