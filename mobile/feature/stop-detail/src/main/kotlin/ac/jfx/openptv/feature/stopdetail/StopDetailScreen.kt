package ac.jfx.openptv.feature.stopdetail

import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.PlatformNumber
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.feature.stopdetail.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
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
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Stateful entry point. Navigation 3 hands the destination key in via [stopId] and [routeType];
 * we forward them to the [StopDetailViewModel.Factory] assisted-injection seam so the ViewModel
 * sees the args without round-tripping through `SavedStateHandle` (Navigation 3 alpha doesn't
 * wire NavKey fields into the saved state automatically the way Navigation 2 does).
 *
 * The `key` argument on `hiltViewModel` is part of the ViewModel store key so navigating from one
 * stop to a different one allocates a fresh ViewModel instead of reusing the previous one with a
 * stale `stopId`. Owns the lifecycle-aware polling driver: `repeatOnLifecycle(RESUMED)` re-launches
 * the collection job each time the screen comes back to the foreground.
 */
@Composable
fun StopDetailRoute(
    stopId: StopId,
    routeType: RouteType,
    onBack: () -> Unit,
    onDepartureClicked: (Departure) -> Unit = {},
    viewModel: StopDetailViewModel =
        hiltViewModel<StopDetailViewModel, StopDetailViewModel.Factory>(
            key = "stop-detail-${stopId.value}-${routeType.name}",
        ) { factory ->
            factory.create(stopId = stopId.value, routeTypeCode = routeType.toCode())
        },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Each Resume kicks off a fresh collection; pause cancels the block, which cancels
            // the launched job by structured concurrency. `startObserving` then cancels-and-
            // relaunches inside the ViewModel scope so the collector lifetime is its own.
            viewModel.startObserving()
        }
        // Reached when the lifecycle owner is destroyed. Stop the polling job so the ViewModel
        // doesn't keep ticking against a dead screen.
        viewModel.stopObserving()
    }

    StopDetailScreenContent(
        uiState = uiState,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onRetry = viewModel::retryHeader,
        onDepartureClicked = onDepartureClicked,
        timeFormatter = viewModel.timeFormatter,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StopDetailScreenContent(
    uiState: StopDetailUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onDepartureClicked: (Departure) -> Unit,
    timeFormatter: RelativeTimeFormatter,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val disruptionMessage = stringResource(R.string.feature_stop_detail_disruption_snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text =
                            when (val header = uiState.header) {
                                is HeaderState.Loaded -> header.detail.stop.name
                                else -> stringResource(R.string.feature_stop_detail_title)
                            },
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        // Material icons aren't pulled into this module to keep the dep surface
                        // tight; a glyph stand-in keeps the back affordance.
                        Text(text = "‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                actions = {
                    // Disabled favourite icon — Phase 04 wires it up. Modelled here so the layout
                    // doesn't shift when the feature lands.
                    IconButton(
                        onClick = { /* disabled */ },
                        enabled = false,
                        modifier = Modifier.semantics {
                            contentDescription =
                                "Favourite (coming in Phase 4)"
                        },
                    ) {
                        Text(text = "☆", style = MaterialTheme.typography.titleLarge)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    HeaderSection(
                        state = uiState.header,
                        onRetry = onRetry,
                    )
                    HorizontalDivider()
                }

                item(key = "as-of") {
                    AsOfRow(asOf = uiState.asOf)
                }

                when (val list = uiState.departures) {
                    DeparturesState.Loading -> {
                        item(key = "loading") {
                            LoadingSkeleton(modifier = Modifier.testTag(TestTagLoading))
                        }
                    }
                    DeparturesState.Empty -> {
                        item(key = "empty") {
                            EmptyState(modifier = Modifier.testTag(TestTagEmpty))
                        }
                    }
                    is DeparturesState.Error -> {
                        item(key = "error") {
                            ErrorState(
                                reason = list.reason,
                                onRetry = onRefresh,
                                modifier = Modifier.testTag(TestTagError),
                            )
                        }
                    }
                    is DeparturesState.Loaded -> {
                        list.groups.forEach { group ->
                            item(key = "group-${group.key.routeId}-${group.key.directionId}") {
                                GroupHeader(group = group)
                            }
                            items(
                                items = group.departures,
                                key = { dep -> "${group.key.routeId}-${group.key.directionId}-${dep.runRef.value}" },
                            ) { dep ->
                                DepartureRow(
                                    departure = dep,
                                    timeFormatter = timeFormatter,
                                    routeBadge = group.headerLabel,
                                    onDisruptionClicked = {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(disruptionMessage)
                                        }
                                    },
                                    onClicked = { onDepartureClicked(dep) },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderSection(
    state: HeaderState,
    onRetry: () -> Unit,
) {
    when (state) {
        HeaderState.Loading -> {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.feature_stop_detail_loading),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        is HeaderState.Loaded -> {
            StopHeader(stop = state.detail.stop, routes = state.detail.servingRoutes)
        }
        is HeaderState.Error -> {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    text = state.reason,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRetry, modifier = Modifier.testTag(TestTagHeaderRetry)) {
                    Text(stringResource(R.string.feature_stop_detail_retry))
                }
            }
        }
    }
}

@Composable
private fun StopHeader(
    stop: Stop,
    routes: List<Route>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stop.routeType.label(),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(end = 8.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stop.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (stop.suburb.isNotBlank()) {
                    Text(
                        text = stop.suburb,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (routes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.feature_stop_detail_serving_routes),
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            // A Row instead of FlowRow keeps the dep surface tight. For long route lists this
            // will scroll horizontally via LazyRow in a follow-up — Flinders has dozens of routes.
            Row(
                modifier = Modifier.fillMaxWidth().testTag(TestTagRouteChips),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                routes.take(MAX_INLINE_ROUTE_CHIPS).forEach { route ->
                    AssistChip(
                        onClick = { /* route detail lands in Phase 06 */ },
                        label = { Text(route.number.ifBlank { route.name }) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: Group) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(TestTagGroupHeader),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = group.headerLabel,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun DepartureRow(
    departure: Departure,
    timeFormatter: RelativeTimeFormatter,
    routeBadge: String,
    onDisruptionClicked: () -> Unit,
    onClicked: () -> Unit,
) {
    val relative = timeFormatter.format(
        scheduled = departure.scheduledDepartureUtc,
        estimated = departure.estimatedDepartureUtc,
    )
    val scheduled = departure.scheduledDepartureUtc.formatTimeOfDay()
    val platformClause =
        departure.platform?.let { platform ->
            stringResource(R.string.feature_stop_detail_row_platform_clause, platform.value)
        }.orEmpty()

    val routeDescriptor = routeBadge.removePrefix("Route ").substringBefore(" ·")
    val talkback =
        stringResource(
            R.string.feature_stop_detail_row_content_description,
            routeDescriptor,
            departure.direction.name,
            relative.toSpokenForm(),
            platformClause,
        )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = talkback }
                .testTag(TestTagDepartureRow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Route badge — borders/colours land with the designsystem polish; this is the v1
            // text-only badge so the row composes without a theme dependency leak.
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(
                    text = routeBadge.routeShortCode(),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = departure.direction.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.feature_stop_detail_scheduled, scheduled),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = relative,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val delayMinutes = departure.delayMinutes()
                if (delayMinutes != null && delayMinutes >= 1) {
                    Text(
                        text = stringResource(R.string.feature_stop_detail_delay_indicator, delayMinutes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text =
                    departure.platform?.let {
                        stringResource(R.string.feature_stop_detail_platform, it.value)
                    } ?: stringResource(R.string.feature_stop_detail_no_platform),
                style = MaterialTheme.typography.bodySmall,
            )
            if (departure.flags.hasDisruption) {
                Spacer(modifier = Modifier.width(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onDisruptionClicked,
                    modifier = Modifier.testTag(TestTagDisruptionFlag),
                ) {
                    Text(stringResource(R.string.feature_stop_detail_disruption))
                }
            }
        }
    }
}

@Composable
private fun AsOfRow(asOf: Instant?) {
    if (asOf == null) {
        Spacer(modifier = Modifier.height(8.dp))
        return
    }
    Text(
        text = stringResource(R.string.feature_stop_detail_as_of, asOf.formatTimeOfDay()),
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
            text = stringResource(R.string.feature_stop_detail_empty),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.feature_stop_detail_empty_subtitle),
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
            text = stringResource(R.string.feature_stop_detail_error),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onRetry, modifier = Modifier.testTag(TestTagDeparturesRetry)) {
            Text(stringResource(R.string.feature_stop_detail_retry))
        }
    }
}

private fun Instant.formatTimeOfDay(): String {
    val local = toLocalDateTime(TimeZone.currentSystemDefault())
    return "%02d:%02d".format(local.hour, local.minute)
}

private fun Departure.delayMinutes(): Long? {
    val estimated = estimatedDepartureUtc ?: return null
    val delta = estimated - scheduledDepartureUtc
    return delta.inWholeMinutes.takeIf { it != 0L }
}

/**
 * "Route 19 · North Coburg" → "19". Cheap split — keeps the badge composable free of route
 * lookup gymnastics now that the [Group] has the formatted header label baked in.
 */
private fun String.routeShortCode(): String =
    removePrefix("Route ").substringBefore(" ·")

/**
 * Tighten the relative-time copy slightly for TalkBack: "in 3 min" → "in 3 minutes" sounds more
 * natural when spoken. Keep the visible form short and the spoken form long.
 */
private fun String.toSpokenForm(): String =
    when {
        this == "now" -> "right now"
        this == "departed" -> "having already departed"
        this == "scheduled" -> "as scheduled"
        endsWith(" min") -> replace(" min", " minutes")
        endsWith(" h") -> replace(" h", " hours")
        contains(" h ") -> replace(" h ", " hours ").replace(" min", " minutes")
        endsWith(" day") || endsWith(" days") -> this
        else -> this
    }

@Composable
private fun RouteType.label(): String =
    when (this) {
        RouteType.Train -> stringResource(R.string.feature_stop_detail_route_type_train)
        RouteType.Tram -> stringResource(R.string.feature_stop_detail_route_type_tram)
        RouteType.Bus -> stringResource(R.string.feature_stop_detail_route_type_bus)
        RouteType.VLine -> stringResource(R.string.feature_stop_detail_route_type_vline)
        RouteType.NightBus -> stringResource(R.string.feature_stop_detail_route_type_night_bus)
        RouteType.Unknown -> stringResource(R.string.feature_stop_detail_route_type_unknown)
    }

private const val MAX_INLINE_ROUTE_CHIPS = 6
private const val SKELETON_ROWS = 5

internal const val TestTagRoot: String = "stop-detail-root"
internal const val TestTagRouteChips: String = "stop-detail-route-chips"
internal const val TestTagGroupHeader: String = "stop-detail-group-header"
internal const val TestTagDepartureRow: String = "stop-detail-departure-row"
internal const val TestTagDisruptionFlag: String = "stop-detail-disruption-flag"
internal const val TestTagAsOf: String = "stop-detail-as-of"
internal const val TestTagLoading: String = "stop-detail-loading"
internal const val TestTagEmpty: String = "stop-detail-empty"
internal const val TestTagError: String = "stop-detail-error"
internal const val TestTagHeaderRetry: String = "stop-detail-header-retry"
internal const val TestTagDeparturesRetry: String = "stop-detail-departures-retry"

// Visible only to silence Detekt's unused-private rule on the colour helper below.
@Suppress("unused")
private val touchpoint: Color = Color.Transparent
