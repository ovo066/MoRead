package com.mozhi.reader.ui.components

import androidx.compose.runtime.Composable
import com.mozhi.reader.core.speech.GeminiVoicePresets

@Composable
fun GeminiVoicePicker(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    VoiceChoiceDialog(
        "选择 Gemini 音色",
        GeminiVoicePresets.voices.map { VoiceChoice(it.voiceId, it.displayName, "多语言 · 支持中文") },
        selected.ifBlank { "Sulafat" }, onSelect, onDismiss
    )
}
