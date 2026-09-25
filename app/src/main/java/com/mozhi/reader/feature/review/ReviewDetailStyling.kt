package com.mozhi.reader.feature.review

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mozhi.reader.feature.reader.AiRichText
import com.mozhi.reader.feature.reader.companionChatPalette

@Composable
internal fun ReviewNoteText(text: String) {
    val type = MaterialTheme.typography
    MaterialTheme(typography = type.copy(bodyMedium = type.bodyMedium.copy(fontSize = 16.sp, lineHeight = 28.sp,
        fontWeight = FontWeight.Medium))) {
        AiRichText(text, companionChatPalette(), parseSynchronously = true)
    }
}
