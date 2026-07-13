package ac.jfx.openptv.feature.journeyplanner

import ac.jfx.openptv.core.common.AbsoluteTimeFormatter
import ac.jfx.openptv.core.common.RelativeTimeFormatter
import ac.jfx.openptv.core.datastore.preference.rememberUse24Hour
import ac.jfx.openptv.core.designsystem.DepartureTimeSelector
import ac.jfx.openptv.core.designsystem.ScreenHeading
import ac.jfx.openptv.core.model.FollowedTrip
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.launch

/**
 * Stateful entry point wired from the navigation graph (issue #204). Hoists
 * [JourneyPlannerViewModel] off Hilt and drops the rest into the stateless
 * [JourneyPlannerScreenContent] so previews and tests don't need DI.
 *
 * [onOpenRunPattern] fires when a journey row is tapped — the app composition root pushes the
 * run-pattern destination with the origin as `fromStopId` so the timeline marks where the user
 * boards.
 *
 * [prefillOrigin]/[prefillDestination] are the one-shot endpoint prefill (issue #209): tapping a
 * journey favourite on the Favourites tab switches to this tab with both stops supplied. The
 * `LaunchedEffect` applies them once and fires [onPrefillConsumed] so the host clears the
 * pending pair — the NearbyRoute focus-consumption pattern, but host-owned state instead of a
 * nav-key arg because `Stop` doesn't fit in a route.
 */
@Composable
fun JourneyPlannerRoute(
    onOpenRunPattern: (runRef: RunRef, routeType: RouteType, fromStopId: StopId) -> Unit = { _, _, _ -> },
    onOpenSettings: () -> Unit = {},
    prefillOrigin: Stop? = null,
    prefillDestination: Stop? = null,
    onPrefillConsumed: () -> Unit = {},
    viewModel: JourneyPlannerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(prefillOrigin, prefillDestination) {
        if (prefillOrigin != null && prefillDestination != null) {
            viewModel.onEndpointsPrefilled(prefillOrigin, prefillDestination)
            onPrefillConsumed()
        }
    }

    // Alight-alert permission plumbing (issue #220) — the RunPatternRoute recipe verbatim: both
    // prompts are contextual (notifications on arm, location only when the armed run is
    // schedule-only), neither grant is a precondition, and a snackbar explains what a denial
    // degrades.
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var pendingAlightArm by remember { mutableStateOf<JourneyOption?>(null) }
    val notificationsDeniedMessage =
        stringResource(R.string.feature_journey_planner_alight_notifications_denied)
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                scope.launch { snackbarHostState.showSnackbar(notificationsDeniedMessage) }
            }
            pendingAlightArm?.let(viewModel::armAlightAlert)
            pendingAlightArm = null
        }
    val onArmAlight: (JourneyOption) -> Unit = { option ->
        if (needsNotificationPermission(context)) {
            pendingAlightArm = option
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.armAlightAlert(option)
        }
    }

    val scheduleOnlyMessage = stringResource(R.string.feature_journey_planner_alight_schedule_only)
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

    JourneyPlannerScreenContent(
        uiState = uiState,
        timeFormatter = viewModel.timeFormatter,
        onFieldSelected = viewModel::onFieldSelected,
        onStopCleared = viewModel::onStopCleared,
        onPickerDismissed = viewModel::onPickerDismissed,
        onQueryChanged = viewModel::onQueryChanged,
        onRouteTypeFilterToggled = viewModel::onRouteTypeFilterToggled,
        onStopPicked = viewModel::onStopPicked,
        onSwapStops = viewModel::onSwapStops,
        onToggleFavouriteJourney = viewModel::onToggleFavouriteJourney,
        onTimeSelected = viewModel::onTimeSelected,
        onTimeCleared = viewModel::onTimeCleared,
        onRetry = viewModel::onRetry,
        onJourneySelected = { option ->
            uiState.origin?.let { origin ->
                onOpenRunPattern(option.runRef, origin.routeType, origin.id)
            }
        },
        onArmAlight = onArmAlight,
        onDisarmAlight = viewModel::disarmAlightAlert,
        onConfirmReplaceFollow = viewModel::confirmReplaceFollow,
        onDismissReplaceFollow = viewModel::dismissReplaceFollow,
        snackbarHostState = snackbarHostState,
        onOpenSettings = onOpenSettings,
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
@Suppress("LongParameterList") // one callback per screen event, unidirectional-state convention
internal fun JourneyPlannerScreenContent(
    uiState: JourneyPlannerUiState,
    timeFormatter: RelativeTimeFormatter,
    onFieldSelected: (JourneyField) -> Unit,
    onStopCleared: (JourneyField) -> Unit,
    onPickerDismissed: () -> Unit,
    onQueryChanged: (String) -> Unit,
    onRouteTypeFilterToggled: (RouteType) -> Unit,
    onStopPicked: (Stop) -> Unit,
    onSwapStops: () -> Unit,
    onToggleFavouriteJourney: () -> Unit,
    onTimeSelected: (kotlinx.datetime.Instant) -> Unit,
    onTimeCleared: () -> Unit,
    onRetry: () -> Unit,
    onJourneySelected: (JourneyOption) -> Unit,
    onArmAlight: (JourneyOption) -> Unit = {},
    onDisarmAlight: (JourneyOption) -> Unit = {},
    onConfirmReplaceFollow: () -> Unit = {},
    onDismissReplaceFollow: () -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onOpenSettings: () -> Unit = {},
) {
    // Replace-confirmation for the followed trip (issue #200 rule, surfaced here by the
    // result-row bell): shown when arming while a *different* run is already followed.
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
                isFavouriteJourney = uiState.isFavouriteJourney,
                onFieldSelected = onFieldSelected,
                onStopCleared = onStopCleared,
                onSwapStops = onSwapStops,
                onToggleFavouriteJourney = onToggleFavouriteJourney,
            )

            if (uiState.activeField != null) {
                StopPickerSection(
                    query = uiState.query,
                    routeTypeFilter = uiState.routeTypeFilter,
                    picker = uiState.picker,
                    onQueryChanged = onQueryChanged,
                    onRouteTypeFilterToggled = onRouteTypeFilterToggled,
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
                    alightArmedRunRef = uiState.alightArmedRunRef,
                    timeFormatter = timeFormatter,
                    onRetry = onRetry,
                    onJourneySelected = onJourneySelected,
                    onArmAlight = onArmAlight,
                    onDisarmAlight = onDisarmAlight,
                )
            }
        }
    }
}

