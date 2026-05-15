package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.designsystem.LocationPermissionRationale
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful Hilt-aware entry point. Wires the [OpenPtvMap] seam (injected via Hilt; production
 * binds [MapLibreOpenPtvMap]) into [NearbyScreen]. `OpenPtvMapInitialiser.init()` is fired here
 * via `LaunchedEffect(Unit)` so the maps stack is up before the first composition that touches
 * MapView.
 *
 * @param onOpenStopDetail navigates to stop detail. The screen produces `(stopId, routeTypeCode)`
 *   exactly — there's no `focusRouteId` from the map (we don't know which route the user wants
 *   from a single pin tap, so the screen lands on stop-detail's grouped view).
 */
@Composable
fun NearbyRoute(
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel(),
    map: OpenPtvMap = hiltViewModel<NearbyMapHolder>().map,
    initialiser: OpenPtvMapInitialiser = hiltViewModel<NearbyMapHolder>().initialiser,
    timeFormatter: RelativeTimeFormatter = hiltViewModel<NearbyMapHolder>().timeFormatter,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        initialiser.init()
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            viewModel.onPermissionResult(granted)
        }

    // Track "have we already asked this session" so re-entering the screen after the system prompt
    // doesn't re-show the rationale dialog forever. Saved through config change.
    var rationaleDismissed by rememberSaveable { mutableStateOf(false) }

    // Pre-grant check: a user who already granted on a previous launch should land in `Loaded`
    // immediately. Fired once on entry.
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.onPermissionResult(true)
            rationaleDismissed = true
        }
    }

    NearbyScreen(
        uiState = uiState,
        map = map,
        timeFormatter = timeFormatter,
        onCameraIdle = viewModel::onCameraIdle,
        onPinClicked = viewModel::onPinClicked,
        onSheetDismissed = viewModel::onSheetDismissed,
        onFollowMeClicked = viewModel::onFollowMeClicked,
        onRouteTypeFilterToggled = viewModel::onRouteTypeFilterToggled,
        onViewStop = { stop ->
            viewModel.onSheetDismissed()
            onOpenStopDetail(stop.id.value, stop.routeType.toCode())
        },
        onOpenAppSettings = {
            val intent =
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            context.startActivity(intent)
        },
        rationaleDismissed = rationaleDismissed,
        onRationaleDismiss = {
            rationaleDismissed = true
            viewModel.onPermissionResult(false)
        },
        onRationaleConfirm = {
            rationaleDismissed = true
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        },
    )
}

