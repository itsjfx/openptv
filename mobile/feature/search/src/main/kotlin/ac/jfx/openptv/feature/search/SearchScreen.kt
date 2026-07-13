package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.designsystem.ScreenHeading
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.feature.search.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point wired from the navigation graph. Hoists [SearchViewModel] off Hilt and
 * drops the rest into the stateless [SearchScreenContent] so previews and tests don't need DI.
 */
@Composable
fun SearchScreen(
    onStopSelected: (Stop) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val routeTypeFilter by viewModel.routeTypeFilter.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreenContent(
        query = query,
        routeTypeFilter = routeTypeFilter,
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onRouteTypeFilterToggled = viewModel::onRouteTypeFilterToggled,
        onStopSelected = onStopSelected,
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreenContent(
    query: String,
    routeTypeFilter: Set<RouteType>,
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onRouteTypeFilterToggled: (RouteType) -> Unit,
    onStopSelected: (Stop) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            // Small TopAppBar — gear lives in the compact icon row under the status bar, hero
            // heading is rendered in the body via [ScreenHeading] (ReadYou layout, issue #111
            // review).
            TopAppBar(
                title = {},
                navigationIcon = {
                    SettingsGearButton(onClick = onOpenSettings)
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            ScreenHeading(text = stringResource(R.string.feature_search_title))
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TestTagQueryField),
                    label = { Text(stringResource(R.string.feature_search_field_label)) },
                    singleLine = true,
                    keyboardOptions =
                        androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                )

                RouteTypeFilterRow(
                    selected = routeTypeFilter,
                    onToggle = onRouteTypeFilterToggled,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag(TestTagFilterRow),
                )

                when (val state = uiState) {
                    SearchUiState.Idle ->
                        CenteredMessage(
                            text = stringResource(R.string.feature_search_idle_hint),
                        )
                    SearchUiState.Loading -> CenteredLoader()
                    SearchUiState.Empty ->
                        CenteredMessage(
                            text = stringResource(R.string.feature_search_empty),
                        )
                    is SearchUiState.Results ->
                        StopList(
                            stops = state.stops,
                            // Phase 02 surfaced a snackbar here as a placeholder navigation target.
                            // Phase 03 wires the real destination via the `onStopSelected` hoist —
                            // the app composition root pushes `AppNavKey.StopDetail`.
                            onStopSelected = onStopSelected,
                        )
                    is SearchUiState.Error -> CenteredMessage(text = state.reason)
                }
            }
        }

        LaunchedEffect(uiState) {
            // Hook for future analytics / haptics on state transitions; intentionally empty.
        }
    }
}

@Composable
private fun StopList(
    stops: List<Stop>,
    onStopSelected: (Stop) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(TestTagResults)) {
        items(stops, key = { it.id.value to it.routeType }) { stop ->
            StopRow(stop = stop, onClick = { onStopSelected(stop) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun StopRow(
    stop: Stop,
    onClick: () -> Unit,
) {
    val mode = stop.routeType.label()
    val talkback =
        stringResource(
            R.string.feature_search_row_content_description,
            stop.name,
            stop.suburb.ifBlank { stringResource(R.string.feature_search_unknown_suburb) },
            mode,
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .semantics { contentDescription = talkback },
    ) {
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = mode,
                style = MaterialTheme.typography.labelSmall,
                modifier =
                    Modifier
                        .padding(end = 12.dp),
            )
            Column {
                Text(stop.name, style = MaterialTheme.typography.bodyLarge)
                if (stop.suburb.isNotBlank()) {
                    Text(stop.suburb, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(top = 4.dp))
        androidx.compose.material3.TextButton(onClick = onClick) {
            Text(stringResource(R.string.feature_search_row_open))
        }
    }
}

@Composable
private fun CenteredLoader() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Horizontal row of [FilterChip]s, one per visible [RouteType] — a per-feature mirror of the
 * Nearby map's chip strip (issue #213; duplicated rather than promoted to `:core:designsystem`
 * because designsystem doesn't depend on `:core:model` and this composable isn't worth adding
 * that edge for). Unlike Nearby's filter, empty selection is allowed and means "all modes".
 * The row scrolls horizontally so the five chips fit on a 360-dp width device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteTypeFilterRow(
    selected: Set<RouteType>,
    onToggle: (RouteType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowDescription = stringResource(R.string.feature_search_filter_row_description)
    Row(
        modifier =
            modifier
                .horizontalScroll(rememberScrollState())
                .semantics { contentDescription = rowDescription },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        filterTypes.forEach { routeType ->
            FilterChip(
                selected = selected.contains(routeType),
                onClick = { onToggle(routeType) },
                label = { Text(routeType.label()) },
                leadingIcon = { Text(routeType.glyph()) },
                colors = FilterChipDefaults.filterChipColors(),
                modifier = Modifier.testTag(filterChipTestTag(routeType)),
            )
        }
    }
}

/** The user-facing modes, chip order matching Nearby's strip. [RouteType.Unknown] is a runtime fallback, never a chip. */
private val filterTypes: List<RouteType> =
    listOf(RouteType.Train, RouteType.Tram, RouteType.Bus, RouteType.VLine, RouteType.NightBus)

@Composable
private fun RouteType.label(): String =
    when (this) {
        RouteType.Train -> stringResource(R.string.feature_search_route_type_train)
        RouteType.Tram -> stringResource(R.string.feature_search_route_type_tram)
        RouteType.Bus -> stringResource(R.string.feature_search_route_type_bus)
        RouteType.VLine -> stringResource(R.string.feature_search_route_type_vline)
        RouteType.NightBus -> stringResource(R.string.feature_search_route_type_night_bus)
        RouteType.Unknown -> stringResource(R.string.feature_search_route_type_unknown)
    }

/** Same glyph set as the Nearby chip strip so the modes read identically across surfaces. */
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
 * Top-left settings gear — see the equivalent doc on `:feature:favourites`. Pushed as a
 * destination by the app composition root so system back returns to whichever main screen
 * launched it (issue #111).
 */
@Composable
private fun SettingsGearButton(onClick: () -> Unit) {
    val description = stringResource(R.string.feature_search_open_settings)
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .testTag(TestTagSettingsGear)
                .semantics { contentDescription = description },
    ) {
        Text(
            text = "⚙",
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

internal const val TestTagQueryField: String = "search-query-field"
internal const val TestTagResults: String = "search-results-list"
internal const val TestTagSettingsGear: String = "search-settings-gear"
internal const val TestTagFilterRow: String = "search-filter-row"

internal fun filterChipTestTag(routeType: RouteType): String = "search-filter-chip-${routeType.name.lowercase()}"
