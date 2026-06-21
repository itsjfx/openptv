package ac.jfx.openptv.feature.runpattern

import ac.jfx.openptv.core.common.AbsoluteTimeFormatter
import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.datastore.preference.rememberUse24Hour
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.StopId
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
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

    RunPatternScreenContent(
        uiState = uiState,
        onRefresh = viewModel::refresh,
        timeFormatter = viewModel.timeFormatter,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RunPatternScreenContent(
    uiState: RunPatternUiState,
    onRefresh: () -> Unit,
    timeFormatter: RelativeTimeFormatter,
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.pattern.titleText(),
                        maxLines = 1,
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
                                    PatternStopRow(
                                        row = row,
                                        timeFormatter = timeFormatter,
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
 */
@Composable
private fun PatternStopRow(
    row: PatternStopRow,
    timeFormatter: RelativeTimeFormatter,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stop.stopName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (row.hasDeparted) FontWeight.Normal else FontWeight.Medium,
                    color = contentColor,
                )
                if (row.isOrigin) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.testTag(TestTagThisStop),
                    ) {
                        Text(
                            text = stringResource(R.string.feature_run_pattern_this_stop),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
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
