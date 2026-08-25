package com.mozhi.reader.core.datastore

/**
 * agent 主动调用的总闸（2026-08-24 用户定调的硬规则）。
 *
 * BYOK 花的是用户自己的额度，所以**凡是 agent 自己决定发起的付费调用都必须能单独关掉**，
 * 而且默认保守。这里只收「应用替用户掏钱」的那几类：
 *
 * - 自主发语音：模型在回复里打 `[语音]` 标记，应用替它调一次 TTS。
 * - 自主生图：模型自己调 `generate_image`。
 * - 随读段评及其语音/配图：读完一章后应用自动发起的批量调用。
 *
 * 关闭的语义是**彻底不给这个能力**，不是「给了但拦住」：提示词里不写语音标记说明，
 * 工具表里不注册 generate_image。留一个注册了却永远失败的工具只会误导模型
 * （与 MemoryScope.longTermEnabled 关掉即不注册 recall_memory 同一纪律）。
 */
data class CompanionAutonomySettings(
    /** 允许 AI 自己决定哪一句发语音。关＝提示词不提语音，永不自动合成。 */
    val voiceRepliesEnabled: Boolean = false,
    /** 允许 AI 自己决定生成插图。关＝generate_image 不注册。 */
    val imageRepliesEnabled: Boolean = false,
    /** 随读段评：读完一章后自动产出 ≤2 条批注。 */
    val proactiveAnnotationsEnabled: Boolean = false,
    /** 段评附语音；受 [proactiveAnnotationsEnabled] 约束。 */
    val proactiveAnnotationVoiceEnabled: Boolean = false,
    /** 段评附插图；受 [proactiveAnnotationsEnabled] 约束。 */
    val proactiveAnnotationImageEnabled: Boolean = false
) {
    /** 总开关关掉时，两个媒体子项一律视为关——避免「子项开着但看不出没生效」。 */
    val annotationVoiceActive: Boolean
        get() = proactiveAnnotationsEnabled && proactiveAnnotationVoiceEnabled

    val annotationImageActive: Boolean
        get() = proactiveAnnotationsEnabled && proactiveAnnotationImageEnabled

    /** 有没有任何一项主动调用是开着的；设置页据此写摘要。 */
    val anyEnabled: Boolean
        get() = voiceRepliesEnabled || imageRepliesEnabled || proactiveAnnotationsEnabled
}
