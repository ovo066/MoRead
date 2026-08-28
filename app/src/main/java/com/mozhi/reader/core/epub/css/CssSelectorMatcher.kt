package com.mozhi.reader.core.epub.css

object CssSelectorMatcher {
    fun matches(
        selector: CssSelector,
        element: CssElementNode,
        pseudoElement: CssPseudoElement? = null
    ): Boolean {
        if (selector.neverMatches || selector.compounds.isEmpty() || selector.pseudoElement != pseudoElement) {
            return false
        }
        return matchesAt(selector, selector.compounds.lastIndex, element)
    }

    private fun matchesAt(selector: CssSelector, index: Int, element: CssElementNode): Boolean {
        if (!matchesCompound(selector.compounds[index], element)) return false
        if (index == 0) return true
        return when (selector.combinators[index - 1]) {
            CssCombinator.CHILD -> element.parent?.let { matchesAt(selector, index - 1, it) } == true
            CssCombinator.DESCENDANT -> {
                var ancestor = element.parent
                while (ancestor != null) {
                    if (matchesAt(selector, index - 1, ancestor)) return true
                    ancestor = ancestor.parent
                }
                false
            }
            CssCombinator.ADJACENT_SIBLING -> previousSiblings(element).lastOrNull()
                ?.let { matchesAt(selector, index - 1, it) } == true
            CssCombinator.GENERAL_SIBLING -> previousSiblings(element)
                .any { matchesAt(selector, index - 1, it) }
        }
    }

    private fun matchesCompound(selector: CssCompoundSelector, element: CssElementNode): Boolean {
        if (selector.tag != null && !selector.tag.equals(element.tag, ignoreCase = true)) return false
        if (selector.id != null && selector.id != element.id) return false
        if (!element.classes.containsAll(selector.classes)) return false
        if (selector.attributes.any { !matchesAttribute(it, element.attributes) }) return false
        if (selector.pseudoClasses.any { !matchesPseudo(it, element) }) return false
        return true
    }

    private fun matchesAttribute(selector: CssAttributeSelector, attributes: Map<String, String>): Boolean {
        val actual = attributes.entries.firstOrNull { it.key.equals(selector.name, ignoreCase = true) }?.value
            ?: return false
        val expected = selector.value ?: return selector.operator == CssAttributeOperator.EXISTS
        fun String.normalized() = if (selector.caseInsensitive) lowercase() else this
        return when (selector.operator) {
            CssAttributeOperator.EXISTS -> true
            CssAttributeOperator.EQUALS -> actual.normalized() == expected.normalized()
            CssAttributeOperator.INCLUDES -> actual.split(Regex("\\s+")).any {
                it.normalized() == expected.normalized()
            }
            CssAttributeOperator.DASH_MATCH -> {
                val left = actual.normalized()
                val right = expected.normalized()
                left == right || left.startsWith("$right-")
            }
        }
    }

    private fun matchesPseudo(pseudo: CssPseudoClass, element: CssElementNode): Boolean = when (pseudo) {
        CssPseudoClass.FirstChild -> element.childIndex == 0
        CssPseudoClass.LastChild -> element.parent?.elementChildren?.lastOrNull() === element
        CssPseudoClass.OnlyChild -> element.parent?.elementChildren?.size == 1
        CssPseudoClass.FirstOfType -> element.childIndexOfType == 0
        CssPseudoClass.LastOfType -> element.parent?.elementChildren
            ?.lastOrNull { it.tag.equals(element.tag, true) } === element
        is CssPseudoClass.NthChild -> nthMatches(element.childIndex + 1, pseudo.a, pseudo.b)
        is CssPseudoClass.Not -> !matchesCompound(pseudo.selector, element)
        is CssPseudoClass.Unsupported -> false
    }

    private fun nthMatches(index: Int, a: Int, b: Int): Boolean {
        if (a == 0) return index == b
        val difference = index - b
        return difference % a == 0 && difference / a >= 0
    }

    private fun previousSiblings(element: CssElementNode): List<CssElementNode> {
        val siblings = element.parent?.elementChildren ?: return emptyList()
        val end = element.childIndex.coerceIn(0, siblings.size)
        return siblings.subList(0, end)
    }
}

/** Rightmost-selector candidate index; full matching remains authoritative. */
class CssRuleIndex(rules: List<CssRule>) {
    private val byId = HashMap<String, MutableList<CssRule>>()
    private val byClass = HashMap<String, MutableList<CssRule>>()
    private val byTag = HashMap<String, MutableList<CssRule>>()
    private val universal = ArrayList<CssRule>()

    init {
        rules.forEach { rule ->
            val right = rule.selector.compounds.lastOrNull()
            when {
                right == null -> Unit
                right.id != null -> byId.getOrPut(right.id) { ArrayList() }.add(rule)
                right.classes.isNotEmpty() -> right.classes.forEach { name ->
                    byClass.getOrPut(name) { ArrayList() }.add(rule)
                }
                right.tag != null -> byTag.getOrPut(right.tag.lowercase()) { ArrayList() }.add(rule)
                else -> universal += rule
            }
        }
    }

    fun candidates(element: CssElementNode): List<CssRule> = buildList {
        element.id?.let { addAll(byId[it].orEmpty()) }
        element.classes.forEach { addAll(byClass[it].orEmpty()) }
        addAll(byTag[element.tag.lowercase()].orEmpty())
        addAll(universal)
    }.distinct()
}
