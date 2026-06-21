package ac.jfx.openptv.core.data

import ac.jfx.openptv.core.common.Result
import ac.jfx.openptv.core.model.RouteShape
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.RouteShapeDataSource
import ac.jfx.openptv.core.network.RunPatternDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Default impl. Same polling shape as [DepartureRepositoryImpl]: a cold `flow {}` that emits
 * `Loading` → fetch result → `delay(POLL_INTERVAL)` → loop, running in the collector's coroutine
 * so cancellation (the screen leaving RESUMED) tears the loop down cleanly. An error mid-poll
 * surfaces as [Result.Error] without breaking the loop — the next tick can recover.
 *
 * 30 s cadence matches the departures poll per the phase doc ("don't poll faster — wastes
 * battery and your PTV quota; backend caches at 15 s anyway").
 *
 * **Geopath enrichment (issue #187).** The pattern endpoint returns `geopath: null` even with
 * `include_geopath=true`, so the line is fetched from the companion `stops/route` endpoint via
 * [RouteShapeDataSource] and joined onto each emission: the matching-direction polyline goes into
 * [RunPattern.geopath] and each stop's coordinates fill in [`RunPatternStop.coordinates`]. The
 * route shape is geometry that doesn't change between polls, so it's fetched once per collection
 * and cached for the flow's lifetime — the 30 s poll only refreshes the live times. A failed (or
 * empty) shape fetch is swallowed: the pattern still emits `Success` with an empty geopath and the
 * map degrades gracefully, because the text timeline must never break just because the line is
 * unavailable.
 */
internal class RunPatternRepositoryImpl
    @Inject
    constructor(
        private val dataSource: RunPatternDataSource,
        private val routeShapeDataSource: RouteShapeDataSource,
    ) : RunPatternRepository {
        override fun observeRunPattern(
            runRef: RunRef,
            routeType: RouteType,
        ): Flow<Result<RunPattern>> =
            flow {
                // Fetched lazily on the first successful pattern (we need its route_id) and reused
                // for the rest of the collection — the route geometry is static between polls.
                var cachedShape: RouteShape? = null
                while (true) {
                    emit(Result.Loading)
                    val result = fetchOnce(runRef, routeType)
                    if (result is Result.Success) {
                        val pattern = result.data
                        if (cachedShape == null) {
                            cachedShape = fetchRouteShape(pattern, routeType)
                        }
                        emit(Result.Success(pattern.withGeometry(cachedShape)))
                    } else {
                        emit(result)
                    }
                    delay(POLL_INTERVAL)
                }
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun fetchOnce(
            runRef: RunRef,
            routeType: RouteType,
        ): Result<RunPattern> =
            try {
                Result.Success(dataSource.getRunPattern(runRef, routeType))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Result.Error(t)
            }

        /**
         * Best-effort geopath fetch. Returns [RouteShape.EMPTY] (not an error) when the run has no
         * resolvable `route_id` or the companion call fails — the map degrades to "stops only" /
         * hidden, the timeline is unaffected. [CancellationException] still propagates so the
         * collector tears down cleanly.
         *
         * `SwallowedException` is suppressed deliberately: the geopath is a non-critical
         * enhancement, so a failure fetching it must NOT surface as an error — swallowing it to
         * [RouteShape.EMPTY] is the whole point.
         */
        @Suppress("TooGenericExceptionCaught", "SwallowedException")
        private suspend fun fetchRouteShape(
            pattern: RunPattern,
            routeType: RouteType,
        ): RouteShape {
            val routeId = pattern.route?.id ?: return RouteShape.EMPTY
            return try {
                routeShapeDataSource.getRouteShape(routeId, routeType)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                RouteShape.EMPTY
            }
        }

        /**
         * Join the route geometry onto a pattern: the matching-direction polyline and each stop's
         * coordinates. A null/empty shape leaves the pattern's geopath empty and coordinates null,
         * which the map treats as "no line to draw".
         */
        private fun RunPattern.withGeometry(shape: RouteShape?): RunPattern {
            if (shape == null) return this
            val polyline = shape.geopathFor(directionId)
            return copy(
                geopath = polyline,
                stops =
                    stops.map { stop ->
                        val coord = shape.stopCoordinates[stop.stopId]
                        if (coord == null) stop else stop.copy(coordinates = coord)
                    },
            )
        }

        private companion object {
            private val POLL_INTERVAL: Duration = 30.seconds
        }
    }
