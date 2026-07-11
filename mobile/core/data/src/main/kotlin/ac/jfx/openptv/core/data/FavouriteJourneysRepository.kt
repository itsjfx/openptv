package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow

/**
 * Persistence contract for the user's favourited journeys (issue #209). The favourite unit is
 * the **ordered** origin→destination stop pair — A→B and B→A are distinct favourites, see
 * [FavouriteJourney].
 *
 * All writes are `suspend`; reads are `Flow` so the UI updates without re-querying — the same
 * shape as [FavouritesRepository]. Errors are not modelled: the underlying DAO surfaces
 * `SQLiteException` on the same `suspend` call, which the caller surfaces upstream as it sees
 * fit.
 */
interface FavouriteJourneysRepository {
    /** Live stream of every journey favourite, ordered by `addedAt` ascending. */
    fun observe(): Flow<List<FavouriteJourney>>

    /**
     * Star/unstar the pair in one call — the planner's ★ is a toggle, so the repository owns
     * the "does it exist yet" check rather than every caller racing its own read. Re-toggling
     * an existing pair removes it; toggling a new pair inserts it with the repository clock's
     * now as `addedAt`. Display fields are captured from the passed [Stop]s at insertion time.
     */
    suspend fun toggle(
        origin: Stop,
        destination: Stop,
    )

    /**
     * Reactive `(origin, destination) ∈ favourites?` for the planner's ★ state. Derived from
     * [observe] in-memory rather than a second DAO query — same trade as
     * [FavouritesRepository.isFavourite]: the list is small and `distinctUntilChanged` collapses
     * no-op ticks.
     */
    fun isFavourite(
        originStopId: StopId,
        destinationStopId: StopId,
    ): Flow<Boolean>
}
