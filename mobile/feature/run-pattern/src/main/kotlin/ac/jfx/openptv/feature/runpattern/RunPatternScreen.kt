package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.AbsoluteTimeFormatter
import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.datastore.preference.rememberUse24Hour
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/**
 * Stateful entry point for the run-pattern screen (issue #132). Navigation 3 hands the
 * destination key in via [runRef] / [routeType] / [fromStopId]; we forward them to the
 * [RunPatternViewModel.Factory] assisted-injection seam — same template as `StopDetailRoute`.
 *
 * Owns the lifecycle-aware polling driver: `repeatOnLifecycle(RESUMED)` re-launches the
 * collection job each time the screen comes back to the foreground, so polling pauses while
 * the screen is backgrounded.
 */
@Composable
fun RunPatternRoute(
    runRef: RunRef,
    routeType: RouteType,
    fromStopId: StopId? = null,
    onStopClicked: (StopId) -> Unit = {},
    viewModel: RunPatternViewModel =
        hiltViewModel<RunPatternViewModel, RunPatternViewModel.Factory>(
            // Part of the ViewModel store key so navigating to a different run allocates a fresh
            // ViewModel instead of reusing the previous one with a stale runRef.
            key = "run-pattern-${runRef.value}-${routeType.name}",
        ) { factory ->
            factory.create(
                runRef = runRef.value,
                routeTypeCode = routeType.toCode(),
                fromStopId = fromStopId?.value ?: RunPatternViewModel.NO_FROM_STOP,
            )
        },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.startObserving()
        }
        viewModel.stopObserving()
    }

    // Alight-alert permission plumbing (issue #201). Both prompts are *contextual*: the
    // notification one fires only when the user actually arms an alert (API 33+), the location
    // one only when the armed run has no real-time signal (trams) so the GPS fallback matters.
    // Neither grant is a precondition — arming proceeds regardless, and a snackbar explains
    // what a denial degrades.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingAlightArm by remember { mutableStateOf<StopId?>(null) }
    val notificationsDeniedMessage = stringResource(R.string.feature_run_pattern_alight_notifications_denied)
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                scope.launch { snackbarHostState.showSnackbar(notificationsDeniedMessage) }
            }
            pendingAlightArm?.let(viewModel::armAlightAlert)
            pendingAlightArm = null
        }
    val onArmAlight: (StopId) -> Unit = { stopId ->
        if (needsNotificationPermission(context)) {
            pendingAlightArm = stopId
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.armAlightAlert(stopId)
        }
    }

    val scheduleOnlyMessage = stringResource(R.string.feature_run_pattern_alight_schedule_only)
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.none { it }) {
                scope.launch { snackbarHostState.showSnackbar(scheduleOnlyMessage) }
            }
            viewModel.onAlightLocationPromptHandled()
        }
    LaunchedEffect(uiState.alightLocationPromptNeeded) {
        if (uiState.alightLocationPromptNeeded) {
            if (hasLocationPermission(context)) {
                viewModel.onAlightLocationPromptHandled()
            } else {
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                    ),
                )
            }
        }
    }

    RunPatternScreenContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        onStopClicked = onStopClicked,
        timeFormatter = viewModel.timeFormatter,
        onFollowClicked = viewModel::followTrip,
        onUnfollowClicked = viewModel::unfollowTrip,
        onConfirmReplaceFollow = viewModel::confirmReplaceFollow,
        onDismissReplaceFollow = viewModel::dismissReplaceFollow,
        onArmAlight = onArmAlight,
        onDisarmAlight = viewModel::disarmAlightAlert,
        snackbarHostState = snackbarHostState,
    )
}

/** POST_NOTIFICATIONS is runtime-requestable only from API 33; below that it's implicit. */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