/**
 * Stateless screen. Renders the map at full-bleed; permission overlays + the follow-me FAB sit on
 * top via a Box stack; the bottom sheet animates up when [SheetState.Open] lands in `uiState`.
 *
 * The route-type filter row sits OVER the map (not above it in a separate column) so the map
 * stays full-bleed — the chips overlay the top of the map content with a translucent surface
 * underneath them, mirroring how Google Maps renders its mode chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun NearbyScreen(
    uiState: NearbyUiState,
    map: OpenPtvMap,
    timeFormatter: RelativeTimeFormatter,
    onCameraIdle: (OpenPtvCameraState) -> Unit,
    onPinClicked: (Stop) -> Unit,
    onSheetDismissed: () -> Unit,
    onFollowMeClicked: () -> Unit,
    onRouteTypeFilterToggled: (RouteType) -> Unit,
    onViewStop: (Stop) -> Unit,
    onOpenAppSettings: () -> Unit,
    rationaleDismissed: Boolean,
    onRationaleDismiss: () -> Unit,
    onRationaleConfirm: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_nearby_title)) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        modifier = Modifier.testTag(TestTagRoot),
    ) { padding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Underlying map — always present. The variant decides whether overlays show.
            val camera = uiState.cameraOrCbd()
            val rawPins = uiState.pinsOrEmpty()
            val pins = rawPins.filteredBy(uiState.routeTypeFilter)
            val userLocation = (uiState as? NearbyUiState.Loaded)?.userLocation
            map.Render(
                camera = camera,
                userLocation = userLocation,
                pins = pins,
                isDark = isDark,
                onCameraIdle = onCameraIdle,
                onPinClicked = onPinClicked,
                modifier = Modifier.fillMaxSize().testTag(TestTagMap),
            )

            // Filter chip row pinned to the top of the map content.
            RouteTypeFilterRow(
                selected = uiState.routeTypeFilter,
                onToggle = onRouteTypeFilterToggled,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag(TestTagFilterRow),
            )

            // Permission banner (denied state) — sits below the chip row so both are visible.
            if (uiState is NearbyUiState.PermissionDenied) {
                PermissionDeniedBanner(
                    onOpenSettings = onOpenAppSettings,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                            .testTag(TestTagPermissionDeniedBanner),
                )
            }

            // Empty-state hint over the map when a fetch returned no pins for the region
            if (uiState is NearbyUiState.Loaded && uiState.showEmptyHint) {
                EmptyStateHint(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(32.dp)
                            .testTag(TestTagEmptyHint),
                )
            }

            // Follow-me FAB — only useful when there's a user location to centre on
            if (uiState is NearbyUiState.Loaded && uiState.userLocation != null) {
                FloatingActionButton(
                    onClick = onFollowMeClicked,
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .testTag(TestTagFollowMeFab),
                ) {
                    Text("⌖", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }

    // Rationale dialog — shown only once per session, only when we haven't asked yet
    if (uiState is NearbyUiState.PermissionUnasked && !rationaleDismissed) {
        LocationPermissionRationale(
            onConfirm = onRationaleConfirm,
            onDismiss = onRationaleDismiss,
        )
    }

    // Bottom sheet for a tapped pin
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val pendingSheet = (uiState as? NearbyUiState.Loaded)?.pendingSheet
    if (pendingSheet is SheetState.Open) {
        ModalBottomSheet(
            onDismissRequest = onSheetDismissed,
            sheetState = sheetState,
            modifier = Modifier.testTag(TestTagBottomSheet),
        ) {
            StopBottomSheetContent(
                sheet = pendingSheet.sheet,
                timeFormatter = timeFormatter,
                onViewStop = { onViewStop(pendingSheet.sheet.stop) },
            )
        }
    }

    // Compose's `rememberCoroutineScope()` returns a scope that survives recomposition. Held but
    // unused for v1 — keep it around for the follow-up where "Pin tap → camera animates onto pin
    // before sheet opens" needs to launch from a Compose scope.
    @Suppress("UNUSED_EXPRESSION")
    scope
}

/**
 * Horizontal row of [FilterChip]s, one per visible [RouteType] (Unknown is intentionally absent
 * — it's a runtime fallback, never a user-facing mode). The row scrolls horizontally so the five
 * chips fit comfortably on a 360-dp width device without overflowing into a menu — the five
 * common modes are short labels and a `horizontalScroll` is the cheapest "still readable on a
 * narrow phone" option that keeps every chip discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteTypeFilterRow(
    selected: Set<RouteType>,
    onToggle: (RouteType) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier =
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { contentDescription = "Filter by mode" },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            filterTypes.forEach { routeType ->
                val isSelected = selected.contains(routeType)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(routeType) },
                    label = { Text(routeType.label()) },
                    leadingIcon = { Text(routeType.glyph()) },
                    colors = FilterChipDefaults.filterChipColors(),
                    modifier = Modifier.testTag(filterChipTestTag(routeType)),
                )
            }
        }
    }
}

@Composable
private fun PermissionDeniedBanner(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.feature_nearby_permission_denied_banner),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.testTag(TestTagOpenSettings),
            ) {
                Text(stringResource(R.string.feature_nearby_permission_open_settings))
            }
        }
    }
}

@Composable
private fun EmptyStateHint(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.feature_nearby_no_stops),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.feature_nearby_no_stops_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Bottom sheet body for a tapped pin. Three sections: stop header, serving routes, next
 * departures. Each fetched section ([StopBottomSheet.routes] / [StopBottomSheet.departures])
 * shows a placeholder line while `null` (i.e. the fetch hasn't landed yet); empty list renders
 * a "no rows" message; populated list renders the rows. The error chip surfaces above the
 * sections when at least one fetch failed.
 */
