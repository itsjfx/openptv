package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.datastore.preference.FavouritesSortPreference
import ac.jfx.openptv.core.model.RouteType
import kotlinx.datetime.Instant

/**
 * UI state for the favourites screen. The screen has three shapes:
 *
 *  - [Loading] — the favourites repository hasn't emitted yet (first frame after launch).
 *  - [Empty] — the repository emitted an empty list; show the "Star a route at a stop to favourite
 *    it" copy and the search CTA.
 *  - [Loaded] — at least one favourite exists; render the row list.
 *
 * The sort preference travels with [Loaded.sort] so the screen knows which `FilterChip` to
 * highlight without reading the composition local again at the row level. `pendingUndo` is set
 * when the user swipes a row away and clears either when they tap "Undo" or when the snackbar
 * times out (the VM handles both).
 */
sealed interface FavouritesUiState {
    data object Loading : FavouritesUiState

    data object Empty : FavouritesUiState

    data class Loaded(
        val rows: List<FavouriteRow>,
        val sort: FavouritesSortPreference,
        val pendingUndo: PendingUndo? = null,
    ) : FavouritesUiState
}

/**
 * One row in the favourites list. The row is a route-at-stop — `(stopId, routeId, directionId)` is
 * the composite key — plus the cached display fields the repository persists alongside the
 * favourite (so the row renders without a network call) and the live [nextDeparture] computed by
 * the ViewModel.
 *
 * Equality semantics: `key` is the de-facto identity (`LazyColumn` `key =` slot, snackbar undo
 * lookup, reorder payload). Two rows with the same `key` and different `nextDeparture` are still
 * "the same row" — only the right-hand subtext changes between ticks.
 */
data class FavouriteRow(
    val key: FavouriteKey,
    val routeType: RouteType,
    val stopName: String,
    val stopSuburb: String,
    val routeNumber: String,
    val routeName: String,
    val directionName: String,
    val nextDeparture: NextDepartureState,
    val position: Int,
    // `lat` / `lng` carry the favourite's stored stop coordinates so the Nearest sort (Phase 05)
    // can compute haversine distance from the user's last-known fix without re-reading the
    // repository. Set to 0 / 0 if the original favourite was added without a geo fix (legacy
    // pre-Phase-05 entries, see the `onUndoDelete` comment in FavouritesViewModel).
    val lat: Double,
    val lng: Double,
)

/**
 * Composite primary key for a favourite. Stable across renames / suburb updates — the cached
 * display fields can change but the triple never does. Used as the `key =` slot for `LazyColumn`
 * row identity and as the undo lookup.
 */
data class FavouriteKey(
    val stopId: Int,
    val routeId: Int,
    val directionId: Int,
)

/**
 * Live "next departure" subtext state for a single row. The ViewModel re-runs the batched fetch
 * every 60 s while RESUMED (mirrors stop-detail) and projects each favourite into one of:
 *
 *  - [Loading] — first fetch hasn't landed yet, or the row was just added.
 *  - [Empty] — the fetch succeeded but no matching `(routeId, directionId)` is upcoming.
 *  - [Loaded] — a real next departure exists; the screen renders [relativeLabel] (already-formatted
 *    by `RelativeTimeFormatter`) on the right of the row.
 *  - [Error] — the fetch failed and no previous Loaded value is available to fall back to.
 *
 * The VM holds onto the most-recent [Loaded] across a transient [Error] tick so the row's label
 * doesn't flicker on a single dropped poll — the screen only sees [Error] when no stale value is
 * available.
 */
sealed interface NextDepartureState {
    data object Loading : NextDepartureState

    data object Empty : NextDepartureState

    data class Loaded(
        val relativeLabel: String,
        val scheduledUtc: Instant,
        val estimatedUtc: Instant?,
    ) : NextDepartureState

    data object Error : NextDepartureState
}

/**
 * Bookkeeping for swipe-to-delete with undo. The VM stashes the removed [row] plus its original
 * [originalPosition] when the user swipes it away; the screen reads this to show the snackbar with
 * "Undo". Tapping undo calls back into the VM, which re-`add`s the favourite (and a
 * follow-up `reorder` will land it at `originalPosition` once the repository supports
 * insert-at-index — see PR body).
 */
data class PendingUndo(
    val row: FavouriteRow,
    val originalPosition: Int,
)
