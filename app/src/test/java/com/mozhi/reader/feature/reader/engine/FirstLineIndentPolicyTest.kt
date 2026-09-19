package com.mozhi.reader.feature.reader.engine

import com.mozhi.reader.core.datastore.PublisherStyleMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 一字宽 10px；用户滑杆出厂值 2 字。 */
class FirstLineIndentPolicyTest {
    private fun indent(
        publisher: Float?,
        user: Float,
        mode: PublisherStyleMode,
        heading: Boolean = false
    ) = resolveFirstLineIndent(publisher, user, 10f, mode, heading)

    @Test fun `no publisher declaration uses the reader setting`() {
        assertEquals(20f, indent(null, 2f, PublisherStyleMode.SMART), .01f)
        assertEquals(0f, indent(null, 0f, PublisherStyleMode.SMART), .01f)
        assertEquals(40f, indent(null, 4f, PublisherStyleMode.RESPECT), .01f)
    }

    @Test fun `respect mode keeps every declared indent`() {
        assertEquals(30f, indent(30f, 0f, PublisherStyleMode.RESPECT), .01f)
        assertEquals(0f, indent(0f, 4f, PublisherStyleMode.RESPECT), .01f)
    }

    @Test fun `smart mode scales a declared indent by the reader ratio`() {
        // 出厂值下与原书一致：升级不改变已有 EPUB 的观感。
        assertEquals(30f, indent(30f, 2f, PublisherStyleMode.SMART), .01f)
        assertEquals(15f, indent(30f, 1f, PublisherStyleMode.SMART), .01f)
        assertEquals(0f, indent(30f, 0f, PublisherStyleMode.SMART), .01f)
    }

    @Test fun `a declared zero cannot be scaled so the reader setting wins`() {
        assertEquals(20f, indent(0f, 2f, PublisherStyleMode.SMART), .01f)
        assertEquals(0f, indent(0f, 0f, PublisherStyleMode.SMART), .01f)
    }

    @Test fun `take over mode uses the reader setting verbatim`() {
        assertEquals(20f, indent(40f, 2f, PublisherStyleMode.TAKE_OVER), .01f)
        assertEquals(0f, indent(40f, 0f, PublisherStyleMode.TAKE_OVER), .01f)
    }

    @Test fun `hanging indent is structure and survives every mode`() {
        listOf(PublisherStyleMode.RESPECT, PublisherStyleMode.SMART, PublisherStyleMode.TAKE_OVER)
            .forEach { assertEquals(-20f, indent(-20f, 4f, it), .01f) }
    }

    @Test fun `headings never take the body indent but keep their own`() {
        assertEquals(0f, indent(null, 2f, PublisherStyleMode.SMART, heading = true), .01f)
        assertEquals(0f, indent(0f, 2f, PublisherStyleMode.SMART, heading = true), .01f)
        assertEquals(0f, indent(30f, 2f, PublisherStyleMode.TAKE_OVER, heading = true), .01f)
        assertEquals(30f, indent(30f, 2f, PublisherStyleMode.RESPECT, heading = true), .01f)
        assertEquals(30f, indent(30f, 2f, PublisherStyleMode.SMART, heading = true), .01f)
    }
}
