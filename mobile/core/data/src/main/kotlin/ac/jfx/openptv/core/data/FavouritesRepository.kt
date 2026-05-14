package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the user's favourited services. The favourite unit is a route at a
 * stop — see [FavouriteRouteAtStop] for why.
 *
 * All write operations are `suspend`; reads are `Flow` so the UI updates without re-querying.
 * Errors are not modelled here — the underlying DAO surfaces `SQLiteException` on the same
 * `suspend` call, which the caller surfaces upstream as it sees fit. This matches `:core:data`'s
 * convention for the database-backed repositories that ship later (notifications, disruptions),
 * and keeps the surface narrow enough to fake convincingly in `:core:data-test`.
 */
interface FavouritesRepository {
    /**
     * Live stream of every favourite, ordered by `position` ascending — so the favourites screen
     * (issue #35) renders directly off the emission without re-sorting.
     */
    fun observe(): Flow<List<FavouriteRouteAtStop>>

    /**
     * Insert (or refresh) a favourite. The display fields (`stopName`, `stopSuburb`, etc.) are
     * the source of truth at insertion time — if the upstream PTV data has changed (a stop is
     * renamed, a route name is updated), the next call re-cachess them.
     *
     * `position` is assigned by the repository to `max(position) + 1` over the current set, so
     * newly-added favourites land at the tail of the list. `addedAt` is the wall-clock instant
     * the call lands, captured via the repository's injected `Clock`.
     */
    @Suppress("LongParameterList")
    suspend fun add(
        stopId: StopId,
        routeType: RouteType,
        routeId: RouteId,
        directionId: DirectionId,
        stopName: String,
        stopSuburb: String,
        routeNumber: String,
        routeName: String,
        directionName: String,
        lat: Double,
        lng: Double,
    )

    /** Delete by composite key. No-op if the row doesn't exist. */
    suspend fun remove(
        stopId: StopId,
        routeId: RouteId,
        directionId: DirectionId,
    )

    /**
     * Re-number `position` to match the order of [orderedIds]. The triple is
     * `(stopId, routeId, directionId)` — each triple's list-index becomes its new `position`.
     */
    suspend fun reorder(orderedIds: List<Triple<Int, Int, Int>>)

    /**
     * Reactive `(stopId, routeId, directionId) ∈ favourites?`. Derived from [observe] in-memory
     * rather than a second DAO query, because Room already conflates the flow on row identity
     * and the per-key filter is cheap (favourites lists are small).
     */
    fun isFavourite(
        stopId: StopId,
        routeId: RouteId,
        directionId: DirectionId,
    ): Flow<Boolean>
}
