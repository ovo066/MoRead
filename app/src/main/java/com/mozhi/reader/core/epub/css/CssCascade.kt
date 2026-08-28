package com.mozhi.reader.core.epub.css

object CssCascade {
    fun resolve(
        element: CssElementNode,
        rules: List<CssRule>,
        inlineDeclarations: List<CssDeclaration> = emptyList(),
        viewportWidthPx: Float = 0f,
        viewportHeightPx: Float = 0f,
        dpi: Float = 96f,
        pseudoElement: CssPseudoElement? = null
    ): Map<String, CssValue> = resolve(
        element = element,
        index = CssRuleIndex(rules),
        inlineDeclarations = inlineDeclarations,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
        dpi = dpi,
        pseudoElement = pseudoElement
    )

    fun resolve(
        element: CssElementNode,
        index: CssRuleIndex,
        inlineDeclarations: List<CssDeclaration> = emptyList(),
        viewportWidthPx: Float = 0f,
        viewportHeightPx: Float = 0f,
        dpi: Float = 96f,
        pseudoElement: CssPseudoElement? = null
    ): Map<String, CssValue> {
        val winners = LinkedHashMap<String, Winner>()
        index.candidates(element).forEach { rule ->
            if (rule.mediaCondition?.evaluate(viewportWidthPx, viewportHeightPx, dpi) == false) return@forEach
            if (!CssSelectorMatcher.matches(rule.selector, element, pseudoElement)) return@forEach
            rule.declarations.forEachIndexed { declarationIndex, declaration ->
                offer(
                    winners,
                    declaration,
                    Priority(
                        important = declaration.important,
                        inline = false,
                        specificity = rule.selector.specificity,
                        order = rule.order,
                        declarationIndex = declarationIndex
                    )
                )
            }
        }
        if (pseudoElement == null) {
            inlineDeclarations.forEachIndexed { declarationIndex, declaration ->
                offer(
                    winners,
                    declaration,
                    Priority(
                        important = declaration.important,
                        inline = true,
                        specificity = CssSpecificity(),
                        order = Int.MAX_VALUE,
                        declarationIndex = declarationIndex
                    )
                )
            }
        }
        return winners.mapValues { it.value.value }
    }

    private fun offer(
        winners: MutableMap<String, Winner>,
        declaration: CssDeclaration,
        priority: Priority
    ) {
        val previous = winners[declaration.property]
        if (previous == null || priority > previous.priority) {
            winners[declaration.property] = Winner(declaration.value, priority)
        }
    }

    private data class Winner(val value: CssValue, val priority: Priority)

    private data class Priority(
        val important: Boolean,
        val inline: Boolean,
        val specificity: CssSpecificity,
        val order: Int,
        val declarationIndex: Int
    ) : Comparable<Priority> {
        override fun compareTo(other: Priority): Int {
            compareValues(important, other.important).takeIf { it != 0 }?.let { return it }
            compareValues(inline, other.inline).takeIf { it != 0 }?.let { return it }
            specificity.compareTo(other.specificity).takeIf { it != 0 }?.let { return it }
            compareValues(order, other.order).takeIf { it != 0 }?.let { return it }
            return compareValues(declarationIndex, other.declarationIndex)
        }
    }
}
