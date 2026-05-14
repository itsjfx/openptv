package ac.jfx.openptv.ui

import ac.jfx.openptv.R
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.designsystem.ThemeMode
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.navigation.AppNavKey
import ac.jfx.openptv.feature.favourites.FavouritesRoute
import ac.jfx.openptv.feature.nearby.NearbyRoute
import ac.jfx.openptv.feature.search.SearchScreen
import ac.jfx.openptv.feature.settings.SettingsRoute
import ac.jfx.openptv.feature.setup.SetupScreen
import ac.jfx.openptv.feature.stopdetail.StopDetailRoute
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
    val backStack = rememberNavBackStack(AppNavKey.Home)

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
            entryProvider {
                entry<AppNavKey.Home> {
                    HomeScaffold(
                        onOpenStopDetail = { stopId, routeTypeCode, focusRouteId, focusDirectionId ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                    focusRouteId = focusRouteId,
                                    focusDirectionId = focusDirectionId,
                                ),
                            )
                        },
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
                    )
                }
                entry<AppNavKey.Favourites> {
                    FavouritesRoute(
                        onOpenStopDetail = { stopId, routeTypeCode, focusRouteId, focusDirectionId ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                    focusRouteId = focusRouteId,
                                    focusDirectionId = focusDirectionId,
                                ),
                            )
                        },
                        onOpenSearch = { backStack.add(AppNavKey.Search) },
                    )
                }
                entry<AppNavKey.Nearby> {
                    NearbyRoute(
                        onOpenStopDetail = { stopId, routeTypeCode ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                ),
                            )
                        },
                    )
                }
                entry<AppNavKey.StopDetail> { key ->
                    StopDetailRoute(
                        stopId = StopId(key.stopId),
                        routeType = RouteType.fromCode(key.routeTypeCode),
                        focusRouteId = key.focusRouteId,
                        focusDirectionId = key.focusDirectionId,
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<AppNavKey.Settings> {
                    SettingsRoute(onBack = { backStack.removeLastOrNull() })
                }
            },
    )
}

/**
 * Bottom-nav scaffold hosting the three top-level tabs: Favourites (default), Search, Settings.
 * Each tab swaps the content under the [Scaffold] without pushing a back-stack entry, so the
 * system back button still pops out of the gate (consistent with the Material 3 bottom-nav
 * pattern).
 *
 * `selectedTab` is saved via `rememberSaveable` so a configuration change (rotation, dark-mode
 * flip) keeps the user on the same tab; cold-launch always lands on Favourites because the issue
 * calls Favourites the user's primary surface.
 *
 * Search-tab clicks open the existing [SearchScreen] in place; selecting a stop inside it pushes
 * the stop-detail destination onto the back stack at the [MainNav] level. The Search destination
 * is *also* navigable directly (the favourites empty-state CTA pushes it as a destination), so
 * the tab variant and the destination variant are both wired.
 */
@Composable
private fun HomeScaffold(
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int, focusRouteId: Int, focusDirectionId: Int) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Favourites) }

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
                    )
                HomeTab.Nearby ->
                    NearbyRoute(
                        onOpenStopDetail = { stopId, routeTypeCode ->
                            onOpenStopDetail(stopId, routeTypeCode, -1, -1)
                        },
                    )
                HomeTab.Search ->
                    SearchScreen(
                        onStopSelected = { stop ->
                            onOpenStopDetail(stop.id.value, stop.routeType.toCode(), -1, -1)
                        },
                    )
                HomeTab.Settings ->
                    SettingsRoute(
                        // Back inside Settings just flips the tab back to Favourites because the
                        // user is inside a bottom-nav surface with no real "back" target.
                        onBack = { selectedTab = HomeTab.Favourites },
                    )
            }
        }
    }
}

// Bottom-nav tab order: Favourites (default surface), Nearby (Phase 05 — map-based discovery),
// Search (text discovery), Settings. Nearby slots between Favourites and Search because both
// Nearby and Search are "find a stop" surfaces — Nearby by geography, Search by name — and
// reviewers can tap-cycle between them without crossing Settings. `cc @itsjfx` to confirm.
private enum class HomeTab(
    val glyph: String,
    val labelRes: Int,
    val semanticLabel: String,
    val testTag: String,
) {
    Favourites("★", R.string.bottom_nav_favourites, "Favourites tab", TestTagTabFavourites),
    Nearby("🗺", R.string.bottom_nav_nearby, "Nearby tab", TestTagTabNearby),
    Search("⌕", R.string.bottom_nav_search, "Search tab", TestTagTabSearch),
    Settings("⚙", R.string.bottom_nav_settings, "Settings tab", TestTagTabSettings),
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
internal const val TestTagTabSettings: String = "home-tab-settings"

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
