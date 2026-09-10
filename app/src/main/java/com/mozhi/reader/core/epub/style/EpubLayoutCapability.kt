package com.mozhi.reader.core.epub.style

/** CSS `writing-mode` 的三个取值；`-webkit-` / `-epub-` 前缀在解析阶段归一到同一属性。 */
enum class EpubWritingMode(val css: String) {
    HORIZONTAL_TB("horizontal-tb"),
    VERTICAL_RL("vertical-rl"),
    VERTICAL_LR("vertical-lr");

    val vertical: Boolean get() = this != HORIZONTAL_TB

    companion object {
        fun parse(value: String?): EpubWritingMode? = when (value?.trim()?.lowercase()) {
            null, "", "initial", "unset", "horizontal-tb" -> HORIZONTAL_TB
            "vertical-rl", "tb-rl" -> VERTICAL_RL
            "vertical-lr", "tb-lr", "tb" -> VERTICAL_LR
            else -> null
        }
    }
}

/** 原生精排为什么没有按出版样式的书写模式排版；给诊断与后续降级策略用的结构化原因。 */
enum class EpubLayoutFallbackReason(val description: String) {
    VERTICAL_WRITING_MODE("vertical writing mode falls back to horizontal flow"),
    NESTED_WRITING_MODE("nested writing-mode transition"),
    SCOPE_TOO_DEEP("style scope deeper than the native layout supports")
}

/**
 * 一章能否由原生精排引擎「如实」排出来。`supported` 为 false 时排版仍会继续——按横排、按当前
 * 已支持的属性——但调用方能拿到明确原因，而不是靠异常或肉眼发现「竖排书变成了横排」。
 */
data class EpubLayoutCapability(
    val supported: Boolean,
    val reason: EpubLayoutFallbackReason? = null,
    val detail: String = if (supported) "supported" else reason?.description.orEmpty(),
    val chapterWritingMode: EpubWritingMode = EpubWritingMode.HORIZONTAL_TB
) {
    companion object {
        val Supported = EpubLayoutCapability(supported = true)

        fun fallback(
            reason: EpubLayoutFallbackReason,
            chapterWritingMode: EpubWritingMode,
            detail: String = reason.description
        ) = EpubLayoutCapability(
            supported = false,
            reason = reason,
            detail = detail,
            chapterWritingMode = chapterWritingMode
        )
    }
}

/**
 * 布局前的能力门：在级联之后、盒树之前扫一遍样式树，把「排不出来」的情况在动手前判定出来。
 *
 * 判定顺序（与可验证的成熟阅读器行为一致）：
 * 1. 章节主书写模式为竖排 → 引擎目前只有横排轴，整章降级为横排流。
 * 2. 横排章节里出现局部竖排（或竖排章节里嵌横排）→ 记为嵌套切换。
 * 3. 样式作用域过深 → 记为超深作用域（极端嵌套的转换器产物）。
 * 其余情况视为支持。判定不阻断排版，只产出结构化结果。
 */
object EpubLayoutCapabilityAnalyzer {
    /** 超过这个深度的嵌套几乎只见于转换工具产物；深度本身不影响横排，但记录下来便于诊断。 */
    const val SAFE_SCOPE_DEPTH = 96

    fun analyze(root: StyledDomNode): EpubLayoutCapability {
        val chapterMode = root.style.writingMode
        var nestedTransition = false
        var maxDepth = 0
        fun walk(node: StyledDomNode, depth: Int) {
            if (depth > maxDepth) maxDepth = depth
            if (node.style.writingMode != chapterMode) nestedTransition = true
            node.children.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return when {
            chapterMode.vertical -> EpubLayoutCapability.fallback(
                reason = EpubLayoutFallbackReason.VERTICAL_WRITING_MODE,
                chapterWritingMode = chapterMode,
                detail = "chapter writing-mode ${chapterMode.css} is laid out as horizontal-tb"
            )
            nestedTransition -> EpubLayoutCapability.fallback(
                reason = EpubLayoutFallbackReason.NESTED_WRITING_MODE,
                chapterWritingMode = chapterMode
            )
            maxDepth > SAFE_SCOPE_DEPTH -> EpubLayoutCapability.fallback(
                reason = EpubLayoutFallbackReason.SCOPE_TOO_DEEP,
                chapterWritingMode = chapterMode,
                detail = "style scope depth $maxDepth exceeds $SAFE_SCOPE_DEPTH"
            )
            else -> EpubLayoutCapability.Supported
        }
    }
}
