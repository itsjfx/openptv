package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.network.model.DeparturesResponseDto
import ac.jfx.openptv.core.network.model.SearchResponseDto
import ac.jfx.openptv.core.network.model.StopResponseDto
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Retrofit-bound view of the Go proxy. The base URL is user-configurable (see
 * `SettingsRepository`), so the repository composes the absolute URL per call and hands it to
 * Retrofit via `@Url` rather than relying on the build-time base URL.
 *
 * The Retrofit `Retrofit.Builder().baseUrl(...)` is still required for client construction but
 * its value is a sentinel — every request supplies an absolute URL that overrides it.
 *
 * Marked `internal` because Retrofit interfaces are an implementation detail of `:core:network`
 * — consumers see only repository interfaces in `:core:data`. Restricted-visibility on this
 * type is what keeps the layering from leaking.
 */
internal interface BackendApiService {
    /** PTV `GET /api/v3/search/{term}` — used by the search screen. */
    @GET
    suspend fun searchStops(
        @Url url: String,
    ): SearchResponseDto

    /**
     * PTV `GET /api/v3/stops/{stop_id}/route_type/{route_type}` — stop metadata enriched with
     * the routes that serve it. The data source composes the absolute URL (including the
     * `stop_location=true&stop_disruptions=true` query string) before calling.
     */
    @GET
    suspend fun getStop(
        @Url url: String,
    ): StopResponseDto

    /**
     * PTV `GET /api/v3/departures/route_type/{route_type}/stop/{stop_id}` — live departures for
     * a stop. The data source composes the absolute URL with the appropriate `expand=...`
     * (`Run,Direction,Route,Disruption`) query parameters before calling. Per the phase doc,
     * `expand=All` is overkill; the picked subset minimises payload while still letting the
     * mapper resolve route + direction names client-side.
     */
    @GET
    suspend fun getDepartures(
        @Url url: String,
    ): DeparturesResponseDto
}
