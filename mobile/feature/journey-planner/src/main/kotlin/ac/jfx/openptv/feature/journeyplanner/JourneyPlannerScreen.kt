package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.common.AbsoluteTimeFormatter
import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.datastore.preference.rememberUse24Hour
import ac.jfx.openptv.core.designsystem.DepartureTimeSelector
import ac.jfx.openptv.core.designsystem.ScreenHeading
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point wired from the navigation graph (issue #204). Hoists
 * [JourneyPlannerViewModel] off Hilt and drops the rest into the stateless
 * [JourneyPlannerScreenContent] so previews and tests don't need DI.
 *
 * [onOpenRunPattern] fires when a journey row is tapped — the app composition root pushes the
 * run-pattern destination with the origin as `fromStopId` so the timeline marks where the user
 * boards.
 */
@Composable
fun JourneyPlannerRoute(
    onOpenRunPattern: (runRef: RunRef, routeType: RouteType, fromStopId: StopId) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    viewModel: JourneyPlannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    JourneyPlannerScreenContent(
        uiState = uiState,
        timeFormatter = viewModel.timeFormatter,
        onFieldSelected = viewModel::onFieldSelected,
        onPickerDismissed = viewModel::onPickerDismissed,
        onQueryChanged = viewModel::onQueryChanged,
        onStopPicked = viewModel::onStopPicked,
        onSwapStops = viewModel::onSwapStops,
        onTimeSelected = viewModel::onTimeSelected,
        onTimeCleared = viewModel::onTimeCleared,
        onRetry = viewModel::onRetry,
        onJourneySelected = { option ->
            uiState.origin?.let { origin ->
                onOpenRunPattern(option.runRef, origin.routeType, origin.id)
            }
        },
        onOpenSettings = onOpenSettings,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JourneyPlannerScreenContent(
    uiState: JourneyPlannerUiState,
    timeFormatter: RelativeTimeFormatter,
    onFieldSelected: (JourneyField) -> Unit,
    onPickerDismissed: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onStopPicked: (Stop) -> Unit,
    onSwapStops: () -> Unit,
    onTimeSelected: (kotlinx.datetime.Instant) -> Unit,
    onTimeCleared: () -> Unit,
    onRetry: () -> Unit,
    onJourneySelected: (JourneyOption) -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { SettingsGearButton(onClick = onOpenSettings) },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            ScreenHeading(text = stringResource(R.string.feature_journey_planner_title))

            EndpointFields(
                origin = uiState.origin,
                destination = uiState.destination,
                onFieldSelected = onFieldSelected,
                onSwapStops = onSwapStops,
            )

            if (uiState.activeField != null) {
                StopPickerSection(
                    query = uiState.query,
                    picker = uiState.picker,
                    onQueryChanged = onQueryChanged,
                    onStopPicked = onStopPicked,
                    onPickerDismissed = onPickerDismissed,
                )
            } else {
                TimeSelectorRow(
                    selectedTime = uiState.selectedTime,
                    onSelectTime = onTimeSelected,
                    onClearTime = onTimeCleared,
                )
                ResultsSection(
                    results = uiState.results,
                    timeFormatter = timeFormatter,
                    onRetry = onRetry,
                    onJourneySelected = onJourneySelected,
                )
            }
        }
    }
}

/**
 * The From / To rows plus the swap control. Each row is one tap target that opens the inline
 * picker for that endpoint. Long stop names (V/Line stress case) wrap down, bounded at one
 * line with ellipsis — the swap button keeps its tap target via the text column's weight.
 */
@Composable
private fun EndpointFields(
    origin: Stop?,
    destination: Stop?,
    onFieldSelected: (JourneyField) -> Unit,
    onSwapStops: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EndpointRow(
                label = stringResource(R.string.feature_journey_planner_from_label),
                stop = origin,
                onClick = { onFieldSelected(JourneyField.Origin) },
                testTag = TestTagOriginField,
            )
            HorizontalDivider()
            EndpointRow(
                label = stringResource(R.string.feature_journey_planner_to_label),
                stop = destination,
                onClick = { onFieldSelected(JourneyField.Destination) },
                testTag = TestTagDestinationField,
            )
        }
        val swapDescription = stringResource(R.string.feature_journey_planner_swap)
        IconButton(
            onClick = onSwapStops,
            modifier =
                Modifier
                    .testTag(TestTagSwapButton)
                    .semantics { contentDescription = swapDescription },
        ) {
            Text(text = "⇅", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun EndpointRow(
    label: String,
    stop: Stop?,
    onClick: () -> Unit,
    testTag: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp)
                .testTag(testTag),
    ) {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Text(
            text = stop?.name ?: stringResource(R.string.feature_journey_planner_choose_stop),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (stop != null) FontWeight.Medium else FontWeight.Normal,
            color =
                if (stop != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The inline stop picker: search field (auto-focused) + result rows, `:feature:search` UX. */
@Composable
private fun StopPickerSection(
    query: String,
    picker: StopPickerState,
    onQueryChanged: (String) -> Unit,
    onStopPicked: (Stop) -> Unit,
    onPickerDismissed: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
                        .testTag(TestTagPickerQueryField),
                label = { Text(stringResource(R.string.feature_journey_planner_search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon =
                    if (query.isNotEmpty()) {
                        { ClearQueryButton(onClick = { onQueryChanged("") }) }
                    } else {
                        null
                    },
            )
            TextButton(
                onClick = onPickerDismissed,
                modifier = Modifier.testTag(TestTagPickerCancel),
            ) {
                Text(stringResource(R.string.feature_journey_planner_search_cancel))
            }
        }

        when (picker) {
            StopPickerState.Idle ->
                CenteredMessage(stringResource(R.string.feature_journey_planner_search_idle_hint))
            StopPickerState.Loading -> CenteredLoader()
            StopPickerState.Empty ->
                CenteredMessage(stringResource(R.string.feature_journey_planner_search_empty))
            is StopPickerState.Results ->
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(TestTagPickerResults)) {
                    items(picker.stops, key = { it.id.value to it.routeType }) { stop ->
                        PickerStopRow(stop = stop, onClick = { onStopPicked(stop) })
                        HorizontalDivider()
                    }
                }
            is StopPickerState.Error -> CenteredMessage(picker.reason)
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/**
 * Trailing ✕ in the picker's search field — clears the query in one tap instead of making the
 * user backspace a long stop name. A text glyph, not a Material icon, per the house trade
 * (the icons artifact is deliberately not a dependency). Only composed while the query is
 * non-empty; clearing routes through [onClick] → `onQueryChanged("")`, which the ViewModel
 * pipeline already maps back to [StopPickerState.Idle].
 */
@Composable
private fun ClearQueryButton(onClick: () -> Unit) {
    val description = stringResource(R.string.feature_journey_planner_search_clear)
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .testTag(TestTagPickerClearButton)
                .semantics { contentDescription = description },
    ) {
        Text(text = "✕", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PickerStopRow(
    stop: Stop,
    onClick: () -> Unit,
) {
    val mode = stop.routeType.label()
    val talkback =
        stringResource(
            R.string.feature_journey_planner_picker_row_content_description,
            stop.name,
            stop.suburb.ifBlank { stringResource(R.string.feature_journey_planner_unknown_suburb) },
            mode,
        )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp)
                .semantics { contentDescription = talkback }
                .testTag(TestTagPickerStopRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mode,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(end = 12.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stop.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (stop.suburb.isNotBlank()) {
                Text(stop.suburb, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun TimeSelectorRow(
    selectedTime: kotlinx.datetime.Instant?,
    onSelectTime: (kotlinx.datetime.Instant) -> Unit,
    onClearTime: () -> Unit,
) {
    val use24Hour = rememberUse24Hour()
    DepartureTimeSelector(
        selectedTime = selectedTime,
        nowLabel = stringResource(R.string.feature_journey_planner_departing_now),
        formatTime = { AbsoluteTimeFormatter.format(it, use24Hour) },
        use24Hour = use24Hour,
        onTimeSelected = onSelectTime,
        onCleared = onClearTime,
        modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .testTag(TestTagTimeSelector),
    )
}

@Composable
private fun ResultsSection(
    results: JourneyResultsState,
    timeFormatter: RelativeTimeFormatter,
    onRetry: () -> Unit,
    onJourneySelected: (JourneyOption) -> Unit,
) {
    when (results) {
        JourneyResultsState.Idle ->
            CenteredMessage(
                text = stringResource(R.string.feature_journey_planner_idle_hint),
                modifier = Modifier.testTag(TestTagResultsIdle),
            )
        JourneyResultsState.Loading -> CenteredLoader()
        JourneyResultsState.NoDirectServices -> NoDirectServices()
        is JourneyResultsState.Loaded ->
            LazyColumn(modifier = Modifier.fillMaxSize().testTag(TestTagResultsList)) {
                items(results.options, key = { it.runRef.value }) { option ->
                    JourneyRow(
                        option = option,
                        timeFormatter = timeFormatter,
                        onClicked = { onJourneySelected(option) },
                    )
                    HorizontalDivider()
                }
            }
        is JourneyResultsState.Error ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = results.reason, style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = onRetry, modifier = Modifier.testTag(TestTagRetryButton)) {
                    Text(stringResource(R.string.feature_journey_planner_retry))
                }
            }
    }
}

/**
 * One direct service. Layout follows stop-detail's departure row and the CLAUDE.md overflow
 * rule: the badge is width-capped and wraps down, the text column takes the weight, and the
 * relative time keeps its space on the right.
 */
@Composable
private fun JourneyRow(
    option: JourneyOption,
    timeFormatter: RelativeTimeFormatter,
    onClicked: () -> Unit,
) {
    val use24Hour = rememberUse24Hour()
    val relative =
        timeFormatter.format(
            scheduled = option.scheduledDepartureUtc,
            estimated = option.estimatedDepartureUtc,
        )
    val departs =
        stringResource(
            R.string.feature_journey_planner_departs,
            AbsoluteTimeFormatter.format(option.effectiveDepartureUtc, use24Hour),
        )
    val arrives =
        stringResource(
            R.string.feature_journey_planner_arrives,
            AbsoluteTimeFormatter.format(option.effectiveArrivalUtc, use24Hour),
        )
    val durationMinutes =
        (option.effectiveArrivalUtc - option.effectiveDepartureUtc).inWholeMinutes
    val duration =
        stringResource(R.string.feature_journey_planner_duration_journey, durationMinutes)
    val platformClause =
        option.departurePlatform?.let {
            stringResource(R.string.feature_journey_planner_platform_clause, it.value)
        }

    val talkback =
        stringResource(
            R.string.feature_journey_planner_row_content_description,
            option.route.displayLabel,
            option.direction.name,
            departs,
            arrives,
            duration,
        )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClicked)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = talkback }
                .testTag(TestTagJourneyRow),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.widthIn(max = RouteBadgeMaxWidth),
            ) {
                Text(
                    text = option.route.displayLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    softWrap = true,
                    maxLines = ROUTE_BADGE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = option.direction.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = relative,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        // Stacked, not dot-joined into one run-on string (issue #208): departure (+ platform)
        // then arrival (+ duration), so each fact keeps its own line and stays scannable on
        // narrow screens.
        Text(
            text = listOfNotNull(departs, platformClause).joinToString(separator = " · "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOf(arrives, duration).joinToString(separator = " · "),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NoDirectServices() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp).testTag(TestTagNoDirectServices),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feature_journey_planner_no_direct_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.feature_journey_planner_no_direct_subtitle),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun CenteredLoader() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun CenteredMessage(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun RouteType.label(): String =
    when (this) {
        RouteType.Train -> stringResource(R.string.feature_journey_planner_route_type_train)
        RouteType.Tram -> stringResource(R.string.feature_journey_planner_route_type_tram)
        RouteType.Bus -> stringResource(R.string.feature_journey_planner_route_type_bus)
        RouteType.VLine -> stringResource(R.string.feature_journey_planner_route_type_vline)
        RouteType.NightBus -> stringResource(R.string.feature_journey_planner_route_type_night_bus)
        RouteType.Unknown -> stringResource(R.string.feature_journey_planner_route_type_unknown)
    }

/** Top-left settings gear — same trade as the other main screens (issue #111). */
@Composable
private fun SettingsGearButton(onClick: () -> Unit) {
    val description = stringResource(R.string.feature_journey_planner_open_settings)
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .testTag(TestTagSettingsGear)
                .semantics { contentDescription = description },
    ) {
        Text(text = "⚙", style = MaterialTheme.typography.titleLarge)
    }
}

private val RouteBadgeMaxWidth = 160.dp
private const val ROUTE_BADGE_MAX_LINES = 3

internal const val TestTagOriginField: String = "journey-origin-field"
internal const val TestTagDestinationField: String = "journey-destination-field"
internal const val TestTagSwapButton: String = "journey-swap-button"
internal const val TestTagTimeSelector: String = "journey-time-selector"
internal const val TestTagPickerQueryField: String = "journey-picker-query-field"
internal const val TestTagPickerCancel: String = "journey-picker-cancel"
internal const val TestTagPickerClearButton: String = "journey-picker-clear-button"
internal const val TestTagPickerResults: String = "journey-picker-results"
internal const val TestTagPickerStopRow: String = "journey-picker-stop-row"
internal const val TestTagResultsIdle: String = "journey-results-idle"
internal const val TestTagResultsList: String = "journey-results-list"
internal const val TestTagJourneyRow: String = "journey-row"
internal const val TestTagNoDirectServices: String = "journey-no-direct-services"
internal const val TestTagRetryButton: String = "journey-retry-button"
internal const val TestTagSettingsGear: String = "journey-settings-gear"
