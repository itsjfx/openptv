package ac.jfx.openptv.feature.search

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.feature.search.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch

/**
 * Stateful entry point wired from the navigation graph. Hoists [SearchViewModel] off Hilt and
 * drops the rest into the stateless [SearchScreenContent] so previews and tests don't need DI.
 */
@Composable
fun SearchScreen(
    onStopSelected: (Stop) -> Unit = {},
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SearchScreenContent(
        query = query,
        uiState = uiState,
        onQueryChanged = viewModel::onQueryChanged,
        onStopSelected = onStopSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SearchScreenContent(
    query: String,
    uiState: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onStopSelected: (Stop) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.feature_search_title)) })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .testTag(TestTagQueryField),
                label = { Text(stringResource(R.string.feature_search_field_label)) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Search,
                ),
            )

            when (val state = uiState) {
                SearchUiState.Idle -> CenteredMessage(
                    text = stringResource(R.string.feature_search_idle_hint),
                )
                SearchUiState.Loading -> CenteredLoader()
                SearchUiState.Empty -> CenteredMessage(
                    text = stringResource(R.string.feature_search_empty),
                )
                is SearchUiState.Results -> StopList(
                    stops = state.stops,
                    onStopSelected = { stop ->
                        onStopSelected(stop)
                        scope.launch {
                            snackbarHostState.showSnackbar(stop.name)
                        }
                    },
                )
                is SearchUiState.Error -> CenteredMessage(text = state.reason)
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
    val talkback = stringResource(
        R.string.feature_search_row_content_description,
        stop.name,
        stop.suburb.ifBlank { stringResource(R.string.feature_search_unknown_suburb) },
        mode,
    )
    Column(
        modifier = Modifier
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
                modifier = Modifier
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

@Composable
private fun RouteType.label(): String = when (this) {
    RouteType.Train -> stringResource(R.string.feature_search_route_type_train)
    RouteType.Tram -> stringResource(R.string.feature_search_route_type_tram)
    RouteType.Bus -> stringResource(R.string.feature_search_route_type_bus)
    RouteType.VLine -> stringResource(R.string.feature_search_route_type_vline)
    RouteType.NightBus -> stringResource(R.string.feature_search_route_type_night_bus)
    RouteType.Unknown -> stringResource(R.string.feature_search_route_type_unknown)
}

internal const val TestTagQueryField: String = "search-query-field"
internal const val TestTagResults: String = "search-results-list"