@Composable
@Suppress("LongMethod")
private fun StopBottomSheetContent(
    sheet: StopBottomSheet,
    timeFormatter: RelativeTimeFormatter,
    onViewStop: () -> Unit,
) {
    Column(modifier = Modifier.padding(24.dp).testTag(TestTagSheetContent)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = sheet.stop.routeType.glyph(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(end = 12.dp),
            )
            Column {
                Text(
                    text = sheet.stop.name,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = sheet.stop.suburb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (sheet.hadError) {
            Text(
                text = stringResource(R.string.feature_nearby_sheet_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(TestTagSheetError),
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Routes section
        SectionHeader(text = stringResource(R.string.feature_nearby_sheet_routes))
        when (val routes = sheet.routes) {
            null ->
                Text(
                    text = stringResource(R.string.feature_nearby_sheet_routes_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                if (routes.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feature_nearby_sheet_routes_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(
                        modifier =
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp)
                                .testTag(TestTagSheetRoutes),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        routes.forEach { route -> RouteChip(route) }
                    }
                }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Departures section
        SectionHeader(text = stringResource(R.string.feature_nearby_sheet_next_departures))
        when (val departures = sheet.departures) {
            null ->
                Text(
                    text = stringResource(R.string.feature_nearby_sheet_departures_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else ->
                if (departures.isEmpty()) {
                    Text(
                        text = stringResource(R.string.feature_nearby_sheet_departures_none),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .padding(top = 4.dp)
                                .testTag(TestTagSheetDepartures),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        departures.forEach { departure ->
                            DepartureRow(departure, timeFormatter)
                        }
                    }
                }
        }

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(
            onClick = onViewStop,
            modifier = Modifier.testTag(TestTagViewStop),
        ) {
            Text(stringResource(R.string.feature_nearby_view_stop))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RouteChip(route: Route) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        val label = route.number.ifBlank { route.name }.ifBlank { "#${route.id.value}" }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun DepartureRow(
    departure: Departure,
    timeFormatter: RelativeTimeFormatter,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = departure.direction.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text =
                timeFormatter.format(
                    scheduled = departure.scheduledDepartureUtc,
                    estimated = departure.estimatedDepartureUtc,
                ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Hilt-injected holder for the [OpenPtvMap] singleton + [OpenPtvMapInitialiser] +
 * [RelativeTimeFormatter]. We resolve them through a ViewModel because `@Composable fun
 * NearbyRoute(...)` has no other Hilt-aware seam apart from `hiltViewModel`. This is the same
 * trick `:feature:stop-detail` uses for cross-VM dependencies.
 */
@dagger.hilt.android.lifecycle.HiltViewModel
class NearbyMapHolder
    @javax.inject.Inject
    constructor(
        val map: OpenPtvMap,
        val initialiser: OpenPtvMapInitialiser,
        val timeFormatter: RelativeTimeFormatter,
    ) : androidx.lifecycle.ViewModel()

// ----------- helpers -----------

private val filterTypes: List<RouteType> =
    listOf(RouteType.Train, RouteType.Tram, RouteType.Bus, RouteType.VLine, RouteType.NightBus)

@Composable
private fun RouteType.label(): String =
    when (this) {
        RouteType.Train -> stringResource(R.string.feature_nearby_filter_train)
        RouteType.Tram -> stringResource(R.string.feature_nearby_filter_tram)
        RouteType.Bus -> stringResource(R.string.feature_nearby_filter_bus)
        RouteType.VLine -> stringResource(R.string.feature_nearby_filter_vline)
        RouteType.NightBus -> stringResource(R.string.feature_nearby_filter_nightbus)
        // Unknown isn't in FILTER_TYPES, but the `when` has to be exhaustive — fall back to its
        // glyph so a future caller never crashes.
        RouteType.Unknown -> "•"
    }

private fun NearbyUiState.cameraOrCbd(): OpenPtvCameraState =
    when (this) {
        is NearbyUiState.Loaded -> camera
        is NearbyUiState.PermissionDenied -> camera
        NearbyUiState.PermissionUnasked ->
            OpenPtvCameraState(
                centre = NearbyViewModel.MELBOURNE_CBD,
                zoom = NearbyViewModel.INITIAL_ZOOM,
            )
    }

private fun NearbyUiState.pinsOrEmpty(): List<Stop> =
    when (this) {
        is NearbyUiState.Loaded -> pins
        is NearbyUiState.PermissionDenied -> pins
        NearbyUiState.PermissionUnasked -> emptyList()
    }

/**
 * Belt-and-braces filter for the pin list. The repository fetch already passes the filter set
 * to PTV via `route_types`, but a stale fetch can land mid-toggle (e.g. between the user
 * tapping a chip and the debounce expiring). Re-applying the filter at the render seam keeps
 * the on-screen pins consistent with the chip state at all times.
 */
private fun List<Stop>.filteredBy(filter: Set<RouteType>): List<Stop> =
    if (filter.isEmpty()) this else filter { it.routeType in filter }

private fun RouteType.glyph(): String =
    when (this) {
        RouteType.Train -> "🚆"
        RouteType.Tram -> "🚊"
        RouteType.Bus -> "🚌"
        RouteType.VLine -> "🚉"
        RouteType.NightBus -> "🌙"
        RouteType.Unknown -> "•"
    }

/**
 * Estimate luminance from an `argb` color. Material 3's `ColorScheme.surface` is the host theme's
 * primary background; a low-luminance value means we're in dark mode. We do this rather than
 * reading a `LocalThemeMode` because `:feature:nearby` doesn't depend on `:core:datastore` and
 * the designsystem's composition local lives there. Same trick `:feature:favourites` could use.
 *
 * Coefficients are the ITU-R BT.709 perceptual-luminance weights — the same formula `WCAG`
 * uses to score contrast. Named constants so detekt's `MagicNumber` rule stays happy.
 */
private fun androidx.compose.ui.graphics.Color.luminance(): Float =
    RED_LUMA_COEFF * red + GREEN_LUMA_COEFF * green + BLUE_LUMA_COEFF * blue

private const val RED_LUMA_COEFF: Float = 0.2126f
private const val GREEN_LUMA_COEFF: Float = 0.7152f
private const val BLUE_LUMA_COEFF: Float = 0.0722f
private const val DARK_LUMINANCE_THRESHOLD: Float = 0.5f

internal const val TestTagRoot: String = "nearby-root"
internal const val TestTagMap: String = "nearby-map"
internal const val TestTagFollowMeFab: String = "nearby-follow-me-fab"
internal const val TestTagPermissionDeniedBanner: String = "nearby-permission-denied-banner"
internal const val TestTagOpenSettings: String = "nearby-open-settings"
internal const val TestTagEmptyHint: String = "nearby-empty-hint"
internal const val TestTagBottomSheet: String = "nearby-bottom-sheet"
internal const val TestTagSheetContent: String = "nearby-sheet-content"
internal const val TestTagSheetError: String = "nearby-sheet-error"
internal const val TestTagSheetRoutes: String = "nearby-sheet-routes"
internal const val TestTagSheetDepartures: String = "nearby-sheet-departures"
internal const val TestTagViewStop: String = "nearby-view-stop"
internal const val TestTagFilterRow: String = "nearby-filter-row"

internal fun filterChipTestTag(routeType: RouteType): String = "nearby-filter-chip-${routeType.name.lowercase()}"
