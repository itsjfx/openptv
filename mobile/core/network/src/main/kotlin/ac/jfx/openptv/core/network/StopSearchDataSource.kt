package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.Stop

/**
 * Network-layer search seam. Public because `:core:data` injects it, but the only thing exposed
 * is the mapped domain type — Retrofit DTOs stay `internal` to this module. That keeps the
 * dependency rule honest: `:core:data` (and anything downstream) never imports `:core:network`'s
 * wire types, only its public abstractions.
 *
 * The data source is the boundary between "I know about HTTP" and "I know about repositories":
 * if a future phase swaps Retrofit for Ktor, only the impl behind this interface changes.
 *
 * - [term] is the raw query string from the user. The data source URL-encodes it.
 * - [routeTypes] forwards to PTV's `route_types` query parameter (repeated once per requested
 *   mode, same convention as [NearbyStopsDataSource]). An empty set means "all modes" — the
 *   parameter is omitted and PTV returns every mode.
 *
 * The absolute URL was previously composed from a `baseUrl` parameter; it now lives behind
 * [PtvUrlResolver] which the network impl injects. That keeps URL composition fully inside
 * `:core:network` and means consumers in `:core:data` no longer touch URL strings.
 */
interface StopSearchDataSource {
    suspend fun searchStops(
        term: String,
        routeTypes: Set<RouteType> = emptySet(),
    ): List<Stop>
}
