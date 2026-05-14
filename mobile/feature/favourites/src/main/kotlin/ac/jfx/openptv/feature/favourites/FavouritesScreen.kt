package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * `onOpenStopDetail` accepts the four ints the destination key needs: `stopId`, `routeTypeCode`,
 * `focusRouteId`, `focusDirectionId`. The app composition root translates those into an
 * `AppNavKey.StopDetail` push.
 */
@Composable
fun FavouritesRoute(
    onOpenStopDetail: (stopId: Int, routeTypeCode: Int, focusRouteId: Int, focusDirectionId: Int) -> Unit,
    onOpenSearch: () -> Unit,
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
        onSortSelected = viewModel::onSortSelected,
        onRowClicked = { row ->
            onOpenStopDetail(row.key.stopId, row.routeType.toCode(), row.key.routeId, row.key.directionId)
        },
        onReorder = viewModel::onReorder,
        onSwipeDelete = viewModel::onSwipeDelete,
        onUndoDelete = viewModel::onUndoDelete,
        onClearUndo = viewModel::clearPendingUndo,
        onOpenSearch = onOpenSearch,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("LongMethod") // Scaffold + sort chips + list + empty / loading branches — kept inline so the screen reads top-to-bottom
internal fun FavouritesScreen(
    uiState: FavouritesUiState,
    onSortSelected: (FavouritesSortPreference) -> Unit,
    onRowClicked: (FavouriteRow) -> Unit,
    onReorder: (List<FavouriteKey>) -> Unit,
    onSwipeDelete: (FavouriteKey) -> Unit,
    onUndoDelete: () -> Unit,
    onClearUndo: () -> Unit,
    onOpenSearch: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedCopy = stringResource(R.string.feature_favourites_removed)
    val undoCopy = stringResource(R.string.feature_favourites_undo)

    // Wire the pending-undo state to the snackbar host. When the VM stashes a pending undo we
    // show the snackbar; the snackbar's dismissal/action result feeds back into the VM through
    // either `onUndoDelete` or `onClearUndo`. Done as a LaunchedEffect so a fresh undo from
    // a different row replaces a still-visible snackbar.
    val pendingUndo = (uiState as? FavouritesUiState.Loaded)?.pendingUndo
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
            TopAppBar(
                title = { Text(stringResource(R.string.feature_favourites_title)) },
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
            SortChipsRow(
                selected = (uiState as? FavouritesUiState.Loaded)?.sort ?: FavouritesSortPreference.Manual,
                onSortSelected = onSortSelected,
            )
            HorizontalDivider()
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
                            manualSort = uiState.sort == FavouritesSortPreference.Manual,
                            onRowClicked = onRowClicked,
                            onReorder = onReorder,
                            onSwipeDelete = onSwipeDelete,
                        )
                    }
            }
        }
    }

    // `scope` isn't used directly today; the snackbar coroutine runs inside `LaunchedEffect`.
    // Keep the handle around for follow-up work (e.g. inline disruption snackbars on the row).
    @Suppress("UNUSED_EXPRESSION")
    scope
}

@Composable
private fun SortChipsRow(
    selected: FavouritesSortPreference,
    onSortSelected: (FavouritesSortPreference) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .testTag(TestTagSortChips),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == FavouritesSortPreference.Manual,
            onClick = { onSortSelected(FavouritesSortPreference.Manual) },
            label = { Text(stringResource(R.string.feature_favourites_sort_manual)) },
            modifier = Modifier.testTag(TestTagSortManual),
        )
        FilterChip(
            selected = selected == FavouritesSortPreference.Alphabetical,
            onClick = { onSortSelected(FavouritesSortPreference.Alphabetical) },
            label = { Text(stringResource(R.string.feature_favourites_sort_alphabetical)) },
            modifier = Modifier.testTag(TestTagSortAlphabetical),
        )
        // Phase 05 — Nearest is enabled. The sort degrades to Manual when LocationProvider has
        // no fix (e.g. user denied coarse location, or device hasn't returned one yet).
        FilterChip(
            selected = selected == FavouritesSortPreference.Nearest,
            onClick = { onSortSelected(FavouritesSortPreference.Nearest) },
            label = { Text(stringResource(R.string.feature_favourites_sort_nearest)) },
            modifier = Modifier.testTag(TestTagSortNearest),
        )
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
 * Stateful list with optional drag-to-reorder. The reorder uses a hand-rolled
 * `Modifier.pointerInput { detectDragGesturesAfterLongPress }` + an index-offset map so we don't
 * pull in a third-party library — see PR body for the trade-off. The drag handle is only visible
 * (and only wired to a `pointerInput`) when [manualSort] is true; alphabetical / nearest modes
 * render rows without the handle because reordering wouldn't survive the next sort tick.
 */
