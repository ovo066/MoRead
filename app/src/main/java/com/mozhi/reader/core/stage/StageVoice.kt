package com.mozhi.reader.core.stage

/** 有声书里已经合成好的一段配音：原文区间（章内 UTF-16）、音频文件与时长。 */
data class StageVoiceClip(val start: Int, val end: Int, val path: String, val millis: Int)

/**
 * 配音跟读：演到哪一拍，就放从这一拍里开始的那几段配音。
 *
 * 有声书按说话人切段，演出按镜头切拍，两边的边界不一定对齐：一段旁白可能横跨好几拍，
 * 一拍里也可能有好几段（提示语和对白分属旁白与角色）。规则是「配音从哪一拍开始，就在哪一拍放」，
 * 每段只放一次；跨到后面几拍的部分在那几拍里接着响，不重放、不截断。
 */
object StageVoice {
    fun clipsFor(beat: StageBeat, clips: List<StageVoiceClip>): List<StageVoiceClip> {
        val range = beat.fullRange ?: return emptyList()
        if (range.length == 0) return emptyList()
        return clips.filter { it.start >= range.start && it.start < range.end }.sortedBy { it.start }
    }

    /** 这几段一共要放多久；自动播放据此决定在这一拍停多久。 */
    fun totalMillis(clips: List<StageVoiceClip>): Long = clips.sumOf { it.millis.coerceAtLeast(0).toLong() }
}
