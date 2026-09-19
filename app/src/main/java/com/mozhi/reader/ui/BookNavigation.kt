package com.mozhi.reader.ui

import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument

private const val BOOK_ID = "bookId"

/** All book destinations use Long; normalization here also accepts older restored entries. */
internal fun bookNavigationArguments(route: String, arguments: List<NamedNavArgument>): List<NamedNavArgument> {
    if ("{$BOOK_ID}" !in route) return arguments
    val explicit = arguments.firstOrNull { it.name == BOOK_ID }
    require(explicit == null || explicit.argument.type == NavType.LongType) { "bookId must use NavType.LongType" }
    return if (explicit != null) arguments else listOf(navArgument(BOOK_ID) { type = NavType.LongType }) + arguments
}

internal fun SavedStateHandle.bookIdOrNull(): Long? = normalizedBookId(get<Any>(BOOK_ID))

internal fun SavedStateHandle.requireBookId(): Long = bookIdOrNull() ?: error("缺少或无效的 bookId")

@Suppress("DEPRECATION")
internal fun NavBackStackEntry.bookIdOrNull(): Long? = normalizedBookId(arguments?.get(BOOK_ID))

private fun normalizedBookId(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
}
