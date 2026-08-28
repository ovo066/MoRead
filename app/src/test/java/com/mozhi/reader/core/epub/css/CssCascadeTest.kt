package com.mozhi.reader.core.epub.css

import org.junit.Assert.assertEquals
import org.junit.Test

class CssCascadeTest {
    @Test
    fun `important inline specificity and source order follow css precedence`() {
        val classes = (1..11).map { "c$it" }.toSet()
        val node = CascadeNode("p", id = "target", classes = classes)
        val classSelector = classes.joinToString("") { ".$it" }
        val parsed = CssParser("test.css").parse(
            "$classSelector { color: red; margin-left: 1px } " +
                "#target { color: blue; margin-left: 2px } " +
                "p { color: green !important } p { margin-left: 3px }"
        )
        val inline = CssParser("inline").parseDeclarations("color: black; margin-left: 4px")
        val values = CssCascade.resolve(node, parsed.stylesheet.rules, inline, 300f, 500f)

        assertEquals(CssValue.Color(0xFF008000.toInt()), values["color"])
        assertEquals(CssValue.Length(4f, CssUnit.PX), values["margin-left"])
    }

    @Test
    fun `later source order wins and media candidates are evaluated at layout time`() {
        val node = CascadeNode("p")
        val parsed = CssParser("test.css").parse(
            "p { color:red } p { color:blue } @media screen and (min-width:400px) { p { color:rebeccapurple !important } }"
        )

        assertEquals(CssValue.Color(0xFF0000FF.toInt()), CssCascade.resolve(node, parsed.stylesheet.rules, viewportWidthPx = 300f)["color"])
        assertEquals(CssValue.Color(0xFF663399.toInt()), CssCascade.resolve(node, parsed.stylesheet.rules, viewportWidthPx = 500f)["color"])
    }
}

private class CascadeNode(
    override val tag: String,
    override val id: String? = null,
    override val classes: Set<String> = emptySet(),
    override val attributes: Map<String, String> = emptyMap(),
    override val parent: CssElementNode? = null,
    override val elementChildren: List<CssElementNode> = emptyList(),
    override val childIndex: Int = 0,
    override val childIndexOfType: Int = 0
) : CssElementNode
