package com.mozhi.reader.core.stage

object StageAssetMerger {
    fun applyAudiobook(script: StageScript, segments: List<StageScriptSegment>): StageScript {
        val protected = setOf(StageSource.DIRECTOR, StageSource.USER)
        return script.copy(beats = script.beats.map { beat ->
            val range = beat.range
            if (beat.kind !in setOf(BeatKind.DIALOGUE, BeatKind.THOUGHT) || range == null || range.length == 0) return@map beat
            val segment = segments.firstOrNull {
                !it.isNarrator && !it.roleName.isNullOrBlank() &&
                    maxOf(0, minOf(range.end, it.end) - maxOf(range.start, it.start)).toLong() * 5 >= range.length.toLong() * 4
            } ?: return@map beat
            val sources = beat.sources.toMutableMap()
            var speaker = beat.speaker
            var emotion = beat.emotion
            var instruction = beat.instruction
            if (speaker?.source !in protected && sources["speaker"] !in protected) {
                val roleName = requireNotNull(segment.roleName)
                speaker = StageSpeaker(roleName, .95f, StageSource.AUDIOBOOK, isProtagonist = roleName == script.protagonist)
                sources["speaker"] = StageSource.AUDIOBOOK
            }
            val incomingEmotion = StageEmotion.fromLabel(segment.emotion)
            if (sources["emotion"] !in protected && incomingEmotion != null) { emotion = incomingEmotion; sources["emotion"] = StageSource.AUDIOBOOK }
            if (sources["instruction"] !in protected && !segment.instruction.isNullOrBlank()) { instruction = segment.instruction; sources["instruction"] = StageSource.AUDIOBOOK }
            beat.copy(speaker = speaker, emotion = emotion, instruction = instruction, sources = sources.toMap())
        })
    }
}
