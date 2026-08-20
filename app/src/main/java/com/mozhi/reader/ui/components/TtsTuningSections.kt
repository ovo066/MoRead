package com.mozhi.reader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mozhi.reader.core.speech.TtsEngineMode
import com.mozhi.reader.core.speech.TtsSettings
import com.mozhi.reader.core.speech.TtsSynthesisGranularity
import kotlin.math.roundToInt

data class TtsTuningActions(
    val onEngineModeChange: (TtsEngineMode) -> Unit,
    val onAiVoiceChange: (String) -> Unit,
    val onSystemRateChange: (Float) -> Unit,
    val onSystemPitchChange: (Float) -> Unit,
    val onAiSpeedChange: (Float) -> Unit,
    val onAiVolumeChange: (Float) -> Unit,
    val onAiPitchChange: (Int) -> Unit,
    val onAllowAudioMixingChange: (Boolean) -> Unit,
    val onTrimSilenceChange: (Boolean) -> Unit,
    val onGranularityChange: (TtsSynthesisGranularity) -> Unit,
    val onMaxCharsChange: (Int) -> Unit,
    val onConcurrencyChange: (Int) -> Unit,
    val onRetryCountChange: (Int) -> Unit,
    val onPrefetchCountChange: (Int) -> Unit,
    val onOpenVoiceLibrary: (() -> Unit)? = null,
    val onClearBookCache: (() -> Unit)? = null
)

@Composable
fun TtsTuningSections(
    settings: TtsSettings,
    actions: TtsTuningActions,
    cacheSummary: String? = null,
    includeEngineSection: Boolean = true,
    includeRateControls: Boolean = true
) {
    if (includeEngineSection) {
        TuningSection("引擎与音色") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.engineMode == TtsEngineMode.SYSTEM,
                    onClick = { actions.onEngineModeChange(TtsEngineMode.SYSTEM) },
                    label = { Text("系统 TTS") }
                )
                FilterChip(
                    selected = settings.engineMode == TtsEngineMode.AI,
                    onClick = { actions.onEngineModeChange(TtsEngineMode.AI) },
                    label = { Text("AI TTS") }
                )
            }
            if (settings.engineMode == TtsEngineMode.AI) {
                OutlinedTextField(
                    value = settings.aiVoiceId,
                    onValueChange = actions.onAiVoiceChange,
                    label = { Text("音色 ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            actions.onOpenVoiceLibrary?.let { open ->
                TextButton(onClick = open) { Text("打开音色库") }
            }
        }
    }

    TuningSection("朗读") {
        if (includeRateControls) {
            if (settings.engineMode == TtsEngineMode.SYSTEM) {
                TuningSlider("语速", settings.systemRate, 0.5f..2f, actions.onSystemRateChange)
                TuningSlider("音调", settings.systemPitch, 0.5f..2f, actions.onSystemPitchChange)
            } else {
                TuningSlider("语速", settings.aiSpeed, 0.5f..2f, actions.onAiSpeedChange)
                TuningSlider("音量", settings.aiVolume, 0.5f..2f, actions.onAiVolumeChange)
                TuningSlider(
                    "音调",
                    settings.aiPitch.toFloat(),
                    -12f..12f,
                    { actions.onAiPitchChange(it.roundToInt()) }
                )
            }
        }
        TuningSwitch(
            title = "与其他音频同时播放",
            supporting = "开启后不请求独占音频焦点",
            checked = settings.allowAudioMixing,
            onCheckedChange = actions.onAllowAudioMixingChange
        )
        TuningSwitch(
            title = "减少句间静音",
            supporting = "为支持该能力的语音引擎保留设置",
            checked = settings.trimSilence,
            onCheckedChange = actions.onTrimSilenceChange
        )
    }

    TuningSection("合成") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsSynthesisGranularity.entries.forEach { granularity ->
                FilterChip(
                    selected = settings.synthesisGranularity == granularity,
                    onClick = { actions.onGranularityChange(granularity) },
                    label = { Text(granularity.label()) }
                )
            }
        }
        TuningSlider(
            label = "单次最大字数 ${settings.maxSynthesisChars}",
            value = settings.maxSynthesisChars.toFloat(),
            range = 80f..2_000f,
            onChange = { actions.onMaxCharsChange((it / 20).roundToInt() * 20) }
        )
        TuningSlider(
            label = "预合成并发 ${settings.synthesisConcurrency}",
            value = settings.synthesisConcurrency.toFloat(),
            range = 1f..4f,
            onChange = { actions.onConcurrencyChange(it.roundToInt()) }
        )
        TuningSlider(
            label = "失败重试 ${settings.retryCount} 次",
            value = settings.retryCount.toFloat(),
            range = 0f..5f,
            onChange = { actions.onRetryCountChange(it.roundToInt()) }
        )
    }

    TuningSection("缓存") {
        TuningSlider(
            label = "预合成 ${settings.prefetchCount} 段",
            value = settings.prefetchCount.toFloat(),
            range = 0f..10f,
            onChange = { actions.onPrefetchCountChange(it.roundToInt()) }
        )
        cacheSummary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions.onClearBookCache?.let { clear ->
            TextButton(onClick = clear) { Text("清理本书语音缓存") }
        }
    }
}

@Composable
private fun TuningSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun TuningSwitch(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TuningSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit
) {
    Column {
        Text("$label · ${"%.2f".format(value)}", style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

private fun TtsSynthesisGranularity.label(): String = when (this) {
    TtsSynthesisGranularity.SENTENCE -> "逐句"
    TtsSynthesisGranularity.PARAGRAPH -> "逐段"
    TtsSynthesisGranularity.CHAPTER -> "整章"
}
