package com.mozhi.reader.ui

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

internal const val ROOT_FADE_OUT_MS = 90
internal const val ROOT_FADE_IN_MS = 210
internal const val PUSH_MS = 280

internal fun isRootRoute(route: String?): Boolean = RootDestination.entries.any { it.route == route }

internal fun isRootSwitch(initialRoute: String?, targetRoute: String?): Boolean =
    isRootRoute(initialRoute) && isRootRoute(targetRoute)

// Select motion for the pair, never just the destination. A root returning from a child must
// mirror that child's shared-axis exit, not run a delayed/scaled root-tab entrance underneath it.
internal fun navigationEnter(initialRoute: String?, targetRoute: String?, pop: Boolean): EnterTransition =
    if (isRootSwitch(initialRoute, targetRoute)) {
        // Do not scale the page-sized gradient or a restored list during root-tab switches.
        fadeIn(tween(ROOT_FADE_IN_MS, delayMillis = ROOT_FADE_OUT_MS, easing = LinearOutSlowInEasing))
    } else {
        slideInHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { if (pop) -it / 8 else it / 4 } +
            fadeIn(tween(PUSH_MS, easing = LinearOutSlowInEasing))
    }

internal fun navigationExit(initialRoute: String?, targetRoute: String?, pop: Boolean): ExitTransition =
    if (isRootSwitch(initialRoute, targetRoute)) {
        fadeOut(tween(ROOT_FADE_OUT_MS, easing = FastOutLinearInEasing))
    } else {
        slideOutHorizontally(tween(PUSH_MS, easing = FastOutSlowInEasing)) { if (pop) it / 4 else -it / 8 } +
            fadeOut(tween(160, easing = FastOutLinearInEasing))
    }

@Composable
internal fun MoReadNavigationHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    builder: NavGraphBuilder.() -> Unit
) {
    NavHost(
        navController = navController,
        startDestination = RootDestination.Bookshelf.route,
        modifier = modifier,
        enterTransition = { navigationEnter(initialState.destination.route, targetState.destination.route, pop = false) },
        exitTransition = { navigationExit(initialState.destination.route, targetState.destination.route, pop = false) },
        popEnterTransition = { navigationEnter(initialState.destination.route, targetState.destination.route, pop = true) },
        popExitTransition = { navigationExit(initialState.destination.route, targetState.destination.route, pop = true) },
        builder = builder
    )
}

/** The root viewport is independent of the reader's transient status-bar visibility. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun stableNavigationInsets(): WindowInsets =
    WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

@Composable
internal fun MoReadNavigationScaffold(
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = stableNavigationInsets(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        content = content
    )
}

/** Reserve the rail inside each root entry, never on the shared NavHost's parent. */
internal fun NavGraphBuilder.rootComposable(
    route: String,
    expanded: Boolean,
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(route) { entry ->
    Box(
        Modifier.fillMaxSize().padding(
            start = if (expanded) MoReadLayoutPolicy.NavigationRailWidthDp.dp else 0.dp
        )
    ) {
        content(entry)
    }
}

/** Secondary entries inherit the same pair-aware motion as their outgoing/incoming root. */
internal fun NavGraphBuilder.pushComposable(
    route: String,
    arguments: List<NamedNavArgument> = emptyList(),
    content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit
) = composable(route = route, arguments = bookNavigationArguments(route, arguments), content = content)