private fun hasLocationPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongParameterList")
internal fun RunPatternScreenContent(
    uiState: RunPatternUiState,
    onRefresh: () -> Unit,
    onStopClicked: (StopId) -> Unit,
    timeFormatter: RelativeTimeFormatter,
    onFollowClicked: () -> Unit = {},
    onUnfollowClicked: () -> Unit = {},
    onConfirmReplaceFollow: () -> Unit = {},
    onDismissReplaceFollow: () -> Unit = {},
    onArmAlight: (StopId) -> Unit = {},
    onDisarmAlight: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val listState = rememberLazyListState()

    // Pinned route map (issue #187). Always shown above the timeline so the spatial overview stays
    // in view while the stop list scrolls beneath it; the show/hide toggle still lets the user
    // reclaim the vertical space. `rememberSaveable` so a rotate doesn't re-expand a map the user
    // collapsed.
    var mapExpanded by rememberSaveable { mutableStateOf(true) }
    val loaded = uiState.pattern as? PatternState.Loaded
    val mapData = loaded?.mapData?.takeIf { it.hasGeometry }

    // One-shot auto-scroll to the first upcoming stop (issue #132: "full pattern, scrolled to
    // the next upcoming stop on first render"). `rememberSaveable` so a configuration change
    // doesn't yank the user back if they've scrolled elsewhere.
    var hasAutoScrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(loaded != null) {
        if (!hasAutoScrolled && loaded != null) {
            // The map is a pinned header outside the list now, so the as-of row is the only leading
            // list item and the first stop sits one slot below it. Only scroll when some stops have
            // already been served, so a run that hasn't started yet stays at the top.
            if (loaded.firstUpcomingIndex > 0) {
                listState.scrollToItem(loaded.firstUpcomingIndex + 1)
            }
            hasAutoScrolled = true
        }
    }

    // Replace-confirmation for the followed trip (issue #200): shown when the user taps Follow
    // (or arms an alight alert) while a *different* run is already followed.
    val replaceCandidate = uiState.followReplaceCandidate
    if (replaceCandidate != null) {
        FollowReplaceDialog(
            candidate = replaceCandidate,
            onConfirm = onConfirmReplaceFollow,
            onDismiss = onDismissReplaceFollow,
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.pattern.titleText(),
                        maxLines = 1,
                    )
                },
                actions = {
                    FollowAction(
                        uiState = uiState,
                        onFollowClicked = onFollowClicked,
                        onUnfollowClicked = onUnfollowClicked,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = uiState.isRefreshing,
            onRefresh = onRefresh,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .testTag(TestTagRoot),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // The map is a pinned header (issue #187): it stays put while the timeline scrolls
                // beneath it. Mounted only while expanded so the GL surface isn't paid for when the
                // user has hidden it.
                if (mapData != null) {
                    MapSection(
                        mapData = mapData,
                        expanded = mapExpanded,
                        onToggle = { mapExpanded = !mapExpanded },
                    )
                }
                // The timeline fills the rest. Every state renders inside the LazyColumn (same shape
                // as stop-detail) so the pull-to-refresh nested-scroll gesture works from the
                // loading / empty / error states too, not just the loaded timeline.
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                ) {
                    when (val pattern = uiState.pattern) {
                        PatternState.Loading -> {
                            item(key = "loading") {
                                LoadingSkeleton(modifier = Modifier.testTag(TestTagLoading))
                            }
                        }
                        PatternState.Empty -> {
                            item(key = "empty") {
                                EmptyState(modifier = Modifier.testTag(TestTagEmpty))
                            }
                        }
                        is PatternState.Error -> {
                            item(key = "error") {
                                ErrorState(
                                    reason = pattern.reason,
                                    onRetry = onRefresh,
                                    modifier = Modifier.testTag(TestTagError),
                                )
                            }
                        }
                        is PatternState.Loaded -> {
                            item(key = "as-of") {
                                AsOfRow(asOf = uiState.asOf)
                            }
                            // A stop can legitimately repeat within one run (city-loop services), so
                            // the row key includes the position in the pattern, which is stable for
                            // a given run.
                            pattern.stops.forEachIndexed { index, row ->
                                item(key = "stop-$index-${row.stop.stopId.value}") {
                                    val isAlight = row.stop.stopId == uiState.alightStopId
                                    PatternStopRow(
                                        row = row,
                                        timeFormatter = timeFormatter,
                                        onClick = { onStopClicked(row.stop.stopId) },
                                        isAlight = isAlight,
                                        onToggleAlight = {
                                            if (isAlight) onDisarmAlight() else onArmAlight(row.stop.stopId)
                                        },
                                    )
                                    if (index != pattern.stops.lastIndex) {
                                        HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Follow/Unfollow top-bar action (issue #200). Only once the pattern is Loaded — the stored
 * trip is built from the fetched terminus arrival + labels, so following a skeleton would
 * persist garbage.
 */
@Composable
private fun FollowAction(
    uiState: RunPatternUiState,
    onFollowClicked: () -> Unit,
    onUnfollowClicked: () -> Unit,
) {
    if (uiState.pattern !is PatternState.Loaded) return
    TextButton(
        onClick = if (uiState.isFollowingThisRun) onUnfollowClicked else onFollowClicked,
        modifier = Modifier.testTag(TestTagFollowButton),
    ) {
        Text(
            text =
                stringResource(
                    if (uiState.isFollowingThisRun) {
                        R.string.feature_run_pattern_unfollow
                    } else {
                        R.string.feature_run_pattern_follow
                    },
                ),
        )
    }
}

/** Confirm replacing the currently followed [candidate] trip with this run (issue #200). */
@Composable
private fun FollowReplaceDialog(
    candidate: FollowedTrip,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTagFollowReplaceDialog),
        title = { Text(stringResource(R.string.feature_run_pattern_follow_replace_title)) },
        text = {
            Text(
                stringResource(
                    R.string.feature_run_pattern_follow_replace_body,
                    candidate.displayText(),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTagFollowReplaceConfirm),
            ) {
                Text(stringResource(R.string.feature_run_pattern_follow_replace_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.feature_run_pattern_follow_replace_cancel))
            }
        },
    )
}

/**
 * Human-readable name for a followed trip, used by the replace-confirmation dialog. Same
 * route/destination collapse rule as [titleText] so "Lilydale to Lilydale" reads as "Lilydale".
 */
@Composable
private fun FollowedTrip.displayText(): String {
    val route = routeLabel
    return when {
        route == null -> destinationName
        route.equals(destinationName, ignoreCase = true) -> route
        else -> stringResource(R.string.feature_run_pattern_title_format, route, destinationName)
    }
}

@Composable
private fun PatternState.titleText(): String =
    when (this) {
        is PatternState.Loaded -> {
            val destination = directionName.ifBlank { stringResource(R.string.feature_run_pattern_title) }
            val route = routeLabel
            when {
                route == null -> destination
                // Loop / terminus runs where the line and its destination share a name (e.g. the
                // Lilydale line terminating at Lilydale) read as "Lilydale to Lilydale" — collapse
                // the redundant half to just the name; keep "X to Y" when they genuinely differ.
                route.equals(destination, ignoreCase = true) -> route
                else -> stringResource(R.string.feature_run_pattern_title_format, route, destination)
            }
        }
        else -> stringResource(R.string.feature_run_pattern_title)
    }

/**
 * One timeline row: a rail glyph column (filled dot for upcoming stops, hollow for already-served
 * ones — Unicode glyphs keep this module off the Material Icons artifact, same trade as the rest
 * of the codebase), the stop name + scheduled time, and the live relative phrase on the trailing
 * edge. Past stops dim to `onSurfaceVariant`; the tapped-through stop gets a "This stop" chip.
 *
 * Upcoming rows also carry the alight-alert bell (issue #201) on the far trailing edge: dimmed
 * while unarmed ("alert me before this stop"), full-strength plus a "Getting off here" chip once
 * armed; tapping again disarms. Departed rows drop the bell — you can't get off at a stop the
 * vehicle already served.
 */
@Composable
@Suppress("LongMethod")
private fun PatternStopRow(
    row: PatternStopRow,
    timeFormatter: RelativeTimeFormatter,
    onClick: () -> Unit,
    isAlight: Boolean = false,
    onToggleAlight: () -> Unit = {},
) {
    val stop = row.stop
    val relative =
        timeFormatter.format(
            scheduled = stop.scheduledDepartureUtc,
            estimated = stop.estimatedDepartureUtc,
        )
    val use24Hour = rememberUse24Hour()
    val scheduled = AbsoluteTimeFormatter.format(stop.scheduledDepartureUtc, use24Hour)
    val contentColor =
        if (row.hasDeparted) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.onSurface
        }
    val talkback =
        stringResource(
            R.string.feature_run_pattern_row_content_description,
            stop.stopName,
            relative.toSpokenForm(),
        )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = talkback }
                .testTag(if (row.hasDeparted) TestTagPastStopRow else TestTagStopRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (row.hasDeparted) "○" else "●",
            style = MaterialTheme.typography.titleMedium,
            color =
                if (row.hasDeparted) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stop.stopName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.hasDeparted) FontWeight.Normal else FontWeight.Medium,
                color = contentColor,
                // Names overflow *down*, bounded at 2 lines + ellipsis, rather than *out*
                // (CLAUDE.md UI conventions).
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (row.isOrigin) {
                // The "This stop" chip sits on its own line, left-aligned under the name, so a long
                // wrapped name never crowds it and it always reads as belonging to this stop.
                StopMarkerChip(
                    text = stringResource(R.string.feature_run_pattern_this_stop),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    testTag = TestTagThisStop,
                )
            }
            if (isAlight) {
                // The armed alight marker (issue #201) — same chip shape as "This stop", on the
                // tertiary container so the two markers read as different things at a glance.
                StopMarkerChip(
                    text = stringResource(R.string.feature_run_pattern_alight_here),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    testTag = TestTagAlightChip,
                )
            }
            Text(
                text = stringResource(R.string.feature_run_pattern_scheduled, scheduled),
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
            )
            val platform = stop.platform
            if (platform != null && !row.hasDeparted) {
                Text(
                    text = stringResource(R.string.feature_run_pattern_platform, platform.value),
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    modifier = Modifier.testTag(TestTagPlatform),
                )
            }
        }
        RelativeTimeColumn(row = row, relative = relative, contentColor = contentColor)
        if (!row.hasDeparted) {
            AlightBell(
                isAlight = isAlight,
                stopName = stop.stopName,
                onToggleAlight = onToggleAlight,
            )
        }
    }
}

/** The trailing live-time column: the relative phrase plus the late/early delta when known. */
@Composable
private fun RelativeTimeColumn(
    row: PatternStopRow,
    relative: String,
    contentColor: Color,
) {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = relative,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (row.hasDeparted) FontWeight.Normal else FontWeight.SemiBold,
            color = contentColor,
        )
        val delayMinutes = row.delayMinutes()
        if (delayMinutes != null && !row.hasDeparted) {
            val absMinutes = kotlin.math.abs(delayMinutes).toInt()
            val pluralRes =
                if (delayMinutes > 0) {
                    R.plurals.feature_run_pattern_delay_late
                } else {
                    R.plurals.feature_run_pattern_delay_early
                }
            Text(
                text = pluralStringResource(pluralRes, absMinutes, absMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** The origin / alight marker chip under a stop's name — single line, never wraps. */
@Composable
private fun StopMarkerChip(
    text: String,
    color: Color,
    testTag: String,
) {
    Spacer(modifier = Modifier.height(4.dp))
    Surface(
        color = color,
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.testTag(testTag),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * The alight-alert bell (issue #201). An emoji glyph ignores tint (it renders in colour), so
 * armed/unarmed is signalled through alpha + the "Getting off here" chip instead. Same
 * no-Material-Icons trade as the rest of the codebase.
 */
@Composable
private fun AlightBell(
    isAlight: Boolean,
    stopName: String,
    onToggleAlight: () -> Unit,
) {
    val bellDescription =
        stringResource(
            if (isAlight) {
                R.string.feature_run_pattern_alight_disarm_content_description
            } else {
                R.string.feature_run_pattern_alight_arm_content_description
            },
            stopName,
        )
    TextButton(
        onClick = onToggleAlight,
        modifier =
            Modifier
                .testTag(if (isAlight) TestTagAlightArmedButton else TestTagAlightButton)
                .semantics { contentDescription = bellDescription },
    ) {
        Text(
            text = "🔔",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.alpha(if (isAlight) 1f else UNARMED_BELL_ALPHA),
        )
    }
}

/**
 * The collapsible route-map section (issue #187). A header row toggles the [RunPatternMap] open and
 * shut; the map is mounted only while expanded so the GL surface (and the tile fetch) isn't paid
 * for when collapsed. `isDark` is derived from the surface luminance — same heuristic the nearby
 * screen uses to pick the OpenFreeMap style variant.
 */
@Composable
private fun MapSection(
    mapData: RunPatternMapData,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < DARK_LUMINANCE_THRESHOLD
    Column(modifier = Modifier.fillMaxWidth().testTag(TestTagMap)) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(
                onClick = onToggle,
                modifier = Modifier.testTag(TestTagMapToggle),
            ) {
                Text(
                    text =
                        stringResource(
                            if (expanded) {
                                R.string.feature_run_pattern_map_hide
                            } else {
                                R.string.feature_run_pattern_map_show
                            },
                        ),
                )
            }
        }
        if (expanded) {
            val mapDescription = stringResource(R.string.feature_run_pattern_map_content_description)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .height(MAP_HEIGHT_DP.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .semantics { contentDescription = mapDescription },
            ) {
                RunPatternMap(
                    mapData = mapData,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AsOfRow(asOf: Instant?) {
    if (asOf == null) {
        Spacer(modifier = Modifier.height(8.dp))
        return
    }
    val use24Hour = rememberUse24Hour()
    Text(
        text = stringResource(R.string.feature_run_pattern_as_of, AbsoluteTimeFormatter.format(asOf, use24Hour)),
        style = MaterialTheme.typography.labelSmall,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TestTagAsOf),
    )
}

@Composable
private fun LoadingSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        repeat(SKELETON_ROWS) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(vertical = 4.dp),
            ) { /* empty box drawn by the surface */ }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feature_run_pattern_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.feature_run_pattern_empty_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(
    reason: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feature_run_pattern_error),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTagRetry)) {
            Text(stringResource(R.string.feature_run_pattern_retry))
        }
    }
}

private fun PatternStopRow.delayMinutes(): Long? {
    val estimated = stop.estimatedDepartureUtc ?: return null
    val delta = estimated - stop.scheduledDepartureUtc
    return delta.inWholeMinutes.takeIf { it != 0L }
}

/**
 * Tighten the relative-time copy slightly for TalkBack — same mapping as stop-detail's rows so
 * the two screens speak the same language.
 */
private fun String.toSpokenForm(): String =
    when {
        this == "now" -> "right now"
        this == "departed" -> "having already departed"
        this == "scheduled" -> "as scheduled"
        endsWith(" min") -> replace(" min", " minutes")
        endsWith(" h") -> replace(" h", " hours")
        contains(" h ") -> replace(" h ", " hours ").replace(" min", " minutes")
        else -> this
    }

private const val SKELETON_ROWS = 8

/** Unarmed bells sit back; the armed one (plus its chip) reads at full strength. */
private const val UNARMED_BELL_ALPHA = 0.4f

/** Surface luminance below this reads as a dark theme — pick the dark OpenFreeMap style. */
private const val DARK_LUMINANCE_THRESHOLD: Float = 0.5f

/** Fixed map height — tall enough to read a metro line, short enough to leave the timeline in view. */
private const val MAP_HEIGHT_DP: Int = 240

internal const val TestTagRoot: String = "run-pattern-root"
internal const val TestTagLoading: String = "run-pattern-loading"
internal const val TestTagEmpty: String = "run-pattern-empty"
internal const val TestTagError: String = "run-pattern-error"
internal const val TestTagRetry: String = "run-pattern-retry"
internal const val TestTagAsOf: String = "run-pattern-as-of"
internal const val TestTagStopRow: String = "run-pattern-stop-row"
internal const val TestTagPastStopRow: String = "run-pattern-past-stop-row"
internal const val TestTagThisStop: String = "run-pattern-this-stop"
internal const val TestTagPlatform: String = "run-pattern-platform"
internal const val TestTagMap: String = "run-pattern-map"
internal const val TestTagMapToggle: String = "run-pattern-map-toggle"
internal const val TestTagFollowButton: String = "run-pattern-follow-button"
internal const val TestTagAlightButton: String = "run-pattern-alight-button"
internal const val TestTagAlightArmedButton: String = "run-pattern-alight-armed-button"
internal const val TestTagAlightChip: String = "run-pattern-alight-chip"
internal const val TestTagFollowReplaceDialog: String = "run-pattern-follow-replace-dialog"
internal const val TestTagFollowReplaceConfirm: String = "run-pattern-follow-replace-confirm"
