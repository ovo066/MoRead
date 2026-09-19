package com.mozhi.reader.ui

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.junit.Assert.*
import org.junit.Test

class BookNavigationTest {
    @Test fun bookRoutesAlwaysDeclareLongIdsAndRetainOtherArguments() {
        val action = navArgument("action") { type = NavType.StringType; nullable = true }
        listOf("book/{bookId}?action={action}", "reader/{bookId}", "listen/{bookId}", "companion-chat/{bookId}").forEach { route ->
            val arguments = bookNavigationArguments(route, listOf(action))
            assertEquals(NavType.LongType, arguments.single { it.name == "bookId" }.argument.type)
            assertSame(action, arguments.single { it.name == "action" })
        }
        assertEquals(listOf(action), bookNavigationArguments("settings", listOf(action)))
    }

    @Test fun currentAndLegacySavedIdsResolveAtOneBoundary() {
        listOf(7L, 7, "7").forEach { raw ->
            assertEquals(7L, SavedStateHandle(mapOf("bookId" to raw)).requireBookId())
        }
        val large = Int.MAX_VALUE.toLong() + 10
        assertEquals(large, SavedStateHandle(mapOf("bookId" to large)).requireBookId())
        assertEquals(large, SavedStateHandle(mapOf("bookId" to large.toString())).requireBookId())
        listOf(0L, -1L).forEach { id ->
            assertEquals(id, SavedStateHandle(mapOf("bookId" to id)).requireBookId())
        }
        listOf(null, "bad", "9223372036854775808", 1.5).forEach { raw ->
            assertNull(SavedStateHandle(mapOf("bookId" to raw)).bookIdOrNull())
        }
    }
}
