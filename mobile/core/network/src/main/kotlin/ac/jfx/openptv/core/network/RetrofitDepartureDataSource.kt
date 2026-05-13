package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.Departure
import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.model.toDomain
import javax.inject.Inject

/**
 * Retrofit-backed [DepartureDataSource]. URL composition mirrors the other data sources in this
 * module: base URL from [BackendUrlProvider], absolute URL via `@Url`.
 *
 * Per the phase doc the picked `expand` set is `Run,Direction,Route,Disruption` — enough for the
 * mapper to resolve direction names client-side without dragging the full PTV response shape
 * through the wire.
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
        ): List<Departure> {
            val base = backendUrl.backendBaseUrl()
            val typeCode = routeType.toPtvCode()
            val url =
                "${base}departures/route_type/$typeCode/stop/${stopId.value}?expand=Run&expand=Direction&expand=Route&expand=Disruption"
            return api.getDepartures(url).toDomain()
        }
    }
