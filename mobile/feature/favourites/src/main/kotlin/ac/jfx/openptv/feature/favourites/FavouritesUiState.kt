package ac.jfx.openptv.feature.favourites

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
 * `pendingUndo` is set when the user swipes a row away and clears either when they tap "Undo" or
 * when the snackbar times out (the VM handles both). `editMode` toggles the "drag handle + delete
 * affordance per row" UX (issue #78); when false the row is a plain tappable surface that opens
 * stop-detail. `isRefreshing` flips while a pull-to-refresh fan-out is in flight so the screen
 * can show the indicator (also #78).
 *
 * Sort UI was removed in #78 — the rows always render in manual / repository order. The
 * persisted [`FavouritesSortPreference`] still exists in datastore for backward compatibility but
 * is no longer surfaced or honoured by the screen.
 */
sealed interface FavouritesUiState {
    data object Loading : FavouritesUiState

    data object Empty : FavouritesUiState

    data class Loaded(
        val rows: List<FavouriteRow>,
        val pendingUndo: PendingUndo? = null,
        val editMode: Boolean = false,
        val isRefreshing: Boolean = false,
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
 *  - [Loaded] — a real next departure exists; the screen renders the scheduled clock-time
 *    (derived from [scheduledUtc]) and the live relative label ([relativeLabel]) side by side,
 *    plus the live clock-time when an estimate exists (derived from [estimatedUtc]).
 *  - [Error] — the fetch failed and no previous Loaded value is available to fall back to.
 *
 * Issue #78 explicitly asks for both the scheduled time AND the live tracking time alongside
 * each other (instead of always showing "Departed"). The VM exposes the relative label as a
 * pre-formatted string (because the relative formatter depends on the injected clock) but
 * carries the raw [Instant]s for the absolute clock-faces — the Compose layer formats those
 * against the user's [ac.jfx.openptv.core.datastore.preference.TimeFormatPreference] so a flip
 * from 24-hour to 12-hour in Settings reflects immediately without a tick round-trip.
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
