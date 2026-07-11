package ac.jfx.openptv.ui

import ac.jfx.openptv.R
import ac.jfx.openptv.alert.AlightAlertService
import ac.jfx.openptv.core.datastore.preference.ThemeModePreference
import ac.jfx.openptv.core.designsystem.ThemeMode
import ac.jfx.openptv.core.domain.TripProgress
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.navigation.AppNavKey
import ac.jfx.openptv.feature.favourites.FavouritesRoute
import ac.jfx.openptv.feature.journeyplanner.JourneyPlannerRoute
import ac.jfx.openptv.feature.nearby.NearbyRoute
import ac.jfx.openptv.feature.runpattern.RunPatternRoute
import ac.jfx.openptv.feature.search.SearchScreen
import ac.jfx.openptv.feature.settings.SettingsRoute
import ac.jfx.openptv.feature.setup.SetupScreen
import ac.jfx.openptv.feature.stopdetail.StopDetailRoute
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.awaitCancellation
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

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
        GateState.Ready -> MainNav(appViewModel)
    }
}

/**
 * Nav host plus the app-wide pinned "Return to your trip" bar (issue #200). The bar sits
 * *below* the [NavDisplay] in a [Column] — not overlaid — so it can never obscure screen
 * content; everything above simply shrinks. On the Home surface, which owns the bottom
 * [NavigationBar], the bar instead docks *above* the nav bar (mini-player placement) by
 * rendering inside [HomeScaffold]'s `bottomBar` slot. It is hidden while the followed run's
 * own pattern screen is on top (the bar would only navigate to where the user already is)
 * and while nothing is followed.
 *
 * Inset handling: when the bar shows at the app level it becomes the bottom-most window
 * element, so it takes the navigation-bar inset itself ([navigationBarsPadding]) and the nav
 * content *consumes* that inset — otherwise every screen's own Scaffold would double-pad
 * against a system bar the bar already cleared. Inside [HomeScaffold] the [NavigationBar]
 * below the bar already clears the system bar, so the bar pads nothing and Home's insets are
 * left untouched.
 */
@Composable
private fun MainNav(appViewModel: AppViewModel) {
    val backStack = rememberNavBackStack(AppNavKey.Home())
    val followedTrip by appViewModel.followedTrip.collectAsStateWithLifecycle()
    val tripProgress by appViewModel.tripProgress.collectAsStateWithLifecycle()

    // In-app completion check: the stored trip only re-emits on writes, so a trip that finished
    // while the app was backgrounded is re-evaluated against the clock on every resume. The same
    // RESUMED window drives the bar's "Next stop" poll: start on entering (which also forces an
    // immediate refresh — the pattern flow fetches on subscription), stop on leaving so a
    // backgrounded app never polls.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            appViewModel.evaluateFollowedTripCompletion()
            appViewModel.startTripProgressPolling()
            try {
                awaitCancellation()
            } finally {
                appViewModel.stopTripProgressPolling()
            }
        }
    }

    // Alight alerts (issue #201): whenever the followed trip carries an armed alert — armed
    // just now on the run-pattern screen, or persisted from before an app restart — make sure
    // the tracking foreground service is running. The service stops itself when the alert
    // goes away (disarm, unfollow, completion), so only the start needs wiring here.
    val context = LocalContext.current
    val alightArmed = followedTrip?.alightAlert != null
    LaunchedEffect(alightArmed) {
        if (alightArmed) {
            context.startForegroundService(Intent(context, AlightAlertService::class.java))
        }
    }

    val trip = followedTrip
    val topKey = backStack.lastOrNull()
    val showReturnBar =
        trip != null && !(topKey is AppNavKey.RunPattern && topKey.runRef == trip.runRef.value)
    val barInHomeScaffold = showReturnBar && topKey is AppNavKey.Home
    val openFollowedRun: () -> Unit = {
        trip?.let {
            backStack.add(
                AppNavKey.RunPattern(
                    runRef = it.runRef.value,
                    routeTypeCode = it.routeType.toCode(),
                    fromStopId = it.fromStopId?.value,
                ),
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .then(
                        if (showReturnBar && !barInHomeScaffold) {
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        } else {
                            Modifier
                        },
                    ),
        ) {
            MainNavDisplay(
                backStack = backStack,
                homeFollowedTripBar =
                    if (trip != null && barInHomeScaffold) {
                        {
                            FollowedTripBar(
                                trip = trip,
                                progress = tripProgress,
                                onOpen = openFollowedRun,
                                onUnfollow = appViewModel::unfollowTrip,
                                padSystemNavBar = false,
                            )
                        }
                    } else {
                        null
                    },
            )
        }
        if (trip != null && showReturnBar && !barInHomeScaffold) {
            FollowedTripBar(
                trip = trip,
                progress = tripProgress,
                onOpen = openFollowedRun,
                onUnfollow = appViewModel::unfollowTrip,
            )
        }
    }
}

