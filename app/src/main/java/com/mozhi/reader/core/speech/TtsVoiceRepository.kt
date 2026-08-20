package com.mozhi.reader.core.speech

import com.mozhi.reader.core.database.dao.TtsVoiceDao
import com.mozhi.reader.core.database.entity.TtsVoiceEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TtsVoiceRepository @Inject constructor(
    private val dao: TtsVoiceDao
) {
    val voices = dao.observeVoices()

    suspend fun getVoices(): List<TtsVoiceEntity> = dao.getVoices()

    suspend fun save(voice: TtsVoiceEntity): Long {
        val normalized = voice.copy(
            voiceId = voice.voiceId.trim(),
            displayName = voice.displayName.trim(),
            tags = voice.tags.split(',', '，').map(String::trim)
                .filter(String::isNotEmpty).distinct().joinToString(",")
        )
        require(normalized.voiceId.isNotBlank()) { "音色 ID 不能为空" }
        require(normalized.displayName.isNotBlank()) { "显示名称不能为空" }
        if (normalized.id == 0L) return dao.insert(normalized)
        dao.update(normalized)
        return normalized.id
    }

    suspend fun delete(voice: TtsVoiceEntity) = dao.delete(voice)

    suspend fun importMiniMaxPresets() {
        dao.insertAll(
            listOf(
                TtsVoiceEntity(
                    voiceId = "male-qn-qingse",
                    displayName = "青涩青年男声",
                    tags = "男声,青年,对白",
                    gender = "MALE",
                    providerHint = "MINIMAX"
                ),
                TtsVoiceEntity(
                    voiceId = "male-qn-jingying",
                    displayName = "精英青年男声",
                    tags = "男声,沉稳,旁白",
                    gender = "MALE",
                    providerHint = "MINIMAX"
                ),
                TtsVoiceEntity(
                    voiceId = "female-shaonv",
                    displayName = "少女女声",
                    tags = "女声,年轻,温柔",
                    gender = "FEMALE",
                    providerHint = "MINIMAX"
                )
            )
        )
    }
}