/**
 * The From / To rows plus the swap control and — once both endpoints are chosen — the ★
 * journey-favourite toggle (issue #209). Each row is one tap target that opens the inline
 * picker for that endpoint. Long stop names (V/Line stress case) wrap down, bounded at one
 * line with ellipsis — the buttons keep their tap targets via the text column's weight.
 */
@Composable
private fun EndpointFields(
    origin: Stop?,
    destination: Stop?,
    isFavouriteJourney: Boolean,
    onFieldSelected: (JourneyField) -> Unit,
    onStopCleared: (JourneyField) -> Unit,
    onSwapStops: () -> Unit,
    onToggleFavouriteJourney: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            EndpointRow(
                label = stringResource(R.string.feature_journey_planner_from_label),
                stop = origin,
                clearContentDescription =
                    stringResource(R.string.feature_journey_planner_clear_origin),
                onClick = { onFieldSelected(JourneyField.Origin) },
                onClear = { onStopCleared(JourneyField.Origin) },
                testTag = TestTagOriginField,
                clearTestTag = TestTagOriginClear,
            )
            HorizontalDivider()
            EndpointRow(
                label = stringResource(R.string.feature_journey_planner_to_label),
                stop = destination,
                clearContentDescription =
                    stringResource(R.string.feature_journey_planner_clear_destination),
                onClick = { onFieldSelected(JourneyField.Destination) },
                onClear = { onStopCleared(JourneyField.Destination) },
                testTag = TestTagDestinationField,
                clearTestTag = TestTagDestinationClear,
            )
        }
        if (origin != null && destination != null) {
            FavouriteJourneyToggle(
                isFavourite = isFavouriteJourney,
                onClick = onToggleFavouriteJourney,
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

/**
 * ★ toggle for the current origin→destination pair (issue #209). Filled when favourited,
 * hollow otherwise — the stop-detail favourite-star trade: a text glyph with a `Crossfade`,
 * no Material Icons artifact. Only composed once both endpoints are set; A→B and B→A are
 * distinct favourites, so swapping the stops flips the star to the other pair's state.
 */
@Composable
private fun FavouriteJourneyToggle(
    isFavourite: Boolean,
    onClick: () -> Unit,
) {
    val description =
        if (isFavourite) {
            stringResource(R.string.feature_journey_planner_unfavourite_journey)
        } else {
            stringResource(R.string.feature_journey_planner_favourite_journey)
        }
    IconButton(
        onClick = onClick,
        modifier =
            Modifier
                .testTag(TestTagFavouriteJourneyToggle)
                .semantics { contentDescription = description },
    ) {
        Crossfade(targetState = isFavourite, label = "favourite-journey-star") { favourited ->
            Text(
                text = if (favourited) "★" else "☆",
                style = MaterialTheme.typography.titleLarge,
                color =
                    if (favourited) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
    }
}

/**
 * One endpoint field. The text column is the open-picker tap target; the ✕ (issue #215, only
 * composed once a stop is chosen) is its own target that clears just this endpoint — clearing
 * either endpoint drops the results back to the idle hint, since results need both.
 */
@Composable
private fun EndpointRow(
    label: String,
    stop: Stop?,
    clearContentDescription: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    testTag: String,
    clearTestTag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
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
        if (stop != null) {
            IconButton(
                onClick = onClear,
                modifier =
                    Modifier
                        .testTag(clearTestTag)
                        .semantics { contentDescription = clearContentDescription },
            ) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The inline stop picker: search field (auto-focused) + mode chips + result rows,
 * `:feature:search` UX. The chip row (issue #213) filters both the search results (on the wire)
 * and the favourite-stops idle list (client-side, in the ViewModel).
 */
@Composable
private fun StopPickerSection(
    query: String,
    routeTypeFilter: Set<RouteType>,
    picker: StopPickerState,
    onQueryChanged: (String) -> Unit,
    onRouteTypeFilterToggled: (RouteType) -> Unit,
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

        RouteTypeFilterRow(
            selected = routeTypeFilter,
            onToggle = onRouteTypeFilterToggled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .testTag(TestTagPickerFilterRow),
        )

        when (picker) {
            is StopPickerState.Idle ->
                if (picker.favouriteStops.isEmpty()) {
                    CenteredMessage(stringResource(R.string.feature_journey_planner_search_idle_hint))
                } else {
                    FavouriteStopsList(stops = picker.favouriteStops, onStopPicked = onStopPicked)
                }
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
 * Favourite stops offered while the picker query is empty (issue #209): the user's starred
 * stops, one row per distinct stop, so common endpoints are one tap away before any typing.
 * Rows reuse [PickerStopRow] — tapping selects the stop exactly like a search result.
 */
@Composable
private fun FavouriteStopsList(
    stops: List<Stop>,
    onStopPicked: (Stop) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(TestTagPickerFavouriteStops)) {
        item(key = "favourite-stops-header") {
            Text(
                text = stringResource(R.string.feature_journey_planner_favourite_stops_header),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        items(stops, key = { it.id.value to it.routeType }) { stop ->
            PickerStopRow(stop = stop, onClick = { onStopPicked(stop) })
            HorizontalDivider()
        }
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
    alightArmedRunRef: RunRef?,
    timeFormatter: RelativeTimeFormatter,
    onRetry: () -> Unit,
    onJourneySelected: (JourneyOption) -> Unit,
    onArmAlight: (JourneyOption) -> Unit,
    onDisarmAlight: (JourneyOption) -> Unit,
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
                    val isAlightArmed = option.runRef == alightArmedRunRef
                    JourneyRow(
                        option = option,
                        timeFormatter = timeFormatter,
                        onClicked = { onJourneySelected(option) },
                        isAlightArmed = isAlightArmed,
                        onToggleAlight = {
                            if (isAlightArmed) onDisarmAlight(option) else onArmAlight(option)
                        },
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
 *
 * The trailing bell (issue #220) arms/disarms the alight alert at the journey's destination —
 * the one-tap version of run-pattern's per-stop bell — signalled through alpha like that
 * screen's, since an emoji glyph ignores tint.
 */
@Composable
private fun JourneyRow(
    option: JourneyOption,
    timeFormatter: RelativeTimeFormatter,
    onClicked: () -> Unit,
    isAlightArmed: Boolean = false,
    onToggleAlight: () -> Unit = {},
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

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClicked)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .semantics { contentDescription = talkback }
                .testTag(TestTagJourneyRow),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
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
        AlightBell(
            isAlightArmed = isAlightArmed,
            onToggleAlight = onToggleAlight,
        )
    }
}

/**
 * The result-row alight-alert bell (issue #220). An emoji glyph ignores tint (it renders in
 * colour), so armed/unarmed is signalled through alpha — the run-pattern `AlightBell` trade.
 * Same no-Material-Icons trade as the rest of the codebase.
 */
@Composable
private fun AlightBell(
    isAlightArmed: Boolean,
    onToggleAlight: () -> Unit,
) {
    val bellDescription =
        stringResource(
            if (isAlightArmed) {
                R.string.feature_journey_planner_alight_disarm_content_description
            } else {
                R.string.feature_journey_planner_alight_arm_content_description
            },
        )
    IconButton(
        onClick = onToggleAlight,
        modifier =
            Modifier
                .testTag(if (isAlightArmed) TestTagAlightArmedButton else TestTagAlightButton)
                .semantics { contentDescription = bellDescription },
    ) {
        Text(
            text = "🔔",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.alpha(if (isAlightArmed) 1f else UNARMED_BELL_ALPHA),
        )
    }
}

/** Confirm replacing the currently followed [candidate] trip with the tapped journey's run. */
@Composable
private fun FollowReplaceDialog(
    candidate: FollowedTrip,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(TestTagFollowReplaceDialog),
        title = { Text(stringResource(R.string.feature_journey_planner_follow_replace_title)) },
        text = {
            Text(
                stringResource(
                    R.string.feature_journey_planner_follow_replace_body,
                    candidate.displayText(),
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag(TestTagFollowReplaceConfirm),
            ) {
                Text(stringResource(R.string.feature_journey_planner_follow_replace_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.feature_journey_planner_follow_replace_cancel))
            }
        },
    )
}

/**
 * Human-readable name for a followed trip in the replace-confirmation dialog — run-pattern's
 * collapse rule, so "Lilydale to Lilydale" reads as "Lilydale".
 */
@Composable
private fun FollowedTrip.displayText(): String {
    val route = routeLabel
    return when {
        route == null -> destinationName
        route.equals(destinationName, ignoreCase = true) -> route
        else ->
            stringResource(
                R.string.feature_journey_planner_follow_replace_trip_format,
                route,
                destinationName,
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
    val rowDescription = stringResource(R.string.feature_journey_planner_filter_row_description)
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

/** Same dim as run-pattern's unarmed bell, so the two surfaces read identically. */
private const val UNARMED_BELL_ALPHA = 0.4f

internal const val TestTagOriginField: String = "journey-origin-field"
internal const val TestTagDestinationField: String = "journey-destination-field"
internal const val TestTagOriginClear: String = "journey-origin-clear"
internal const val TestTagDestinationClear: String = "journey-destination-clear"
internal const val TestTagSwapButton: String = "journey-swap-button"
internal const val TestTagTimeSelector: String = "journey-time-selector"
internal const val TestTagPickerQueryField: String = "journey-picker-query-field"
internal const val TestTagPickerCancel: String = "journey-picker-cancel"
internal const val TestTagPickerFilterRow: String = "journey-picker-filter-row"
internal const val TestTagPickerClearButton: String = "journey-picker-clear-button"
internal const val TestTagPickerFavouriteStops: String = "journey-picker-favourite-stops"
internal const val TestTagFavouriteJourneyToggle: String = "journey-favourite-toggle"
internal const val TestTagPickerResults: String = "journey-picker-results"
internal const val TestTagPickerStopRow: String = "journey-picker-stop-row"
internal const val TestTagResultsIdle: String = "journey-results-idle"
internal const val TestTagResultsList: String = "journey-results-list"
internal const val TestTagJourneyRow: String = "journey-row"
internal const val TestTagAlightButton: String = "journey-alight-bell"
internal const val TestTagAlightArmedButton: String = "journey-alight-bell-armed"
internal const val TestTagFollowReplaceDialog: String = "journey-follow-replace-dialog"
internal const val TestTagFollowReplaceConfirm: String = "journey-follow-replace-confirm"
internal const val TestTagNoDirectServices: String = "journey-no-direct-services"
internal const val TestTagRetryButton: String = "journey-retry-button"
internal const val TestTagSettingsGear: String = "journey-settings-gear"

internal fun filterChipTestTag(routeType: RouteType): String = "journey-picker-filter-chip-${routeType.name.lowercase()}"
