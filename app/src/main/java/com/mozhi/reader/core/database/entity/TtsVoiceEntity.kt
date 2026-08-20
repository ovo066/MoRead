package com.mozhi.reader.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tts_voices",
    indices = [Index(value = ["providerHint", "voiceId"], unique = true)]
)
data class TtsVoiceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voiceId: String,
    val displayName: String,
    val tags: String = "",
    val gender: String = "UNSPECIFIED",
    val providerHint: String = "",
    val extraJson: String = "",
    val pinned: Boolean = false,
    val sortOrder: Int = 0
)
