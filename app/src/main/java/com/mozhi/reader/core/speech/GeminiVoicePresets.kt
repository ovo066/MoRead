package com.mozhi.reader.core.speech

import com.mozhi.reader.core.database.entity.TtsVoiceEntity

/** Featured multilingual voices; the online catalog additionally offers regional Chinese voices. */
object GeminiVoicePresets {
    val voices: List<TtsVoiceEntity> = listOf(
        "Zephyr" to "明亮", "Puck" to "轻快", "Charon" to "解说",
        "Kore" to "坚定", "Fenrir" to "热情", "Leda" to "年轻",
        "Orus" to "坚定", "Aoede" to "轻盈", "Callirrhoe" to "随和",
        "Autonoe" to "明亮", "Enceladus" to "气声", "Iapetus" to "清晰",
        "Umbriel" to "随和", "Algieba" to "顺滑", "Despina" to "顺滑",
        "Erinome" to "清晰", "Algenib" to "沙哑", "Rasalgethi" to "解说",
        "Laomedeia" to "轻快", "Achernar" to "柔和", "Alnilam" to "坚定",
        "Schedar" to "平稳", "Gacrux" to "成熟", "Pulcherrima" to "鲜明",
        "Achird" to "亲切", "Zubenelgenubi" to "随性", "Vindemiatrix" to "温柔",
        "Sadachbia" to "活泼", "Sadaltager" to "博学", "Sulafat" to "温暖"
    ).mapIndexed { index, (id, style) ->
        TtsVoiceEntity(voiceId = id, displayName = "$id · $style", tags = "Gemini,多语言,$style",
            providerHint = "GEMINI", sortOrder = index)
    }
}
