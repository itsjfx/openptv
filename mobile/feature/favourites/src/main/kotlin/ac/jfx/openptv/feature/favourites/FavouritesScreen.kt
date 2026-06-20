package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.common.AbsoluteTimeFormatter
import ac.jfx.openptv.core.datastore.preference.rememberUse24Hour
import ac.jfx.openptv.core.designsystem.ScreenHeading
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.feature.favourites.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlin.math.roundToInt

/**
 * Stateful Hilt-aware entry point. Mirrors `:feature:stop-detail`'s `StopDetailRoute` polling
 * lifecycle: `repeatOnLifecycle(RESUMED)` kicks off the 60 s next-departure tick, cancelling it
 * on Pause and re-launching on Resume.
 *
 * `onOpenStopDetail` accepts the three values the navigation key needs: `stopId`,
 * `routeTypeCode`, `focusDestinationKey`. The app composition root translates those into an
 * `AppNavKey.StopDetail` push.
 */
@Composable
fun FavouritesRoute(
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int, focusDestinationKey: String?) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: FavouritesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.startObserving()
        }
        viewModel.stopObserving()
    }

    FavouritesScreen(
        uiState = uiState,
        onRowClicked = { row ->
            onOpenStopDetail(row.key.stopId, row.routeType.toCode(), row.key.destinationKey)
        },
        onReorder = viewModel::onReorder,
        onSwipeDelete = viewModel::onSwipeDelete,
        onUndoDelete = viewModel::onUndoDelete,
        onClearUndo = viewModel::clearPendingUndo,
        onOpenSearch = onOpenSearch,
        onOpenSettings = onOpenSettings,
        onToggleEditMode = viewModel::toggleEditMode,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Scaffold + list + empty / loading branches — kept inline so the screen reads top-to-bottom
internal fun FavouritesScreen(
    uiState: FavouritesUiState,
    onRowClicked: (FavouriteRow) -> Unit,
    onReorder: (List<FavouriteKey>) -> Unit,
    onSwipeDelete: (FavouriteKey) -> Unit,
    onUndoDelete: () -> Unit,
    onClearUndo: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onToggleEditMode: () -> Unit,
    onRefresh: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val removedCopy = stringResource(R.string.feature_favourites_removed)
    val undoCopy = stringResource(R.string.feature_favourites_undo)
    val loaded = uiState as? FavouritesUiState.Loaded
    val pendingUndo = loaded?.pendingUndo
    val editMode = loaded?.editMode ?: false
    val isRefreshing = loaded?.isRefreshing ?: false

    // Wire the pending-undo state to the snackbar host. When the VM stashes a pending undo we
    // show the snackbar; the snackbar's dismissal/action result feeds back into the VM through
    // either `onUndoDelete` or `onClearUndo`. Done as a LaunchedEffect so a fresh undo from
    // a different row replaces a still-visible snackbar.
    LaunchedEffect(pendingUndo) {
        if (pendingUndo != null) {
            val result =
                snackbarHostState.showSnackbar(
                    message = removedCopy,
                    actionLabel = undoCopy,
                    withDismissAction = true,
                )
            when (result) {
                SnackbarResult.ActionPerformed -> onUndoDelete()
                SnackbarResult.Dismissed -> onClearUndo()
            }
        }
    }

    Scaffold(
        topBar = {
            // Small TopAppBar — gear + edit live in the compact icon row pinned under the status
            // bar. The hero "Favourites" heading is rendered in the content body via
            // [ScreenHeading]; same shape ReadYou's `FeedsPage` + `DisplayText` use. The previous
            // `LargeTopAppBar` pushed the gear down into the expanded title section, which read as
            // too low under the status bar.
            TopAppBar(
                title = {},
                navigationIcon = {
                    SettingsGearButton(onClick = onOpenSettings)
                },
                actions = {
                    // Edit toggle (issue #78). A glyph stand-in keeps the dep surface tight —
                    // no Material Icons artifact pull, same trade as elsewhere in the app.
                    if (loaded != null && loaded.rows.isNotEmpty()) {
                        IconButton(
                            onClick = onToggleEditMode,
                            modifier = Modifier.testTag(TestTagEditToggle),
                        ) {
                            Text(
                                text = if (editMode) "✓" else "✎",
                                style = MaterialTheme.typography.titleLarge,
                                color =
                                    if (editMode) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.testTag(TestTagRoot),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            ScreenHeading(text = stringResource(R.string.feature_favourites_title))
            // PullToRefreshBox wraps the list so the user can drag down anywhere on the favourites
            // surface to trigger a manual fan-out (issue #78). Mirrors stop-detail's pattern.
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .testTag(TestTagPullToRefresh),
            ) {
                when (uiState) {
                    FavouritesUiState.Loading ->
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    FavouritesUiState.Empty ->
                        EmptyState(onOpenSearch = onOpenSearch)
                    is FavouritesUiState.Loaded ->
                        if (uiState.rows.isEmpty()) {
                            EmptyState(onOpenSearch = onOpenSearch)
                        } else {
                            RowList(
                                rows = uiState.rows,
                                editMode = uiState.editMode,
                                onRowClicked = onRowClicked,
                                onReorder = onReorder,
                                onSwipeDelete = onSwipeDelete,
                            )
                        }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(onOpenSearch: () -> Unit) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(32.dp)
                .testTag(TestTagEmpty),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.feature_favourites_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSearch, modifier = Modifier.testTag(TestTagEmptyCta)) {
            Text(stringResource(R.string.feature_favourites_empty_cta))
        }
    }
}

/**
 * Stateful list with optional drag-to-reorder. The drag handle and delete button are only
 * visible (and only wired to a `pointerInput`) when [editMode] is true (issue #78). The
 * reorder uses a hand-rolled `Modifier.pointerInput { detectDragGesturesAfterLongPress }` + an
 * index-offset map so we don't pull in a third-party library.
 */
@Composable
private fun RowList(
    rows: List<FavouriteRow>,
    editMode: Boolean,
    onRowClicked: (FavouriteRow) -> Unit,
    onReorder: (List<FavouriteKey>) -> Unit,
    onSwipeDelete: (FavouriteKey) -> Unit,
) {
    // Local re-orderable working copy of the row list. We mutate it as the user drags, then call
    // `onReorder(...)` once the drag completes so the repository only sees one persist per drag.
    var working by remember(rows) { mutableStateOf(rows) }
    var draggingKey by remember { mutableStateOf<FavouriteKey?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }

    LazyColumn(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(TestTagRowList),
    ) {
        // `LazyColumn`'s `key =` slot is round-tripped through `Bundle` for state restoration,
        // which only accepts primitives. `FavouriteKey` is a data class, so we serialise it to a
        // dotted string here — same identity, Bundle-safe.
        items(working, key = { it.key.asLazyListKey() }) { row ->
            val isDragging = draggingKey == row.key
            val rowModifier =
                Modifier
                    .fillMaxWidth()
                    .offset { if (isDragging) IntOffset(0, dragOffsetY.roundToInt()) else IntOffset.Zero }
                    .background(
                        if (isDragging) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
            FavouriteRowContent(
                row = row,
                editMode = editMode,
                onRowClicked = onRowClicked,
                onSwipeDelete = onSwipeDelete,
                dragModifier =
                    if (editMode) {
                        Modifier.pointerInput(row.key) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingKey = row.key
                                    dragOffsetY = 0f
                                },
                                onDragEnd = {
                                    draggingKey = null
                                    dragOffsetY = 0f
                                    onReorder(working.map { it.key })
                                },
                                onDragCancel = {
                                    draggingKey = null
                                    dragOffsetY = 0f
                                },
                                onDrag = { _, dragAmount ->
                                    dragOffsetY += dragAmount.y
                                    // Each ROW_HEIGHT_PX of drag past the threshold swaps this row
                                    // with its neighbour in the working list. Cheap O(n) lookup —
                                    // the favourites list is small.
                                    val currentIndex = working.indexOfFirst { it.key == row.key }
                                    if (currentIndex < 0) return@detectDragGesturesAfterLongPress
                                    val direction =
                                        when {
                                            dragOffsetY > REORDER_THRESHOLD_PX -> 1
                                            dragOffsetY < -REORDER_THRESHOLD_PX -> -1
                                            else -> 0
                                        }
                                    if (direction != 0) {
                                        val targetIndex = currentIndex + direction
                                        if (targetIndex in working.indices) {
                                            working =
                                                working.toMutableList().also { mutable ->
                                                    val item = mutable.removeAt(currentIndex)
                                                    mutable.add(targetIndex, item)
                                                }
                                            dragOffsetY -= direction * REORDER_THRESHOLD_PX
                                        }
                                    }
                                },
                            )
                        }
                    } else {
                        Modifier
                    },
                modifier = rowModifier,
            )
            HorizontalDivider()
        }
    }
}

@Composable
@Suppress("LongMethod") // top section + bottom subtext + edit-mode affordances; pulling helpers out fragments the layout reading
private fun FavouriteRowContent(
    row: FavouriteRow,
    editMode: Boolean,
    onRowClicked: (FavouriteRow) -> Unit,
    onSwipeDelete: (FavouriteKey) -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clickable(enabled = !editMode) { onRowClicked(row) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(testTagForRow(row.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            // Issue #78 part 3: stop name on the top line, route + direction below. The mode
            // glyph stays on this row for an at-a-glance type cue.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.routeType.glyph(),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text =
                        if (row.stopSuburb.isBlank()) {
                            row.stopName
                        } else {
                            "${row.stopName} · ${row.stopSuburb}"
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Issue #137: the route badge is the actual next service's badge, lifted off
                // `NextDepartureState.Loaded`. Multi-route destinations like "City" rotate the
                // badge across Cranbourne / Pakenham / Frankston as each comes up; single-route
                // destinations always show the same badge. While the next-departure fetch is
                // still loading the badge slot is empty — the destination label below carries
                // the row on its own.
                val liveBadge = (row.nextDeparture as? NextDepartureState.Loaded)?.routeBadge
                if (!liveBadge.isNullOrBlank()) {
                    // Issue #171: cap the badge so a long V/Line line name wraps down instead of
                    // squeezing the destination label off the row. Mirrors the stop-detail row.
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.widthIn(max = FAVOURITE_BADGE_MAX_WIDTH),
                    ) {
                        Text(
                            text = liveBadge,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            softWrap = true,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = stringResource(R.string.feature_favourites_to_destination, row.destinationName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // Times column — issue #78 part 2: scheduled and live alongside each other.
        NextDepartureSubtext(
            state = row.nextDeparture,
            modifier = Modifier.padding(start = 8.dp).testTag(testTagForNext(row.key)),
        )
        if (editMode) {
            Spacer(modifier = Modifier.width(8.dp))
            // Drag handle — long-press anywhere on the handle to grab the row.
            Box(
                modifier =
                    Modifier
                        .testTag(TestTagDragHandle)
                        .padding(8.dp)
                        .then(dragModifier),
            ) {
                Text(
                    text = "⋮⋮",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Delete button — only present in edit mode (issue #78 part 1). Test tag uses the
            // row's composite key so the test can address a specific row.
            IconButton(
                onClick = { onSwipeDelete(row.key) },
                modifier = Modifier.testTag(testTagForDelete(row.key)),
            ) {
                Text(
                    text = "✕",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * Per-row "next departure" subtext. Renders the scheduled time and the live tracking time side
 * by side so the user sees both — issue #78 part 2 explicitly asks for this rather than the
 * single relative label the previous version showed (which collapsed to "Departed" once the
 * scheduled time slipped past).
 *
 * Layout:
 *  - Loaded with no estimate: scheduled time + relative label.
 *  - Loaded with estimate equal to scheduled: scheduled time + relative label.
 *  - Loaded with estimate different from scheduled: scheduled time (struck through) + estimated
 *    time (in primary colour) + relative label.
 *  - Loading / Empty / Error: a plain placeholder so the row layout stays stable.
 */
@Composable
private fun NextDepartureSubtext(
    state: NextDepartureState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        is NextDepartureState.Loaded -> {
            // Issue #89: format absolute clock-faces against the user's 12/24-hour preference
            // (via `LocalTimeFormat`, falling back to the system 24-hour flag when set to
            // "Follow system"). The relative label below remains untouched — "in 4 min" has
            // no clock face to localise.
            val use24Hour = rememberUse24Hour()
            val scheduledClock = AbsoluteTimeFormatter.format(state.scheduledUtc, use24Hour)
            val estimatedClock =
                state.estimatedUtc?.let { AbsoluteTimeFormatter.format(it, use24Hour) }
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.End,
            ) {
                val live = estimatedClock?.takeIf { it != scheduledClock }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = scheduledClock,
                        style = MaterialTheme.typography.bodyMedium,
                        // Strike through the scheduled time when a live estimate disagrees so the
                        // user can see at a glance which is the source of truth — same shape PTV
                        // and Citymapper use for tracked services.
                        textDecoration = if (live != null) TextDecoration.LineThrough else TextDecoration.None,
                        color =
                            if (live != null) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                    )
                    if (live != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = live,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    text = state.relativeLabel,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        NextDepartureState.Loading ->
            Text(
                text = stringResource(R.string.feature_favourites_next_loading),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        NextDepartureState.Empty ->
            Text(
                text = stringResource(R.string.feature_favourites_next_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        NextDepartureState.Error ->
            Text(
                text = stringResource(R.string.feature_favourites_next_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = modifier,
            )
    }
}

/**
 * Top-left settings gear, used by the three bottom-nav main screens (issue #111). Settings is no
 * longer a bottom-nav tab — it lives behind this gear instead, and the host pushes it as a
 * destination so system back returns to whichever main screen launched it. Glyph stand-in keeps
 * `:feature:favourites` off the Material Icons artifact, same trade as the edit toggle.
 */
@Composable
private fun SettingsGearButton(onClick: () -> Unit) {
    val description = stringResource(R.string.feature_favourites_open_settings)
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

private fun RouteType.glyph(): String =
    when (this) {
        RouteType.Train -> "🚆"
        RouteType.Tram -> "🚊"
        RouteType.Bus -> "🚌"
        RouteType.VLine -> "🚉"
        RouteType.NightBus -> "🌙"
        RouteType.Unknown -> "•"
    }

internal fun testTagForRow(key: FavouriteKey): String =
    "favourites-row-${key.stopId}-${key.destinationKey}"

internal fun testTagForDelete(key: FavouriteKey): String =
    "favourites-delete-${key.stopId}-${key.destinationKey}"

internal fun testTagForNext(key: FavouriteKey): String =
    "favourites-next-${key.stopId}-${key.destinationKey}"

/**
 * Bundle-safe string projection of [FavouriteKey] for `LazyColumn`'s `key =` slot.
 *
 * `LazyColumn` round-trips the key through `Bundle` via `SaveableStateHolder` for state
 * restoration, and `Bundle` rejects anything that isn't a registered primitive / `Parcelable` /
 * `Serializable` — a Kotlin `data class` matches `Serializable` only if explicitly declared so,
 * which [FavouriteKey] is not. Project to a delimited `String` instead — same uniqueness,
 * Bundle-safe.
 *
 * `|` is the delimiter because PTV destination strings are space-separated words and can contain
 * dots (e.g. "St. Kilda"), so `.` would collide. `|` doesn't appear in any PTV destination.
 *
 * Visible to tests so a JVM regression can assert the contract without booting an emulator.
 */
internal fun FavouriteKey.asLazyListKey(): String = "$stopId|$destinationKey"

/** How many pixels of vertical drag count as one row-swap. Tuned for a typical ~64.dp row. */
private const val REORDER_THRESHOLD_PX: Float = 80f

// Issue #171: keep the favourite row's route badge from growing unbounded on long V/Line names.
private val FAVOURITE_BADGE_MAX_WIDTH = 140.dp

internal const val TestTagRoot: String = "favourites-root"
internal const val TestTagEmpty: String = "favourites-empty"
internal const val TestTagEmptyCta: String = "favourites-empty-cta"
internal const val TestTagRowList: String = "favourites-row-list"
internal const val TestTagDragHandle: String = "favourites-drag-handle"
internal const val TestTagEditToggle: String = "favourites-edit-toggle"
internal const val TestTagPullToRefresh: String = "favourites-pull-to-refresh"
internal const val TestTagSettingsGear: String = "favourites-settings-gear"
