package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.model.toDomain
import kotlinx.datetime.Instant
import javax.inject.Inject

/**
 * Retrofit-backed [DepartureDataSource]. URL composition mirrors the other data sources in this
 * module: base URL from [BackendUrlProvider], absolute URL via `@Url`.
 *
 * Per the phase doc the picked `expand` set is `Run,Direction,Route,Disruption` — enough for the
 * mapper to resolve direction names client-side without dragging the full PTV response shape
 * through the wire.
 *
 * `date_utc`, `max_results`, and `look_backwards` are forwarded verbatim when callers supply them.
 * PTV expects `date_utc` as ISO-8601 without a trailing fractional second; `Instant.toString()`
 * already emits the right shape (e.g. `2026-05-14T09:00:00Z`). `look_backwards=false` paired with
 * `date_utc=<now>` is how the data layer asks PTV to do the "only upcoming departures" filter
 * server-side (issue #86) instead of returning the full from-midnight window and filtering on the
 * client.
 */
internal class RetrofitDepartureDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val backendUrl: BackendUrlProvider,
    ) : DepartureDataSource {
        override suspend fun getDepartures(
            stopId: StopId,
            routeType: RouteType,
            dateUtc: Instant?,
            maxResults: Int?,
            lookBackwards: Boolean?,
        ): List<Departure> {
            val base = backendUrl.backendBaseUrl()
            val typeCode = routeType.toPtvCode()
            val baseUrl =
                "${base}departures/route_type/$typeCode/stop/${stopId.value}" +
                    "?expand=Run&expand=Direction&expand=Route&expand=Disruption"
            val dateParam = dateUtc?.let { "&date_utc=$it" }.orEmpty()
            val maxParam = maxResults?.let { "&max_results=$it" }.orEmpty()
            val lookBackwardsParam = lookBackwards?.let { "&look_backwards=$it" }.orEmpty()
            return api.getDepartures("$baseUrl$dateParam$maxParam$lookBackwardsParam").toDomain()
        }
    }
