package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.RunPattern
import ac.jfx.openptv.core.model.RunRef
import ac.jfx.openptv.core.network.model.toDomain
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * Retrofit-backed [RunPatternDataSource]. URL composition mirrors the other data sources in this
 * module: absolute URL from [PtvUrlResolver] (proxy or signed-direct-PTV picked per call).
 *
 * The `expand` set is `Stop,Route,Direction` — enough for the mapper to resolve stop display
 * names plus the run-level route / direction labels without dragging the full `expand=All`
 * payload (which sideloads run vehicle descriptors and geopath we don't render) over the wire.
 *
 * `date_utc` is forwarded only when the caller supplies one (same `Instant.toString()` shape as
 * [RetrofitDepartureDataSource]). Omitted, the endpoint defaults to "the pattern for this run
 * today", which includes the stops the service has already called at — exactly what issue #132
 * wants (past stops dimmed, future stops live). The journey planner passes the candidate's
 * departure instant so a next-day run resolves *that* day's times, not today's (issue #211,
 * verified live: `pattern/run/39889/route_type/0` returned today's times until `date_utc`
 * selected tomorrow's instance).
 */
internal class RetrofitRunPatternDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val urlResolver: PtvUrlResolver,
    ) : RunPatternDataSource {
        override suspend fun getRunPattern(
            runRef: RunRef,
            routeType: RouteType,
            dateUtc: Instant?,
        ): RunPattern {
            val typeCode = routeType.toPtvCode()
            val dateParam = dateUtc?.let { "&date_utc=$it" }.orEmpty()
            val path =
                "pattern/run/${runRef.value}/route_type/$typeCode" +
                    "?expand=Stop&expand=Route&expand=Direction" + dateParam
            return api.getRunPattern(urlResolver.resolve(path)).toDomain()
        }
    }
