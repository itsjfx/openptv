package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.Stop
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.Instant

/**
 * Repository for direct journeys between two stops (issue #204). "Direct" means one boardable
 * run that calls at both stops in order — the PTV v3 API has no journey-planning endpoint, so
 * anything multi-leg is out of scope by design (see `docs/architecture.md`).
 *
 * Two surfaces, mirroring [DepartureRepository]:
 *
 *  - [getJourneys] — one-shot fetch. Used when the user pins a custom departure time (a pinned
 *    time is a static snapshot; polling it would re-fetch identical data).
 *  - [observeJourneys] — re-emits on a 30 s tick for the live "departing now" view, so estimated
 *    times track reality. Collector lifetime drives the polling loop, same contract as
 *    [DepartureRepository.observeDepartures].
 *
 * An empty [JourneyOption] list is the honest "no direct services" answer — including the
 * cross-mode case (a train stop and a tram stop never share a route) and the no-shared-route
 * case. The UI renders both as its empty state.
 *
 * All surfaces fold non-cancellation throwables into [Result.Error]; cancellation propagates.
 */
interface JourneyPlannerRepository {
    /**
     * @param at optional departure-time anchor. `null` means "departing now" (with the same
     *   2-minute grace window the departures feed uses); non-null anchors the search at that
     *   instant.
     */
    suspend fun getJourneys(
        origin: Stop,
        destination: Stop,
        at: Instant? = null,
    ): Result<List<JourneyOption>>

    /** Polling variant of [getJourneys]; emits `Result.Loading` before each fetch. */
    fun observeJourneys(
        origin: Stop,
        destination: Stop,
        at: Instant? = null,
    ): Flow<Result<List<JourneyOption>>>
}