@Composable
private fun MainNavDisplay(
    backStack: NavBackStack<NavKey>,
    homeFollowedTripBar: (@Composable () -> Unit)?,
) {
    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider =
            entryProvider {
                entry<AppNavKey.Home> { key ->
                    HomeScaffold(
                        focusLat = key.focusLat,
                        focusLon = key.focusLon,
                        followedTripBar = homeFollowedTripBar,
                        onOpenStopDetail = { stopId, routeTypeCode, focusDestinationKey ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId,
                                    routeTypeCode = routeTypeCode,
                                    focusDestinationKey = focusDestinationKey,
                                ),
                            )
                        },
                        // Issue #204: tapping a journey result opens the run-pattern destination
                        // for that service, with the origin stop marked as "you are here".
                        onOpenRunPattern = { runRef, routeTypeCode, fromStopId ->
                            backStack.add(
                                AppNavKey.RunPattern(
                                    runRef = runRef,
                                    routeTypeCode = routeTypeCode,
                                    fromStopId = fromStopId,
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
                        // Tapping a stop in the run timeline opens that stop's detail. Every stop on
                        // the run shares the run's route type, so we reuse the destination's
                        // routeTypeCode rather than threading a per-stop value.
                        onStopClicked = { stopId ->
                            backStack.add(
                                AppNavKey.StopDetail(
                                    stopId = stopId.value,
                                    routeTypeCode = key.routeTypeCode,
                                ),
                            )
                        },
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
    followedTripBar: (@Composable () -> Unit)?,
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int, focusDestinationKey: String?) -> Unit,
    onOpenRunPattern: (runRef: String, routeTypeCode: Int, fromStopId: Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var selectedTab by rememberSaveable {
        mutableStateOf(
            if (focusLat != null && focusLon != null) HomeTab.Nearby else HomeTab.Favourites,
        )
    }

    Scaffold(
        bottomBar = {
            // The followed-trip bar docks directly above the bottom nav (issue #200 review):
            // stacking it in the bottomBar slot keeps Scaffold's content padding covering both.
            Column {
                followedTripBar?.invoke()
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
                HomeTab.Journey ->
                    JourneyPlannerRoute(
                        onOpenRunPattern = { runRef, routeType, fromStopId ->
                            onOpenRunPattern(runRef.value, routeType.toCode(), fromStopId.value)
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

    // Journey planner (issue #204) slots before Search so the planning surface sits next to the
    // map — Search stays in the corner where muscle memory expects a text box.
    Journey("⇄", R.string.bottom_nav_journey, "Journey planner tab", TestTagTabJourney),
    Search("⌕", R.string.bottom_nav_search, "Search tab", TestTagTabSearch),
}

/**
 * The pinned followed-trip bar (issue #200). Tapping the body reopens the run-pattern
 * destination for the followed run; the ✕ unfollows (glyph stand-in keeps `:app` off the
 * Material Icons artifact, same trade as the bottom-nav tabs).
 *
 * [padSystemNavBar] is true when the bar is the bottom-most window element and must clear the
 * system navigation bar itself; false when it stacks above Home's [NavigationBar], which
 * already owns that inset.
 *
 * [progress] is the live progress line (PR #202 follow-up): "Next stop: …", plus — when this
 * trip has an armed alight alert (issue #201) — "· ~12 min to …" for the alight stop, both
 * derived from the foreground pattern poll. Null (nothing fetched yet, fetch failing) or a
 * progress with nothing to say (run out of upcoming stops, alight stop already passed) simply
 * drops the line/fragment, so the bar degrades to its static text and never shows an error.
 *
 * Long trip names (V/Line: "Bairnsdale - Melbourne via Sale & Traralgon to Southern Cross")
 * overflow *down*, bounded at two lines with ellipsis, never *out* — the text column takes
 * `weight(1f)` so the unfollow control always keeps its tap target (CLAUDE.md UI conventions).
 * The progress line follows the same rule, bounded at one line.
 */
@Composable
private fun FollowedTripBar(
    trip: FollowedTrip,
    progress: TripProgress?,
    onOpen: () -> Unit,
    onUnfollow: () -> Unit,
    padSystemNavBar: Boolean = true,
) {
    val openLabel = stringResource(R.string.followed_trip_open_content_description)
    val unfollowLabel = stringResource(R.string.followed_trip_unfollow_content_description)

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().testTag(TestTagFollowedTripBar),
    ) {
        Row(
            modifier =
                Modifier
                    .clickable(onClick = onOpen)
                    .then(if (padSystemNavBar) Modifier.navigationBarsPadding() else Modifier)
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { contentDescription = openLabel },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.followed_trip_return),
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = trip.displayName(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val progressLine = trip.progressLine(progress)
                if (progressLine != null) {
                    Text(
                        text = progressLine,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(TestTagFollowedTripNextStop),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            TextButton(
                onClick = onUnfollow,
                modifier =
                    Modifier
                        .testTag(TestTagFollowedTripUnfollow)
                        .semantics { contentDescription = unfollowLabel },
            ) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * The bar's live progress line: "Next stop: Richmond", extended with "· ~12 min to Jordanville"
 * when this trip has an armed alight alert and the poll has an ETA for its stop. Either
 * fragment can be absent independently (run finished vs. alight stop passed/off-pattern);
 * null when neither has anything to say, which drops the line entirely.
 */
@Composable
private fun FollowedTrip.progressLine(progress: TripProgress?): String? {
    if (progress == null) return null
    val nextStopFragment =
        progress.nextStopName?.let { stringResource(R.string.followed_trip_next_stop, it) }
    val alightStopName = alightAlert?.stopName
    val alightEta = progress.alightEta
    val etaFragment =
        if (alightStopName != null && alightEta != null) {
            stringResource(R.string.followed_trip_alight_eta, roughEta(alightEta), alightStopName)
        } else {
            null
        }
    return listOfNotNull(nextStopFragment, etaFragment)
        .takeIf { it.isNotEmpty() }
        ?.joinToString(separator = " · ")
}

/** Rough human ETA: "~12 min", or "<1 min" once the arrival is under a minute away. */
@Composable
private fun roughEta(eta: Duration): String =
    if (eta < 1.minutes) {
        stringResource(R.string.followed_trip_eta_under_minute)
    } else {
        stringResource(R.string.followed_trip_eta_minutes, eta.inWholeMinutes)
    }

/**
 * Human-readable trip name for the bar. Mirrors the run-pattern title's collapse rule so a
 * loop/terminus run whose line and destination share a name reads once, not "X to X".
 */
@Composable
private fun FollowedTrip.displayName(): String {
    val route = routeLabel
    return when {
        route == null -> destinationName
        route.equals(destinationName, ignoreCase = true) -> route
        else -> stringResource(R.string.followed_trip_format, route, destinationName)
    }
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
internal const val TestTagTabJourney: String = "home-tab-journey"
internal const val TestTagFollowedTripBar: String = "followed-trip-bar"
internal const val TestTagFollowedTripUnfollow: String = "followed-trip-unfollow"
internal const val TestTagFollowedTripNextStop: String = "followed-trip-next-stop"

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
