package com.mozhi.reader.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationMotionTest {
    @Test fun onlyPairsOfRootsUseFadeThrough() {
        RootDestination.entries.forEach { from ->
            RootDestination.entries.forEach { to ->
                assertTrue("${from.route} -> ${to.route}", isRootSwitch(from.route, to.route))
            }
            listOf("reader/{bookId}", "book/{bookId}", "settings-reading", "companion-chat/{bookId}").forEach { child ->
                assertFalse(isRootSwitch(from.route, child))
                assertFalse(isRootSwitch(child, from.route))
                assertFalse(isRootSwitch(child, child))
            }
        }
    }

    @Test fun missingOrNestedRoutesAreNotMistakenForRootSwitches() {
        assertFalse(isRootRoute(null))
        assertFalse(isRootRoute("bookshelf/detail"))
        assertFalse(isRootSwitch(null, "bookshelf"))
        assertFalse(isRootSwitch("reader/{bookId}", null))
    }
}
