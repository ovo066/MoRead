package com.mozhi.reader.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionToolbarItemsTest {
    @Test fun `common actions stay first and the rest go under more`() {
        val (primary, overflow) = SelectionToolbarItems.split(emptySet(), editable = true)
        assertEquals(listOf("annotate", "copy", "dictionary", "translate", "ask"), primary)
        assertEquals(listOf("analyze", "paragraph", "speak", "image", "edit"), overflow)
    }

    @Test fun `chosen extras join the first row in a stable order`() {
        val (primary, overflow) = SelectionToolbarItems.split(setOf("image", "speak"), editable = true)
        assertEquals(listOf("annotate", "copy", "dictionary", "translate", "ask", "speak", "image"), primary)
        assertEquals(listOf("analyze", "paragraph", "edit"), overflow)
    }

    @Test fun `edit disappears when the text cannot be edited`() {
        val (primary, overflow) = SelectionToolbarItems.split(setOf("edit"), editable = false)
        assertEquals(SelectionToolbarItems.PRIMARY, primary)
        assertEquals(listOf("analyze", "paragraph", "speak", "image"), overflow)
    }
}
