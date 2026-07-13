package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.FavouriteJourneysRepository
import ac.jfx.openptv.core.model.FavouriteJourney
import ac.jfx.openptv.core.model.Stop
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
 * Hand-written fake for [FavouriteJourneysRepository] (issue #209) backed by an in-memory
 * [MutableStateFlow] — same shape as [FakeFavouritesRepository]. The Room-backed contract is
 * covered by `FavouriteJourneysRepositoryImplTest` in `:core:data`.
 *
 * `@Singleton` so a `setUp()` mutation lands on the instance the ViewModel collects. `seed(...)`
 * pre-populates in one call; `clock` is swappable so `addedAt` ordering is deterministic.
 */
@Singleton
class FakeFavouriteJourneysRepository
    @Inject
    constructor() : FavouriteJourneysRepository {
        private val state: MutableStateFlow<List<FavouriteJourney>> = MutableStateFlow(emptyList())

        var clock: Clock = Clock.System

        /** Replace the entire state in one call — `@Before`-style seeding. */
        fun seed(favourites: List<FavouriteJourney>) {
            state.value = favourites
        }

        /** Read-only view of the current state — for assertions. */
        val current: List<FavouriteJourney>
            get() = state.value

        override fun observe(): Flow<List<FavouriteJourney>> = state

        override suspend fun toggle(
            origin: Stop,
            destination: Stop,
        ) {
            state.update { current ->
                val existing =
                    current.firstOrNull {
                        it.origin.id == origin.id && it.destination.id == destination.id
                    }
                if (existing != null) {
                    current - existing
                } else {
                    current +
                        FavouriteJourney(
                            origin = origin,
                            destination = destination,
                            addedAt = clock.now(),
                        )
                }
            }
        }

        override fun isFavourite(
            originStopId: StopId,
            destinationStopId: StopId,
        ): Flow<Boolean> =
            state
                .map { current ->
                    current.any {
                        it.origin.id == originStopId && it.destination.id == destinationStopId
                    }
                }
                .distinctUntilChanged()
    }
