package ac.jfx.openptv.ui

import ac.jfx.openptv.R
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.designsystem.ThemeMode
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.navigation.AppNavKey
import ac.jfx.openptv.feature.favourites.FavouritesRoute
import ac.jfx.openptv.feature.nearby.NearbyRoute
import ac.jfx.openptv.feature.runpattern.RunPatternRoute
import ac.jfx.openptv.feature.search.SearchScreen
import ac.jfx.openptv.feature.settings.SettingsRoute
import ac.jfx.openptv.feature.setup.SetupScreen
import ac.jfx.openptv.feature.stopdetail.StopDetailRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay

/**
 * Root composable. The persisted theme mode is wrapped around this composable in
 * [ac.jfx.openptv.MainActivity] via `SettingsProvider`, so children read `LocalThemeMode.current`
 * directly without an extra ViewModel hop.
 *
 * Setup gating still goes through the existing `:app`-owned `SettingsRepository` (backend URL
 * + `setupCompleted` flag) — that's a separate concern from user preferences.
 */
@Composable
fun App(appViewModel: AppViewModel = hiltViewModel()) {
    val gate by appViewModel.gate.collectAsStateWithLifecycle()

    when (gate) {
        GateState.Loading -> SplashLoader()
        GateState.NeedsSetup -> SetupScreen(onSetupComplete = { /* gate flow flips */ })
        GateState.Ready -> MainNav()
    }
}

@Composable
private fun MainNav() {
    val backStack = rememberNavBackStack(AppNavKey.Home())

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
            entryProvider {
                entry<AppNavKey.Home> { key ->
                    HomeScaffold(
                        focusLat = key.focusLat,
                        focusLon = key.focusLon,
                        onOpenStopDetail = { stopId, routeTypeCode, focusDestinationKey ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                    focusDestinationKey = focusDestinationKey,
                                ),
                            )
                        },
                        onOpenSettings = { backStack.add(AppNavKey.Settings) },
                    )
                }
                entry<AppNavKey.Search> {
                    SearchScreen(
                        onStopSelected = { stop ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stop.id.value,
                                    routeTypeCode = stop.routeType.toCode(),
                                ),
                            )
                        },
                        onOpenSettings = { backStack.add(AppNavKey.Settings) },
                    )
                }
                entry<AppNavKey.Favourites> {
                    FavouritesRoute(
                        onOpenStopDetail = { stopId, routeTypeCode, focusDestinationKey ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                    focusDestinationKey = focusDestinationKey,
                                ),
                            )
                        },
                        onOpenSearch = { backStack.add(AppNavKey.Search) },
                        onOpenSettings = { backStack.add(AppNavKey.Settings) },
                    )
                }
                entry<AppNavKey.StopDetail> { key ->
                    StopDetailRoute(
                        stopId = StopId(key.stopId),
                        routeType = RouteType.fromCode(key.routeTypeCode),
                        focusDestinationKey = key.focusDestinationKey,
                        // Issue #132: tapping a departure row opens the run-pattern destination
                        // for that service. `fromStopId` lets the pattern screen mark the stop
                        // the user came from.
                        onDepartureClicked = { departure ->
                            backStack.add(
                                AppNavKey.RunPattern(
                                    runRef = departure.runRef.value,
                                    routeTypeCode = key.routeTypeCode,
                                    fromStopId = key.stopId,
                                ),
                            )
                        },
                        // Issue #154: tapping the map icon on stop-detail returns the user to the
                        // bottom-nav surface (Home) framed on the stop, instead of pushing a
                        // standalone Nearby destination that hid the bottom nav bar. We reset the
                        // back stack to a fresh Home carrying the focus coords; the Home entry
                        // recomposes from scratch, lands on the Nearby tab, and forwards the coords
                        // to NearbyRoute, which re-centres the camera at street zoom and consumes
                        // the focus once via a `LaunchedEffect` keyed on the pair (issue #123).
                        onShowOnMap = { lat, lon ->
                            backStack.clear()
                            backStack.add(AppNavKey.Home(focusLat = lat, focusLon = lon))
                        },
                    )
                }
                entry<AppNavKey.RunPattern> { key ->
                    RunPatternRoute(
                        runRef = RunRef(key.runRef),
                        routeType = RouteType.fromCode(key.routeTypeCode),
                        fromStopId = key.fromStopId?.let(::StopId),
                    )
                }
                entry<AppNavKey.Settings> {
                    SettingsRoute()
                }
            },
    )
}

