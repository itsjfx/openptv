package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.DirectionId
import ac.jfx.openptv.core.model.FavouriteRouteAtStop
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [FavouritesRepository] backed by an in-memory [MutableStateFlow]. Behaves
 * close enough to the Room-backed impl for feature androidTests and ViewModel unit tests — the
 * real repository's contract (observe, add, remove, reorder, isFavourite) is covered by
 * `FavouritesRepositoryImplTest` in `:core:data`.
 *
 * `@Singleton` so a `setUp()` mutation lands on the same instance the ViewModel ends up
 * collecting. `seed(...)` pre-populates the state in one call — `add(...)` adds at the tail,
 * `remove(...)` filters out the matching triple, `reorder(...)` permutes by position.
 *
 * The `addedAt` instant on newly-added favourites comes from a public, swappable [Clock] so tests
 * that care about ordering by recency can pin the value deterministically. Default is
 * `Clock.System` which keeps the no-arg use convenient.
 */
@Singleton
class FakeFavouritesRepository
    @Inject
    constructor() : FavouritesRepository {
        private val state: MutableStateFlow<List<FavouriteRouteAtStop>> = MutableStateFlow(emptyList())

        /** Public so tests that care about deterministic `addedAt` can swap in a fixed clock. */
        var clock: Clock = Clock.System

        /** Replace the entire state in one call. Useful for `@Before`-style seeding. */
        fun seed(favourites: List<FavouriteRouteAtStop>) {
            state.value = favourites
        }

        /** Read-only view of the current state — for assertions. */
        val current: List<FavouriteRouteAtStop>
            get() = state.value

        override fun observe(): Flow<List<FavouriteRouteAtStop>> = state

        @Suppress("LongParameterList")
        override suspend fun add(
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
        ) {
            state.update { current ->
                // Replace existing same-key row (matches Room's @Upsert semantics) so the test
                // mirrors what production does on re-favouriting the same triple.
                val withoutSameKey =
                    current.filterNot {
                        it.stopId == stopId && it.routeId == routeId && it.directionId == directionId
                    }
                val nextPosition = (withoutSameKey.maxOfOrNull { it.position } ?: -1) + 1
                withoutSameKey +
                    FavouriteRouteAtStop(
                        stopId = stopId,
                        routeType = routeType,
                        routeId = routeId,
                        directionId = directionId,
                        stopName = stopName,
                        stopSuburb = stopSuburb,
                        routeNumber = routeNumber,
                        routeName = routeName,
                        directionName = directionName,
                        lat = lat,
                        lng = lng,
                        position = nextPosition,
                        addedAt = clock.now(),
                    )
            }
        }

        override suspend fun remove(
            stopId: StopId,
            routeId: RouteId,
            directionId: DirectionId,
        ) {
            state.update { current ->
                current.filterNot {
                    it.stopId == stopId && it.routeId == routeId && it.directionId == directionId
                }
            }
        }

        override suspend fun reorder(orderedIds: List<Triple<Int, Int, Int>>) {
            state.update { current ->
                val indexOf =
                    orderedIds
                        .withIndex()
                        .associate { (index, triple) -> triple to index }
                current
                    .map { fav ->
                        val key = Triple(fav.stopId.value, fav.routeId.value, fav.directionId.value)
                        val newPosition = indexOf[key] ?: fav.position
                        fav.copy(position = newPosition)
                    }
                    .sortedBy { it.position }
            }
        }

        override fun isFavourite(
            stopId: StopId,
            routeId: RouteId,
            directionId: DirectionId,
        ): Flow<Boolean> =
            state
                .map { current ->
                    current.any {
                        it.stopId == stopId && it.routeId == routeId && it.directionId == directionId
                    }
                }
                .distinctUntilChanged()
    }
