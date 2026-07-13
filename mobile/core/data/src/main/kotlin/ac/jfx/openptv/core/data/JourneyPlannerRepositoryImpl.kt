package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.JourneyOption
import ac.jfx.openptv.core.model.Route
import ac.jfx.openptv.core.model.RouteId
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.model.Stop
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.DepartureDataSource
import ac.jfx.openptv.core.network.RouteStopsDataSource
import ac.jfx.openptv.core.network.RunPatternDataSource
import ac.jfx.openptv.core.network.StopDetailDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Default impl. PTV has no A→B endpoint, so a direct journey is *derived* (issue #204, algorithm
 * validated live against the proxy on 2026-07-11):
 *
 * ```
 * departures(origin)  ────┐
 * departures(dest)    ────┤ 1. candidates = origin departures on a route that also serves dest
 * stopDetail(dest)    ────┘    (dest's serving routes, cached — a stop's routes are static-ish)
 *                          2. join candidate → dest departures by run_ref: a run listed at both
 *                             stops with the dest time later IS a direct service, and the dest
 *                             entry carries the arrival (scheduled + estimated) for free
 *                          3. unjoined candidates might still be direct — a run that TERMINATES
 *                             at the destination never appears in its departures feed (verified
 *                             live at Lilydale). Before spending a pattern fetch, check direction
 *                             of travel via stop sequences (cached): only a candidate whose
 *                             origin precedes the destination along its direction is worth it
 *                          4. pattern fetch settles the stragglers: destination present after
 *                             the origin → direct (arrival from the pattern row); absent →
 *                             express skip or wrong branch → dropped
 * ```
 *
 * Why not sequences alone (skip step 4): express runs skip stops mid-route — sequence order says
 * "towards", not "calls at" (`express_stop_count > 0` exists on the Richmond→Burnley corridor).
 * Why not patterns alone (skip steps 2–3): that's a pattern fetch per candidate per 30 s tick;
 * the join answers almost all candidates with the two departures calls the tick already makes.
 *
 * Day boundaries (issue #211): a `run_ref` names a timetable slot, not a calendar day's instance,
 * so both resolution paths can cross days — the pattern endpoint defaults to *today's* instance
 * (a next-day candidate then "arrives" ~24 h before it departs), and the two departures feeds
 * have different window depths so around midnight they can hold different days' instances of the
 * same ref. The pattern fetch is therefore anchored to the candidate's departure via `date_utc`,
 * and both paths only accept arrivals inside `(departure, departure + MAX_PLAUSIBLE_JOURNEY]`.
 *
 * Failure posture inside one derivation: the three head fetches failing fails the whole fetch
 * ([Result.Error], next tick recovers). A per-candidate sequence fetch failing falls through to
 * the pattern check (worst case: one extra fetch); a per-candidate pattern fetch failing drops
 * that candidate for this tick rather than erroring a list that's otherwise fine.
 *
 * Caches ([servingRouteIdsCache], [sequenceCache]) are in-memory, unbounded, and live for the
 * process: route membership and stop ordering change on timetable updates, not mid-session. A
 * concurrent first-poll may double-fetch a key; last write wins, both writes are equal.
 */
internal class JourneyPlannerRepositoryImpl
    @Inject
    constructor(
        private val departureDataSource: DepartureDataSource,
        private val stopDetailDataSource: StopDetailDataSource,
        private val routeStopsDataSource: RouteStopsDataSource,
        private val runPatternDataSource: RunPatternDataSource,
        private val clock: Clock,
    ) : JourneyPlannerRepository {
        private val servingRouteIdsCache = ConcurrentHashMap<StopKey, Set<RouteId>>()
        private val sequenceCache = ConcurrentHashMap<SequenceKey, Map<StopId, Int>>()

        @Suppress("TooGenericExceptionCaught")
        override suspend fun getJourneys(
            origin: Stop,
            destination: Stop,
            at: Instant?,
        ): Result<List<JourneyOption>> =
            try {
                Result.Success(deriveJourneys(origin, destination, at))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        override fun observeJourneys(
            origin: Stop,
            destination: Stop,
            at: Instant?,
        ): Flow<Result<List<JourneyOption>>> =
            flow {
                while (true) {
                    emit(Result.Loading)
                    emit(getJourneys(origin, destination, at))
                    delay(POLL_INTERVAL)
                }
            }

        private suspend fun deriveJourneys(
            origin: Stop,
            destination: Stop,
            at: Instant?,
        ): List<JourneyOption> {
            // A PTV route belongs to exactly one route_type, so stops of different modes can
            // never share one — answer the cross-mode case without a network call.
            if (origin.routeType != destination.routeType) return emptyList()
            if (origin.id == destination.id) return emptyList()

            val anchor = at ?: (clock.now() - NOW_GRACE)
            return coroutineScope {
                val originDepartures =
                    async {
                        departureDataSource.getDepartures(
                            stopId = origin.id,
                            routeType = origin.routeType,
                            dateUtc = anchor,
                            maxResults = ORIGIN_RESULTS_PER_ROUTE,
                            lookBackwards = false,
                        )
                    }
                // Wider page than the origin: a run departing the origin late in its window
                // reaches the destination later still, so the join needs the destination feed
                // to extend past the origin's. Same single call either way.
                val destinationDepartures =
                    async {
                        departureDataSource.getDepartures(
                            stopId = destination.id,
                            routeType = destination.routeType,
                            dateUtc = anchor,
                            maxResults = DESTINATION_RESULTS_PER_ROUTE,
                            lookBackwards = false,
                        )
                    }
                val destinationRouteIds = async { servingRouteIds(destination) }

                val atOrigin = originDepartures.await()
                val destByRun = destinationDepartures.await().departures.groupBy { it.runRef }
                val sharedRouteIds = destinationRouteIds.await()

                val routesById = atOrigin.routes.associateBy { it.id }
                atOrigin.departures
                    .filter { it.routeId in sharedRouteIds }
                    .map { candidate ->
                        async { resolveCandidate(candidate, origin, destination, destByRun, routesById) }
                    }
                    .awaitAll()
                    .filterNotNull()
                    .sortedBy { it.effectiveDepartureUtc }
            }
        }

        /**
         * Decide whether one origin departure is a direct journey, and if so with which arrival
         * times. Join first (free), then sequence-gate the pattern fetch (cached), then pattern.
         */
        private suspend fun resolveCandidate(
            candidate: Departure,
            origin: Stop,
            destination: Stop,
            destByRun: Map<RunRef, List<Departure>>,
            routesById: Map<RouteId, Route>,
        ): JourneyOption? {
            val route = routesById[candidate.routeId] ?: fallbackRoute(candidate.routeId, origin.routeType)

            // "Later than the departure" alone would also match a *different day's* instance of
            // the ref (~24 h out) — reject those here so the candidate falls through to the
            // date-anchored pattern check instead of rendering an absurd duration.
            val joined =
                destByRun[candidate.runRef]
                    ?.firstOrNull { isPlausibleArrival(candidate, it.scheduledDepartureUtc) }
            if (joined != null) {
                return candidate.toJourneyOption(
                    route = route,
                    scheduledArrivalUtc = joined.scheduledDepartureUtc,
                    estimatedArrivalUtc = joined.estimatedDepartureUtc,
                )
            }

            if (!headsTowardsDestination(candidate, origin, destination)) return null
            return patternArrival(candidate, origin, destination, route)
        }

        /**
         * True when this candidate's direction of travel passes the origin before the
         * destination — the cheap gate that stops wrong-way departures from costing a pattern
         * fetch each tick. Unknown (fetch failed) leans true so the pattern check, which is
         * authoritative, gets the final say.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun headsTowardsDestination(
            candidate: Departure,
            origin: Stop,
            destination: Stop,
        ): Boolean {
            val key = SequenceKey(candidate.routeId, origin.routeType, candidate.direction.id.value)
            val sequences =
                sequenceCache[key] ?: try {
                    routeStopsDataSource
                        .getStopSequences(candidate.routeId, origin.routeType, candidate.direction.id)
                        .also { sequenceCache[key] = it }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return true
                }
            val originSeq = sequences[origin.id] ?: return false
            val destinationSeq = sequences[destination.id] ?: return false
            return originSeq < destinationSeq
        }

        /**
         * The authoritative per-run answer: fetch the stopping pattern and look for the
         * destination *after* the origin. Handles runs that terminate at the destination (absent
         * from its departures feed) and rules out express runs that sail past it. A pattern
         * fetch failing drops this candidate for the tick instead of erroring the whole list.
         *
         * The fetch is anchored to the candidate's scheduled departure: timetable `run_ref`s
         * recur daily and the endpoint defaults to today's instance, so a next-day candidate
         * would otherwise join yesterday-relative times (issue #211's "-1436 min journey"). The
         * plausibility check stays as the backstop for refs the API won't re-anchor.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun patternArrival(
            candidate: Departure,
            origin: Stop,
            destination: Stop,
            route: Route,
        ): JourneyOption? {
            val pattern =
                try {
                    runPatternDataSource.getRunPattern(
                        candidate.runRef,
                        origin.routeType,
                        dateUtc = candidate.scheduledDepartureUtc,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    return null
                }
            val originIndex = pattern.stops.indexOfFirst { it.stopId == origin.id }
            if (originIndex < 0) return null
            val arrival =
                pattern.stops
                    .drop(originIndex + 1)
                    .firstOrNull { it.stopId == destination.id }
                    ?.takeIf { isPlausibleArrival(candidate, it.scheduledDepartureUtc) }
                    ?: return null
            return candidate.toJourneyOption(
                route = route,
                scheduledArrivalUtc = arrival.scheduledDepartureUtc,
                estimatedArrivalUtc = arrival.estimatedDepartureUtc,
            )
        }

        /**
         * True when [arrival] can belong to the candidate's own service instance: strictly after
         * the scheduled departure and within [MAX_PLAUSIBLE_JOURNEY]. Anything outside that band
         * is a different calendar day's instance of the same `run_ref` (issue #211) — never a
         * journey to offer.
         */
        private fun isPlausibleArrival(
            candidate: Departure,
            arrival: Instant,
        ): Boolean {
            val duration = arrival - candidate.scheduledDepartureUtc
            return duration > Duration.ZERO && duration <= MAX_PLAUSIBLE_JOURNEY
        }

        private suspend fun servingRouteIds(stop: Stop): Set<RouteId> {
            val key = StopKey(stop.id, stop.routeType)
            servingRouteIdsCache[key]?.let { return it }
            val detail = stopDetailDataSource.getStopDetail(stop.id, stop.routeType) ?: return emptySet()
            return detail.servingRoutes
                .map { it.id }
                .toSet()
                .also { servingRouteIdsCache[key] = it }
        }

        /**
         * PTV occasionally omits a route from the departures sideload; [routeDisplayLabel] then
         * falls back to `#<routeId>` via this stub so the option still renders and sorts.
         */
        private fun fallbackRoute(
            routeId: RouteId,
            routeType: RouteType,
        ): Route = Route(id = routeId, number = "", name = "", routeType = routeType)

        private fun Departure.toJourneyOption(
            route: Route,
            scheduledArrivalUtc: Instant,
            estimatedArrivalUtc: Instant?,
        ): JourneyOption =
            JourneyOption(
                route = route,
                direction = direction,
                runRef = runRef,
                scheduledDepartureUtc = scheduledDepartureUtc,
                estimatedDepartureUtc = estimatedDepartureUtc,
                departurePlatform = platform,
                scheduledArrivalUtc = scheduledArrivalUtc,
                estimatedArrivalUtc = estimatedArrivalUtc,
                disruptions = disruptions,
            )

        private data class StopKey(val stopId: StopId, val routeType: RouteType)

        private data class SequenceKey(val routeId: RouteId, val routeType: RouteType, val directionId: Int)

        private companion object {
            private val POLL_INTERVAL: Duration = 30.seconds

            /** Same rationale as `DepartureRepositoryImpl.NOW_GRACE` — keep "now" rows alive. */
            private val NOW_GRACE: Duration = 2.minutes

            /** Journey candidates per route at the origin — enough for a useful list per line. */
            private const val ORIGIN_RESULTS_PER_ROUTE: Int = 4

            /**
             * Destination feed page. Deliberately deeper than the origin's so the run_ref join
             * covers the travel-time offset; a miss is only a pattern fetch, not a wrong answer.
             */
            private const val DESTINATION_RESULTS_PER_ROUTE: Int = 12

            /**
             * Longest believable direct journey. The longest direct V/Line runs are ~4.5 h
             * (Bairnsdale — Melbourne), so 6 h clears every real service with slack while
             * sitting far below the ~24 h error a wrong-day instance produces.
             */
            private val MAX_PLAUSIBLE_JOURNEY: Duration = 6.hours
        }
    }
