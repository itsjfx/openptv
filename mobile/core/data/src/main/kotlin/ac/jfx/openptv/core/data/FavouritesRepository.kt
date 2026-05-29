package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the user's favourited destinations. The favourite unit is a
 * destination at a stop — see [FavouriteDestinationAtStop] for why.
 *
 * All writes are `suspend`; reads are `Flow` so the UI updates without re-querying. Errors are
 * not modelled — the underlying DAO surfaces `SQLiteException` on the same `suspend` call, which
 * the caller surfaces upstream as it sees fit.
 */
interface FavouritesRepository {
    /**
     * Live stream of every favourite, ordered by `position` ascending — the favourites screen
     * renders directly off the emission without re-sorting.
     */
    fun observe(): Flow<List<FavouriteDestinationAtStop>>

    /**
     * Insert (or refresh) a favourite. Display fields are the source of truth at insertion
     * time — re-favouriting refreshes them.
     *
     * `position` is assigned by the repository to `max(position) + 1` so new favourites land at
     * the tail. `addedAt` is captured via the repository's injected `Clock`.
     */
    @Suppress("LongParameterList")
    suspend fun add(
        stopId: StopId,
        destinationKey: String,
        routeType: RouteType,
        stopName: String,
        stopSuburb: String,
        destinationName: String,
        lat: Double,
        lng: Double,
    )

    /** Delete by composite key. No-op if the row doesn't exist. */
    suspend fun remove(
        stopId: StopId,
        destinationKey: String,
    )

    /**
     * Re-number `position` to match the order of [orderedKeys]. Each pair is
     * `(stopId, destinationKey)`; its list index becomes the new `position`.
     */
    suspend fun reorder(orderedKeys: List<Pair<Int, String>>)

    /**
     * Reactive `(stopId, destinationKey) ∈ favourites?`. Derived from [observe] in-memory rather
     * than a second DAO query: Room already conflates the flow on row identity and the per-key
     * filter is cheap (favourites lists are small).
     */
    fun isFavourite(
        stopId: StopId,
        destinationKey: String,
    ): Flow<Boolean>
}
