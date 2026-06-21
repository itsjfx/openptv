package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.data.DepartureRepository.Companion.INITIAL_PAGE_SIZE_PER_ROUTE
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.DeparturesAtStop
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.DepartureDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Default impl. The one-shot path mirrors [StopSearchRepositoryImpl]; the polling Flow is the
 * interesting bit:
 *
 * ```
 * collector.subscribe
 *   │
 *   ▼
 *   emit Result.Loading
 *   │
 *   ▼
 *   fetch ── success ──► emit Result.Success(list)
 *     │
 *     └── failure ─────► emit Result.Error(throwable)
 *   │
 *   ▼
 *   delay(POLL_INTERVAL)
 *   │
 *   ▼
 *   (loop)
 *   │
 *   ▼ collector cancels
 *   delay / fetch cancellation propagates; the flow{} body exits cleanly.
 * ```
 *
 * Cancellation contract: `flow {}` runs in the collector's coroutine, so when the caller's
 * scope is cancelled (e.g. `repeatOnLifecycle` tearing down) the in-flight `delay` /
 * suspending `fetch` cancels and the loop exits. No `WhileSubscribed` `stateIn` is needed
 * here — the caller owns lifecycle semantics. Tests pin this with virtual time.
 *
 * Polling cadence is 30 s, matching the phase doc ("don't poll faster — wastes battery and
 * your PTV quota; backend caches at 15 s anyway"). It's a `private const val` so a future ADR
 * can adjust it in one place.
 */
internal class DepartureRepositoryImpl
    @Inject
    constructor(
        private val dataSource: DepartureDataSource,
        private val clock: Clock,
    ) : DepartureRepository {
        @Suppress("TooGenericExceptionCaught")
        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
            at: Instant?,
        ): Result<DeparturesAtStop> =
            try {
                Result.Success(
                    dataSource.getDepartures(
                        stopId = stopId,
                        routeType = routeType,
                        dateUtc = anchorFor(at),
                        // PTV quirk discovered while testing issue #86: `look_backwards=false`
                        // only excludes already-departed entries when `max_results` is also set.
                        // Without it, the response is still anchored at start-of-day. The
                        // favourites screen used to surface that bug as "departed 00:01" rows.
                        // Send the same per-route page size as the head poll so the contract is
                        // identical across the one-shot and streamed paths.
                        maxResults = INITIAL_PAGE_SIZE_PER_ROUTE,
                        lookBackwards = false,
                    ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        override fun observeDepartures(
            stopId: StopId,
            routeType: RouteType,
            at: Instant?,
        ): Flow<Result<List<Departure>>> =
            flow {
                while (true) {
                    emit(Result.Loading)
                    emit(fetchOnce(stopId, routeType, at))
                    delay(POLL_INTERVAL)
                }
            }

        @Suppress("TooGenericExceptionCaught")
        override suspend fun loadMore(
            stopId: StopId,
            routeType: RouteType,
            after: Instant,
            maxResults: Int,
        ): Result<List<Departure>> =
            try {
                Result.Success(
                    dataSource.getDepartures(
                        stopId = stopId,
                        routeType = routeType,
                        dateUtc = after,
                        maxResults = maxResults,
                        lookBackwards = false,
                    ).departures,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun fetchOnce(
            stopId: StopId,
            routeType: RouteType,
            at: Instant?,
        ): Result<List<Departure>> =
            try {
                Result.Success(
                    dataSource.getDepartures(
                        stopId = stopId,
                        routeType = routeType,
                        dateUtc = anchorFor(at),
                        maxResults = INITIAL_PAGE_SIZE_PER_ROUTE,
                        lookBackwards = false,
                    ).departures,
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        /**
         * Resolve the `date_utc` anchor for a fetch. A custom [at] (issue #182) is passed through
         * verbatim — the user picked an exact instant to look around, and `look_backwards=false`
         * already trims anything earlier than it, so no grace subtraction applies. The live "now"
         * path ([at] == null) keeps the 2-minute grace so a row whose scheduled time just slipped
         * into the past but is still tracking upcoming survives PTV's server-side filter.
         */
        private fun anchorFor(at: Instant?): Instant = at ?: (clock.now() - NOW_GRACE)

        private companion object {
            private val POLL_INTERVAL: Duration = 30.seconds

            /**
             * Anchor `date_utc` slightly behind "now" so a row whose scheduled time is a few
             * seconds in the past — but whose live `estimated` is still upcoming — survives PTV's
             * server-side filter. Matches the `RelativeTimeFormatter` "now" window (±2 min), which
             * the UI used to apply client-side before issue #86. Without this grace, a row that
             * would have rendered as "now" could disappear during the second the scheduled time
             * slips into the past.
             */
            private val NOW_GRACE: Duration = 2.minutes
        }
    }
