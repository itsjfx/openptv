package ac.jfx.openptv.feature.nearby

import ac.jfx.openptv.core.common.DistanceFormatter
import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.designsystem.LocationPermissionRationale
import ac.jfx.openptv.core.model.Coordinates
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberStandardBottomSheetState
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
    distanceFormatter: DistanceFormatter = hiltViewModel<NearbyMapHolder>().distanceFormatter,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        initialiser.init()
    }

    // Request both COARSE and FINE together so Android 12+ shows the Precise/Approximate toggle
    // (issue #91). The user picks; we treat either grant as "we have location" because the
    // nearby map doesn't need fine-tighter accuracy than coarse. We use the multi-permission
    // contract for that reason — single `RequestPermission` would only let us pick one.
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { grants ->
            val granted =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            viewModel.onPermissionResult(granted)
        }

    // Track "have we already asked this session" so re-entering the screen after the system prompt
    // doesn't re-show the rationale dialog forever. Saved through config change.
    var rationaleDismissed by rememberSaveable { mutableStateOf(false) }

    // Pre-grant check: a user who already granted on a previous launch (either coarse OR fine —
    // a user who picked "Precise" in the system dialog has fine; "Approximate" gets coarse) should
    // land in `Loaded` immediately. Fired once on entry.
    LaunchedEffect(Unit) {
        val granted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
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
        distanceFormatter = distanceFormatter,
        onCameraIdle = viewModel::onCameraIdle,
        onCameraMoveStarted = viewModel::onCameraMoveStarted,
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
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
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
    distanceFormatter: DistanceFormatter,
    onCameraIdle: (OpenPtvCameraState) -> Unit,
    onCameraMoveStarted: () -> Unit,
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

    // Project the visible pins, filter, and (optional) user location into the row list. The
    // projection is computed in the screen — the ViewModel doesn't need to hold a `nearbyRows`
    // field because the list is purely a rendering of the same `pins` + `routeTypeFilter` the map
    // already reads. Keeping the projection here avoids forking state-of-truth.
    val rawPins = uiState.pinsOrEmpty()
    val filteredPins = rawPins.filteredBy(uiState.routeTypeFilter)
    val userLocation = (uiState as? NearbyUiState.Loaded)?.userLocation
    val userBearing = (uiState as? NearbyUiState.Loaded)?.userBearing
    val nearbyRows = filteredPins.toRows(from = userLocation)

    // Persistent peek-and-expand sheet. `BottomSheetScaffold` (M3) is the right shape for a
    // map + drawer co-existence — `ModalBottomSheet` would steal focus from the map. The peek
    // height shows just the drag handle + a section header so the map stays mostly visible.
    val scaffoldState =
        rememberBottomSheetScaffoldState(
            bottomSheetState =
                rememberStandardBottomSheetState(
                    initialValue = SheetValue.PartiallyExpanded,
                    skipHiddenState = true,
                ),
        )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_nearby_title)) },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        modifier = Modifier.testTag(TestTagRoot),
    ) { padding ->
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = SheetPeekHeight,
            sheetContent = {
                NearbyStopsList(
                    rows = nearbyRows,
                    distanceFormatter = distanceFormatter,
                    onRowClicked = onPinClicked,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = SheetMaxHeight)
                            .testTag(TestTagNearbyList),
                )
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) { innerPadding ->
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
            ) {
                // Underlying map — always present. The variant decides whether overlays show.
                val camera = uiState.cameraOrCbd()
                map.Render(
                    camera = camera,
                    userLocation = userLocation,
                    userBearing = userBearing,
                    pins = filteredPins,
                    isDark = isDark,
                    onCameraIdle = onCameraIdle,
                    onCameraMoveStarted = onCameraMoveStarted,
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

                // Follow-me FAB — only useful when there's a user location to centre on. Sits
                // above the bottom sheet's peek surface so it doesn't disappear behind the
                // sheet header.
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
 * Sheet content for the nearby-stops list (issue #80). A persistent peek-and-expand sheet — the
 * peek shows a header + first row, drag-up reveals the full sortable list. Each row's tap fans
 * out through [onRowClicked] which is wired to the same `onPinClicked` the map pins use, so a
 * row tap and a pin tap end up at the same `StopBottomSheet` projection.
 *
 * The list is keyed by `(stop.id.value, stop.routeType.name)` because PTV's
 * `/stops/location` endpoint returns the same `stop_id` once per `route_type` it serves —
 * keying on stop id alone crashes the LazyColumn with `IllegalArgumentException: Key "X" was
 * already used` when an interchange stop (e.g. Box Hill, stop_id 4407 — Train + Bus) appears.
 * Each `(stop, route-type)` pair is a legitimately distinct row that the user expects to see, so
 * we don't dedupe — the composite key matches the convention `:feature:stop-detail` already uses
 * for grouped/run rows (`group-${routeId}-${directionId}` / `${routeId}-${directionId}-${runRef}`).
 */
@Composable
private fun NearbyStopsList(
    rows: List<NearbyListRow>,
    distanceFormatter: DistanceFormatter,
    onRowClicked: (Stop) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Sheet header — sits in the peek surface so a glance at the collapsed sheet conveys
        // "drag up for the list".
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag(TestTagNearbyListHeader),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.feature_nearby_list_header),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.feature_nearby_list_count, rows.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        if (rows.isEmpty()) {
            Text(
                text = stringResource(R.string.feature_nearby_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .testTag(TestTagNearbyListEmpty),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().testTag(TestTagNearbyListItems),
            ) {
                items(items = rows, key = { "${it.stop.id.value}-${it.stop.routeType.name}" }) { row ->
                    NearbyStopRow(
                        row = row,
                        distanceFormatter = distanceFormatter,
                        onClick = { onRowClicked(row.stop) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * One row in the nearby-stops list. Renders mode glyph + name + suburb + distance subtext. The
 * row is clickable; tapping it calls [onClick] which the parent wires to `onPinClicked` so the
 * row-tap UX is identical to a map-pin tap (same bottom-sheet projection).
 */
@Composable
private fun NearbyStopRow(
    row: NearbyListRow,
    distanceFormatter: DistanceFormatter,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(nearbyListRowTestTag(row.stop.id.value, row.stop.routeType)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.stop.routeType.glyph(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.stop.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = row.stop.suburb,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.distanceMetres != null) {
            Text(
                text = distanceFormatter.format(row.distanceMetres),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
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
        Text(
            // `Route.displayLabel` (issue #88) picks `route_number` for trams/buses and
            // `route_name` for trains/V-Line, falling back to "#<id>" when both are blank.
            text = route.displayLabel,
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
        val distanceFormatter: DistanceFormatter,
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
 * the on-screen pins consistent with the chip state at all times — both the map pins and the
 * bottom-sheet list (issue #80) read this same filtered projection so they never disagree.
 *
 * The `filter` invariant (always non-empty, see [DEFAULT_FILTER]) means the empty-set short-circuit
 * the previous version had is unreachable in practice — keeping it defensively is cheap.
 */
internal fun List<Stop>.filteredBy(filter: Set<RouteType>): List<Stop> =
    if (filter.isEmpty()) this else filter { it.routeType in filter }

/**
 * Project a list of [Stop]s into a sorted [NearbyListRow] list. When [from] is `null` (no
 * permission, or the location provider hasn't returned a fix yet) the rows preserve repository
 * order with `distanceMetres = null` — the screen renders without a distance subtext rather
 * than an awkward "??". When [from] is set, rows sort ascending by haversine distance.
 *
 * Internal so the unit test can call it directly without booting Compose.
 */
internal fun List<Stop>.toRows(from: Coordinates?): List<NearbyListRow> {
    if (from == null) return map { NearbyListRow(it, distanceMetres = null) }
    return map { stop ->
        NearbyListRow(
            stop = stop,
            distanceMetres = from.distanceTo(Coordinates(stop.latitude, stop.longitude)),
        )
    }.sortedBy { it.distanceMetres ?: Double.MAX_VALUE }
}

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
internal const val TestTagNearbyList: String = "nearby-list"
internal const val TestTagNearbyListHeader: String = "nearby-list-header"
internal const val TestTagNearbyListItems: String = "nearby-list-items"
internal const val TestTagNearbyListEmpty: String = "nearby-list-empty"

internal fun filterChipTestTag(routeType: RouteType): String = "nearby-filter-chip-${routeType.name.lowercase()}"

internal fun nearbyListRowTestTag(
    stopId: Int,
    routeType: RouteType,
): String = "nearby-list-row-$stopId-${routeType.name.lowercase()}"

/**
 * Peek height — small enough that the user sees the map and the chip row, but tall enough that
 * the sheet header + drag handle land above the bottom-nav (~80 dp). The sheet's content uses
 * the standard M3 drag handle (added by `BottomSheetScaffold`), so the visible "peek" is the
 * handle + a single header row.
 */
private val SheetPeekHeight = 96.dp

/**
 * Sheet content cap so the list can scroll inside the sheet rather than push the sheet to
 * full-screen. Generous enough that ~5 rows are visible on a typical phone — the user can
 * scroll inside or drag the sheet up further (M3 expands to fill).
 */
private val SheetMaxHeight = 480.dp
