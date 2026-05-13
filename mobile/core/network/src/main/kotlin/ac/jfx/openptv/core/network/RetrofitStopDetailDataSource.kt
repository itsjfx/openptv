package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId
import ac.jfx.openptv.core.network.model.toDomain
import javax.inject.Inject

/**
 * Retrofit-backed [StopDetailDataSource]. URL composition mirrors [RetrofitStopSearchDataSource]:
 * the base URL comes from the injected [BackendUrlProvider] (production reads
 * `SettingsRepository`, tests pass a lambda), and the absolute URL goes to Retrofit via `@Url`.
 *
 * The query string asks PTV to expand the location block plus the stop's direct disruption list
 * — the screen header surfaces both. `getStop` doesn't need `expand=Run` (that's a departures-
 * level concern).
 */
internal class RetrofitStopDetailDataSource
    @Inject
    constructor(
        private val api: BackendApiService,
        private val backendUrl: BackendUrlProvider,
    ) : StopDetailDataSource {
        override suspend fun getStopDetail(
            stopId: StopId,
            routeType: RouteType,
        ): StopDetail? {
            val base = backendUrl.backendBaseUrl()
            val typeCode = routeType.toPtvCode()
            val url = "${base}stops/${stopId.value}/route_type/$typeCode?stop_location=true&stop_disruptions=true"
            return api.getStop(url).toDomain()
        }
    }