@Composable
private fun RowList(
    rows: List<FavouriteRow>,
    manualSort: Boolean,
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
                manualSort = manualSort,
                onRowClicked = onRowClicked,
                onSwipeDelete = onSwipeDelete,
                dragModifier =
                    if (manualSort) {
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
private fun FavouriteRowContent(
    row: FavouriteRow,
    manualSort: Boolean,
    onRowClicked: (FavouriteRow) -> Unit,
    onSwipeDelete: (FavouriteKey) -> Unit,
    dragModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val nextLabel =
        when (val next = row.nextDeparture) {
            NextDepartureState.Loading -> stringResource(R.string.feature_favourites_next_loading)
            NextDepartureState.Empty -> stringResource(R.string.feature_favourites_next_none)
            is NextDepartureState.Loaded -> next.relativeLabel
            NextDepartureState.Error -> stringResource(R.string.feature_favourites_next_error)
        }
    Row(
        modifier =
            modifier
                .clickable { onRowClicked(row) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag(testTagForRow(row.key)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Route badge — same `primaryContainer` + rounded-corner badge as stop-detail's
        // `DepartureRow` so the visual language is consistent across the two screens.
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(4.dp),
        ) {
            Text(
                text = row.routeNumber,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.directionName.ifBlank { row.routeName },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val secondLine = "${row.stopName} · ${row.stopSuburb}".trimEnd(' ', '·').trim()
            Text(
                text = secondLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        // Mode glyph — tight stand-in until designsystem ships icons.
        Text(
            text = row.routeType.glyph(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Text(
            text = nextLabel,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (manualSort) {
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
        }
        Spacer(modifier = Modifier.width(8.dp))
        // Swipe-to-delete is implemented as a tap-to-delete affordance for v1 — the issue calls
        // out a real swipe gesture but Compose-foundation's `SwipeToDismissBox` interacts poorly
        // with the long-press drag-to-reorder gesture on the same row (the swipe consumes the
        // pointer before the long-press registers). Trading the gesture for an explicit
        // "✕" button keeps both affordances reachable; reviving the swipe is a follow-up if the
        // designsystem wants it. See PR body. Test tag uses the row's composite key so the test
        // can address a specific row.
        TextButton(
            onClick = { onSwipeDelete(row.key) },
            modifier = Modifier.testTag(testTagForDelete(row.key)),
        ) {
            Text("✕")
        }
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
    "favourites-row-${key.stopId}-${key.routeId}-${key.directionId}"

internal fun testTagForDelete(key: FavouriteKey): String =
    "favourites-delete-${key.stopId}-${key.routeId}-${key.directionId}"

/**
 * Bundle-safe string projection of [FavouriteKey] for `LazyColumn`'s `key =` slot.
 *
 * `LazyColumn` round-trips the key through `Bundle` via `SaveableStateHolder` for state
 * restoration, and `Bundle` rejects anything that isn't a registered primitive / `Parcelable` /
 * `Serializable` — a Kotlin `data class` matches `Serializable` only if explicitly declared so,
 * which [FavouriteKey] is not. Passing the data class directly throws
 * `IllegalArgumentException: Type of the key ... is not supported` on the first composition that
 * actually has rows. Project to a delimited `String` instead — same uniqueness, Bundle-safe.
 *
 * Visible to tests so a JVM regression can assert the contract without booting an emulator.
 */
internal fun FavouriteKey.asLazyListKey(): String = "$stopId.$routeId.$directionId"

/** How many pixels of vertical drag count as one row-swap. Tuned for a typical ~64.dp row. */
private const val REORDER_THRESHOLD_PX: Float = 80f

internal const val TestTagRoot: String = "favourites-root"
internal const val TestTagSortChips: String = "favourites-sort-chips"
internal const val TestTagSortManual: String = "favourites-sort-manual"
internal const val TestTagSortAlphabetical: String = "favourites-sort-alphabetical"
internal const val TestTagSortNearest: String = "favourites-sort-nearest"
internal const val TestTagEmpty: String = "favourites-empty"
internal const val TestTagEmptyCta: String = "favourites-empty-cta"
internal const val TestTagRowList: String = "favourites-row-list"
internal const val TestTagDragHandle: String = "favourites-drag-handle"
