package ac.jfx.openptv.feature.favourites

import ac.jfx.openptv.core.model.RouteType
import kotlinx.datetime.Instant

/**
 * UI state for the favourites screen. The screen has three shapes:
 *
 *  - [Loading] — the favourites repository hasn't emitted yet (first frame after launch).
 *  - [Empty] — the repository emitted an empty list; show the "Star a destination" copy and the
 *    search CTA.
 *  - [Loaded] — at least one favourite exists; render the row list.
 *
 * `pendingUndo` is set when the user swipes a row away and clears either when they tap "Undo" or
 * the snackbar times out. `editMode` toggles the drag-handle + delete affordance. `isRefreshing`
 * flips while a manual fan-out is in flight.
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
 * One row in the favourites list. The row is a destination-at-stop — `(stopId, destinationKey)`
 * is the composite key — plus the cached display fields the repository persists alongside the
 * favourite and the live [nextDeparture] computed by the ViewModel. The route badge of the actual
 * next service lives in [NextDepartureState.Loaded] (issue #137) so multi-route destinations like
 * "City" show whichever line is up next.
 *
 * `key` is the de-facto identity (LazyColumn `key =` slot, snackbar undo lookup, reorder payload).
 */
data class FavouriteRow(
    val key: FavouriteKey,
    val routeType: RouteType,
    val stopName: String,
    val stopSuburb: String,
    val destinationName: String,
    val nextDeparture: NextDepartureState,
    val position: Int,
    // Stored coordinates so the Nearest sort can compute distance from the user's last fix
    // without re-reading the repository. Set to 0 / 0 if the favourite was added without a fix.
    val lat: Double,
    val lng: Double,
)

/**
 * Composite primary key for a favourite. Stable across stop renames / route additions — the
 * cached display fields can change but the pair never does.
 */
data class FavouriteKey(
    val stopId: Int,
    val destinationKey: String,
)

/**
 * Live "next departure" subtext state for a single row. The ViewModel re-runs the batched fetch
 * every 60 s while RESUMED.
 *
 * [Loaded] carries the next service's [routeBadge] and [routeName] so the favourites row can
 * render which line is *actually* next — the headline issue-#137 affordance. Multi-route
 * destinations like "City" rotate the badge as routes come up; single-route destinations always
 * show the same badge.
 */
sealed interface NextDepartureState {
    data object Loading : NextDepartureState

    data object Empty : NextDepartureState

    data class Loaded(
        val relativeLabel: String,
        val scheduledUtc: Instant,
        val estimatedUtc: Instant?,
        val routeBadge: String,
        val routeName: String,
    ) : NextDepartureState

    data object Error : NextDepartureState
}

/**
 * Bookkeeping for swipe-to-delete with undo.
 */
data class PendingUndo(
    val row: FavouriteRow,
    val originalPosition: Int,
)
