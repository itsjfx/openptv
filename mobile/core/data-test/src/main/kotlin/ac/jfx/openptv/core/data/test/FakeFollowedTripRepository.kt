package ac.jfx.openptv.core.data.test

import ac.jfx.openptv.core.data.FollowedTripRepository
import ac.jfx.openptv.core.model.FollowedTrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-written fake for [FollowedTripRepository] backed by an in-memory [MutableStateFlow].
 * The real DataStore-backed contract is covered by `FollowedTripRepositoryImplTest` in
 * `:core:data`; this fake gives ViewModel unit tests and feature androidTests the same
 * observable semantics without disk.
 *
 * `@Singleton` so a `seed(...)` in `setUp()` lands on the instance the ViewModel collects.
 */
@Singleton
class FakeFollowedTripRepository
    @Inject
    constructor() : FollowedTripRepository {
        private val state: MutableStateFlow<FollowedTrip?> = MutableStateFlow(null)

        /** Pre-populate the followed trip (or reset to nothing-followed with `null`). */
        fun seed(trip: FollowedTrip?) {
            state.value = trip
        }

        /** Read-only view of the current state — for assertions. */
        val current: FollowedTrip?
            get() = state.value

        override val followedTrip: Flow<FollowedTrip?> = state

        override suspend fun follow(trip: FollowedTrip) {
            state.value = trip
        }

        override suspend fun unfollow() {
            state.value = null
        }
    }
