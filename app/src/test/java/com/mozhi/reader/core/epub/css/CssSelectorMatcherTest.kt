package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CssSelectorMatcherTest {
    @Test
    fun `simple selectors attributes combinators nth and not match a dom view`() {
        val root = TestNode("section", classes = setOf("root"))
        val first = root.add(TestNode("p", id = "first", classes = setOf("a"), attributes = mapOf("lang" to "zh-CN", "epub:type" to "chapter")))
        val span = first.add(TestNode("span", classes = setOf("mark")))
        val second = root.add(TestNode("p", classes = setOf("b", "c")))
        val third = root.add(TestNode("p", classes = setOf("c")))

        assertMatches("section.root > p#first.a[lang|=zh] span.mark", span)
        assertMatches("p.a + p.b", second)
        assertMatches("p.a ~ p.c", third)
        assertMatches("[epub\\:type]", first)
        assertMatches("p:nth-child(2n+1):not(.b)", third)
        assertFalse(matches("p:first-child", second))
        assertTrue(matches("p:last-of-type", third))
    }

    @Test
    fun `first letter is matched only for its pseudo target and unknown pseudo never matches`() {
        val node = TestNode("p", classes = setOf("a"))
        val firstLetter = parse("p.a::first-letter")
        val unsupported = CssParser("test.css").parse("p:future-state, .a { color:red }")

        assertFalse(CssSelectorMatcher.matches(firstLetter, node))
        assertTrue(CssSelectorMatcher.matches(firstLetter, node, CssPseudoElement.FIRST_LETTER))
        assertFalse(CssSelectorMatcher.matches(unsupported.stylesheet.rules.first().selector, node))
        assertTrue(CssSelectorMatcher.matches(unsupported.stylesheet.rules.last().selector, node))
    }

    private fun assertMatches(selector: String, node: TestNode) = assertTrue(selector, matches(selector, node))
    private fun matches(selector: String, node: TestNode) = CssSelectorMatcher.matches(parse(selector), node)
    private fun parse(selector: String) = CssParser("test.css").parse("$selector { color:red }").stylesheet.rules.single().selector
}

private class TestNode(
    override val tag: String,
    override val id: String? = null,
    override val classes: Set<String> = emptySet(),
    override val attributes: Map<String, String> = emptyMap()
) : CssElementNode {
    private val mutableChildren = ArrayList<TestNode>()
    override var parent: CssElementNode? = null
        private set
    override val elementChildren: List<CssElementNode> get() = mutableChildren
    override val childIndex: Int get() = parent?.elementChildren?.indexOf(this) ?: 0
    override val childIndexOfType: Int get() = parent?.elementChildren?.take(childIndex)?.count { it.tag.equals(tag, true) } ?: 0

    fun add(child: TestNode): TestNode {
        child.parent = this
        mutableChildren += child
        return child
    }
}
