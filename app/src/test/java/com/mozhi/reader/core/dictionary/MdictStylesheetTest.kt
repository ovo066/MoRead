package com.mozhi.reader.core.dictionary

import org.junit.Assert.*
import org.junit.Test

class MdictStylesheetTest {
    @Test fun numberedLegacyStylesWrapEachRunAndRetainUnknownMarkers() {
        val styles = mdictStylesheet("1\r\n<h2 style='color:teal'>\r\n</h2>\r\n2\r\n<p>\r\n</p>\r\n")
        val html = applyMdictStyles("`1`【正】`2`正直；端正。`99`未定义", styles)
        assertEquals("<h2 style='color:teal'>【正】</h2><p>正直；端正。</p>`99`未定义", html)
        assertEquals("<b>正文</b>", applyMdictStyles("<b>正文</b>", styles))
    }
}
