/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.samples.apps.nowinandroid.ui

import androidx.compose.material3.adaptive.navigation.suite.ExperimentalMaterial3AdaptiveNavigationSuiteApi
import androidx.compose.material3.adaptive.navigation.suite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navOptions
import androidx.tracing.trace
import com.google.samples.apps.nowinandroid.core.data.repository.UserNewsResourceRepository
import com.google.samples.apps.nowinandroid.core.data.util.NetworkMonitor
import com.google.samples.apps.nowinandroid.core.ui.TrackDisposableJank
import com.google.samples.apps.nowinandroid.feature.bookmarks.navigation.bookmarksRoute
import com.google.samples.apps.nowinandroid.feature.bookmarks.navigation.navigateToBookmarks
import com.google.samples.apps.nowinandroid.feature.foryou.navigation.forYouNavigationRoute
import com.google.samples.apps.nowinandroid.feature.foryou.navigation.navigateToForYou
import com.google.samples.apps.nowinandroid.feature.interests.navigation.interestsRoute
import com.google.samples.apps.nowinandroid.feature.interests.navigation.navigateToInterestsGraph
import com.google.samples.apps.nowinandroid.feature.search.navigation.navigateToSearch
import com.google.samples.apps.nowinandroid.navigation.TopLevelDestination
import com.google.samples.apps.nowinandroid.navigation.TopLevelDestination.BOOKMARKS
import com.google.samples.apps.nowinandroid.navigation.TopLevelDestination.FOR_YOU
import com.google.samples.apps.nowinandroid.navigation.TopLevelDestination.INTERESTS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@Composable
fun rememberNiaAppState(
    windowSize: DpSize,
    networkMonitor: NetworkMonitor,
    userNewsResourceRepository: UserNewsResourceRepository,
    coroutineScope: CoroutineScope = rememberCoroutineScope(),
    navController: NavHostController = rememberNavController(),
): NiaAppState {
    NavigationTrackingSideEffect(navController)
    return remember(
        navController,
        coroutineScope,
        windowSize,
        networkMonitor,
        userNewsResourceRepository,
    ) {
        NiaAppState(
            navController,
            coroutineScope,
            windowSize,
            networkMonitor,
            userNewsResourceRepository,
        )
    }
}

@Stable
class NiaAppState(
    val navController: NavHostController,
    val coroutineScope: CoroutineScope,
    private val windowSize: DpSize,
    networkMonitor: NetworkMonitor,
    userNewsResourceRepository: UserNewsResourceRepository,
) {
    val currentDestination: NavDestination?
        @Composable get() = navController
            .currentBackStackEntryAsState().value?.destination

    val currentTopLevelDestination: TopLevelDestination?
        @Composable get() = when (currentDestination?.route) {
            forYouNavigationRoute -> FOR_YOU
            bookmarksRoute -> BOOKMARKS
            interestsRoute -> INTERESTS
            else -> null
        }

    val isOffline = networkMonitor.isOnline
        .map(Boolean::not)
        .stateIn(
            scope = coroutineScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )

    /**
     * Map of top level destinations to be used in the TopBar, BottomBar and NavRail. The key is the
     * route.
     */
    val topLevelDestinations: List<TopLevelDestination> = TopLevelDestination.values().asList()

    /**
     * The top level destinations that have unread news resources.
     */
    val topLevelDestinationsWithUnreadResources: StateFlow<Set<TopLevelDestination>> =
        userNewsResourceRepository.observeAllForFollowedTopics()
            .combine(userNewsResourceRepository.observeAllBookmarked()) { forYouNewsResources, bookmarkedNewsResources ->
                setOfNotNull(
                    FOR_YOU.takeIf { forYouNewsResources.any { !it.hasBeenViewed } },
                    BOOKMARKS.takeIf { bookmarkedNewsResources.any { !it.hasBeenViewed } },
                )
            }.stateIn(
                coroutineScope,
                SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet(),
            )

    /**
     * Per <a href="https://m3.material.io/components/navigation-drawer/guidelines">Material Design 3 guidelines</a>,
     * the selection of the appropriate navigation component should be contingent on the available
     * window size:
     * - Bottom Bar for compact window sizes (below 600dp)
     * - Navigation Rail for medium and expanded window sizes up to 1240dp (between 600dp and 1240dp)
     * - Navigation Drawer to window size above 1240dp
     */
    @OptIn(ExperimentalMaterial3AdaptiveNavigationSuiteApi::class)
    val navigationSuiteType: NavigationSuiteType
        @Composable get() {
            return if (windowSize.width > 1240.dp) {
                NavigationSuiteType.NavigationDrawer
            } else if (windowSize.width >= 600.dp) {
                NavigationSuiteType.NavigationRail
            } else {
                NavigationSuiteType.NavigationBar
            }
        }

    /**
     * UI logic for navigating to a top level destination in the app. Top level destinations have
     * only one copy of the destination of the back stack, and save and restore state whenever you
     * navigate to and from it.
     *
     * @param topLevelDestination: The destination the app needs to navigate to.
     */
    fun navigateToTopLevelDestination(topLevelDestination: TopLevelDestination) {
        trace("Navigation: ${topLevelDestination.name}") {
            val topLevelNavOptions = navOptions {
                // Pop up to the start destination of the graph to
                // avoid building up a large stack of destinations
                // on the back stack as users select items
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                // Avoid multiple copies of the same destination when
                // reselecting the same item
                launchSingleTop = true
                // Restore state when reselecting a previously selected item
                restoreState = true
            }

            when (topLevelDestination) {
                FOR_YOU -> navController.navigateToForYou(topLevelNavOptions)
                BOOKMARKS -> navController.navigateToBookmarks(topLevelNavOptions)
                INTERESTS -> navController.navigateToInterestsGraph(topLevelNavOptions)
            }
        }
    }

    fun navigateToSearch() {
        navController.navigateToSearch()
    }
}

/**
 * Stores information about navigation events to be used with JankStats
 */
@Composable
private fun NavigationTrackingSideEffect(navController: NavHostController) {
    TrackDisposableJank(navController) { metricsHolder ->
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            metricsHolder.state?.putState("Navigation", destination.route.toString())
        }

        navController.addOnDestinationChangedListener(listener)

        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }
}
