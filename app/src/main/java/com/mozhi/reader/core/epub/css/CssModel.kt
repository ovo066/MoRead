package com.mozhi.reader.core.epub.css

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CssStylesheet(
    val sourceHref: String,
    val rules: List<CssRule>,
    val fontFaces: List<CssFontFaceRule> = emptyList()
)

@Serializable
data class CssParseResult(
    val stylesheet: CssStylesheet,
    val unsupportedSelectors: List<String> = emptyList(),
    val unsupportedProperties: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList()
)

@Serializable
data class CssRule(
    val selector: CssSelector,
    val declarations: List<CssDeclaration>,
    val order: Int,
    val mediaCondition: CssMediaCondition? = null
)

@Serializable
data class CssFontFaceRule(
    val family: String,
    val sources: List<String>,
    val weight: Int? = null,
    val style: String? = null
)

@Serializable
data class CssDeclaration(
    val property: String,
    val value: CssValue,
    val important: Boolean = false
)

@Serializable
sealed interface CssValue {
    @Serializable
    @SerialName("length")
    data class Length(val value: Float, val unit: CssUnit) : CssValue

    @Serializable
    @SerialName("number")
    data class Number(val value: Float) : CssValue

    @Serializable
    @SerialName("color")
    data class Color(val argb: Int) : CssValue

    @Serializable
    @SerialName("keyword")
    data class Keyword(val name: String) : CssValue

    @Serializable
    @SerialName("ident")
    data class Ident(val name: String) : CssValue

    @Serializable
    @SerialName("url")
    data class Url(val href: String) : CssValue

    @Serializable
    @SerialName("tuple")
    data class Tuple(val items: List<CssValue>) : CssValue

    @Serializable
    @SerialName("comma-list")
    data class CommaList(val items: List<CssValue>) : CssValue
}

@Serializable
enum class CssUnit { PX, EM, REM, PERCENT, VW, VH, PT, EX, CH }

@Serializable
data class CssSpecificity(
    val ids: Int = 0,
    val classes: Int = 0,
    val tags: Int = 0
) : Comparable<CssSpecificity> {
    override fun compareTo(other: CssSpecificity): Int =
        compareValuesBy(this, other, CssSpecificity::ids, CssSpecificity::classes, CssSpecificity::tags)

    operator fun plus(other: CssSpecificity) = CssSpecificity(
        ids + other.ids,
        classes + other.classes,
        tags + other.tags
    )
}

@Serializable
data class CssSelector(
    /** Compounds are stored left-to-right; combinators[i] joins compounds[i] to compounds[i + 1]. */
    val compounds: List<CssCompoundSelector>,
    val combinators: List<CssCombinator> = emptyList(),
    val pseudoElement: CssPseudoElement? = null,
    val specificity: CssSpecificity = CssSpecificity(),
    val neverMatches: Boolean = false,
    val source: String = ""
)

@Serializable
data class CssCompoundSelector(
    val tag: String? = null,
    val universal: Boolean = false,
    val id: String? = null,
    val classes: List<String> = emptyList(),
    val attributes: List<CssAttributeSelector> = emptyList(),
    val pseudoClasses: List<CssPseudoClass> = emptyList()
)

@Serializable
data class CssAttributeSelector(
    val name: String,
    val operator: CssAttributeOperator = CssAttributeOperator.EXISTS,
    val value: String? = null,
    val caseInsensitive: Boolean = false
)

@Serializable
enum class CssAttributeOperator { EXISTS, EQUALS, INCLUDES, DASH_MATCH }

@Serializable
enum class CssCombinator { DESCENDANT, CHILD, ADJACENT_SIBLING, GENERAL_SIBLING }

@Serializable
enum class CssPseudoElement { FIRST_LETTER, FIRST_LINE }

@Serializable
sealed interface CssPseudoClass {
    @Serializable @SerialName("first-child") data object FirstChild : CssPseudoClass
    @Serializable @SerialName("last-child") data object LastChild : CssPseudoClass
    @Serializable @SerialName("only-child") data object OnlyChild : CssPseudoClass
    @Serializable @SerialName("first-of-type") data object FirstOfType : CssPseudoClass
    @Serializable @SerialName("last-of-type") data object LastOfType : CssPseudoClass
    @Serializable @SerialName("nth-child") data class NthChild(val a: Int, val b: Int) : CssPseudoClass
    @Serializable @SerialName("not") data class Not(val selector: CssCompoundSelector) : CssPseudoClass
    @Serializable @SerialName("unsupported") data class Unsupported(val name: String) : CssPseudoClass
}

/** Minimal DOM view used by the independent selector engine. */
interface CssElementNode {
    val tag: String
    val id: String?
    val classes: Set<String>
    val attributes: Map<String, String>
    val parent: CssElementNode?
    val elementChildren: List<CssElementNode>
    /** Zero-based position among element siblings. */
    val childIndex: Int
    /** Zero-based position among element siblings with the same tag. */
    val childIndexOfType: Int
}

@Serializable
data class CssMediaCondition(val queries: List<CssMediaQuery>) {
    fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float = 96f): Boolean =
        queries.any { it.evaluate(viewportWidthPx, viewportHeightPx, dpi) }
}

@Serializable
data class CssMediaQuery(
    val type: CssMediaType = CssMediaType.ALL,
    val features: List<CssMediaFeature> = emptyList()
) {
    fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float): Boolean {
        if (type != CssMediaType.ALL && type != CssMediaType.SCREEN) return false
        return features.all { it.evaluate(viewportWidthPx, viewportHeightPx, dpi) }
    }
}

@Serializable
enum class CssMediaType { ALL, SCREEN, PRINT, SPEECH, AURAL, UNKNOWN }

@Serializable
sealed interface CssMediaFeature {
    fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float): Boolean

    @Serializable
    @SerialName("min-width")
    data class MinWidth(val value: Float, val unit: CssUnit = CssUnit.PX) : CssMediaFeature {
        override fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float) =
            viewportWidthPx >= mediaPixels(value, unit, dpi)
    }

    @Serializable
    @SerialName("max-width")
    data class MaxWidth(val value: Float, val unit: CssUnit = CssUnit.PX) : CssMediaFeature {
        override fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float) =
            viewportWidthPx <= mediaPixels(value, unit, dpi)
    }

    @Serializable
    @SerialName("orientation")
    data class Orientation(val landscape: Boolean) : CssMediaFeature {
        override fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float) =
            (viewportWidthPx >= viewportHeightPx) == landscape
    }

    /** Unknown features are deliberately permissive for EPUB compatibility. */
    @Serializable
    @SerialName("unknown")
    data class Unknown(val source: String) : CssMediaFeature {
        override fun evaluate(viewportWidthPx: Float, viewportHeightPx: Float, dpi: Float) = true
    }
}

private fun mediaPixels(value: Float, unit: CssUnit, dpi: Float): Float = when (unit) {
    CssUnit.PX -> value
    CssUnit.PT -> value * dpi / 72f
    CssUnit.EM, CssUnit.REM -> value * 16f
    else -> value
}
