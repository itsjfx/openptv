package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.FavouritesRepository
import ac.jfx.openptv.core.model.FavouriteDestinationAtStop
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
 * real repository's contract is covered by `FavouritesRepositoryImplTest` in `:core:data`.
 *
 * `@Singleton` so a `setUp()` mutation lands on the same instance the ViewModel ends up
 * collecting. `seed(...)` pre-populates the state in one call — `add(...)` adds at the tail,
 * `remove(...)` filters out the matching pair, `reorder(...)` permutes by position.
 *
 * The `addedAt` instant on newly-added favourites comes from a public, swappable [Clock] so tests
 * that care about ordering by recency can pin the value deterministically. Default is
 * `Clock.System`.
 */
@Singleton
class FakeFavouritesRepository
    @Inject
    constructor() : FavouritesRepository {
        private val state: MutableStateFlow<List<FavouriteDestinationAtStop>> = MutableStateFlow(emptyList())

        var clock: Clock = Clock.System

        /** Replace the entire state in one call. Useful for `@Before`-style seeding. */
        fun seed(favourites: List<FavouriteDestinationAtStop>) {
            state.value = favourites
        }

        /** Read-only view of the current state — for assertions. */
        val current: List<FavouriteDestinationAtStop>
            get() = state.value

        override fun observe(): Flow<List<FavouriteDestinationAtStop>> = state

        @Suppress("LongParameterList")
        override suspend fun add(
            stopId: StopId,
            destinationKey: String,
            routeType: RouteType,
            stopName: String,
            stopSuburb: String,
            destinationName: String,
            lat: Double,
            lng: Double,
        ) {
            state.update { current ->
                // Replace existing same-key row (matches Room's @Upsert semantics).
                val withoutSameKey =
                    current.filterNot {
                        it.stopId == stopId && it.destinationKey == destinationKey
                    }
                val nextPosition = (withoutSameKey.maxOfOrNull { it.position } ?: -1) + 1
                withoutSameKey +
                    FavouriteDestinationAtStop(
                        stopId = stopId,
                        destinationKey = destinationKey,
                        routeType = routeType,
                        stopName = stopName,
                        stopSuburb = stopSuburb,
                        destinationName = destinationName,
                        lat = lat,
                        lng = lng,
                        position = nextPosition,
                        addedAt = clock.now(),
                    )
            }
        }

        override suspend fun remove(
            stopId: StopId,
            destinationKey: String,
        ) {
            state.update { current ->
                current.filterNot { it.stopId == stopId && it.destinationKey == destinationKey }
            }
        }

        override suspend fun reorder(orderedKeys: List<Pair<Int, String>>) {
            state.update { current ->
                val indexOf =
                    orderedKeys
                        .withIndex()
                        .associate { (index, pair) -> pair to index }
                current
                    .map { fav ->
                        val key = fav.stopId.value to fav.destinationKey
                        val newPosition = indexOf[key] ?: fav.position
                        fav.copy(position = newPosition)
                    }
                    .sortedBy { it.position }
            }
        }

        override fun isFavourite(
            stopId: StopId,
            destinationKey: String,
        ): Flow<Boolean> =
            state
                .map { current ->
                    current.any { it.stopId == stopId && it.destinationKey == destinationKey }
                }
                .distinctUntilChanged()
    }