/**
 * Bottom-nav scaffold hosting the three top-level tabs: Favourites (default), Nearby, Search.
 * Each tab swaps the content under the [Scaffold] without pushing a back-stack entry, so the
 * system back button still pops out of the gate (consistent with the Material 3 bottom-nav
 * pattern).
 *
 * Settings used to be a fourth tab but moved behind a top-left gear on each of the three main
 * screens in issue #111 — tapping the gear pushes [AppNavKey.Settings] as a destination so system
 * back returns to whichever main screen launched it. [onOpenSettings] is the hook the gear
 * fires.
 *
 * `selectedTab` is saved via `rememberSaveable` so a configuration change (rotation, dark-mode
 * flip) keeps the user on the same tab; cold-launch lands on Favourites because the issue calls
 * Favourites the user's primary surface. The exception is when [focusLat]/[focusLon] are supplied
 * (stop-detail's "show on map", issue #154): the scaffold then opens on the Nearby tab framed on
 * that coordinate. This works because that entry point recreates Home via `clear()`+`add()`, so
 * the scaffold composes fresh and `rememberSaveable` re-initialises to Nearby.
 *
 * Search-tab clicks open the existing [SearchScreen] in place; selecting a stop inside it pushes
 * the stop-detail destination onto the back stack at the [MainNav] level. The Search destination
 * is *also* navigable directly (the favourites empty-state CTA pushes it as a destination), so
 * the tab variant and the destination variant are both wired.
 */
@Composable
private fun HomeScaffold(
    focusLat: Double?,
    focusLon: Double?,
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int, focusDestinationKey: String?) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(
            if (focusLat != null && focusLon != null) HomeTab.Nearby else HomeTab.Favourites,
        )
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                HomeTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            // Glyph stand-ins keep `:app` off the Material Icons artifact, same
                            // trade as the back arrow and favourite star elsewhere.
                            Text(
                                text = tab.glyph,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                        modifier =
                            Modifier
                                .testTag(tab.testTag)
                                .semantics { contentDescription = tab.semanticLabel },
                    )
                }
            }
        },
        // Only consume the navigation-bar inset here so the bottom-nav sits above the system bar.
        // The status-bar inset is left to the inner Scaffolds in each tab so their `TopAppBar`s
        // pad against the status bar exactly once. Without this, the inner Scaffold double-pads
        // and the gear lands ~28 dp lower than the ReadYou layout (issue #111 review).
        contentWindowInsets = WindowInsets.navigationBars,
        modifier = Modifier.testTag(TestTagHomeScaffold),
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                HomeTab.Favourites ->
                    FavouritesRoute(
                        onOpenStopDetail = onOpenStopDetail,
                        // Tapping "Search for a stop" from the empty-state CTA flips to the
                        // Search tab rather than pushing a destination, so the user stays inside
                        // the bottom-nav surface.
                        onOpenSearch = { selectedTab = HomeTab.Search },
                        onOpenSettings = onOpenSettings,
                    )
                HomeTab.Nearby ->
                    // When the user taps the Nearby tab directly, focusLat/focusLon are null and
                    // the map opens on whatever camera the VM already holds (existing UX). When the
                    // scaffold was opened by stop-detail's "show on map" (issue #154), the coords
                    // flow through here so the map re-centres on the stop at street zoom (issue
                    // #123). NearbyRoute consumes the focus once via a `LaunchedEffect`.
                    NearbyRoute(
                        focusLat = focusLat,
                        focusLon = focusLon,
                        onOpenStopDetail = { stopId, routeTypeCode ->
                            onOpenStopDetail(stopId, routeTypeCode, null)
                        },
                        onOpenSettings = onOpenSettings,
                    )
                HomeTab.Search ->
                    SearchScreen(
                        onStopSelected = { stop ->
                            onOpenStopDetail(stop.id.value, stop.routeType.toCode(), null)
                        },
                        onOpenSettings = onOpenSettings,
                    )
            }
        }
    }
}

// Bottom-nav tab order: Favourites (default surface), Nearby (Phase 05 — map-based discovery),
// Search (text discovery). Settings moved behind a top-left gear on each screen in issue #111.
// Nearby slots between Favourites and Search because both Nearby and Search are "find a stop"
// surfaces — Nearby by geography, Search by name — and reviewers can tap-cycle between them.
private enum class HomeTab(
    val glyph: String,
    val labelRes: Int,
    val semanticLabel: String,
    val testTag: String,
) {
    Favourites("★", R.string.bottom_nav_favourites, "Favourites tab", TestTagTabFavourites),
    Nearby("🗺", R.string.bottom_nav_nearby, "Nearby tab", TestTagTabNearby),
    Search("⌕", R.string.bottom_nav_search, "Search tab", TestTagTabSearch),
}

@Composable
private fun SplashLoader() {
    Scaffold { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

internal const val TestTagHomeScaffold: String = "home-scaffold"
internal const val TestTagTabFavourites: String = "home-tab-favourites"
internal const val TestTagTabNearby: String = "home-tab-nearby"
internal const val TestTagTabSearch: String = "home-tab-search"

/**
 * Maps the `:core:datastore` user-preference theme enum to the `:core:designsystem`
 * theme enum. The two are intentionally kept separate so the designsystem doesn't depend on
 * datastore — this single hop lives in `:app`'s composition root.
 */
internal fun ThemeModePreference.ThemeMode.toDesignSystem(): ThemeMode =
    when (this) {
        ThemeModePreference.ThemeMode.System -> ThemeMode.System
        ThemeModePreference.ThemeMode.Light -> ThemeMode.Light
        ThemeModePreference.ThemeMode.Dark -> ThemeMode.Dark
    }
