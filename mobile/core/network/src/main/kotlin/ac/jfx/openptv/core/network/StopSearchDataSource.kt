package ac.jfx.openptv.core.network

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
 *
 * The backend base URL was previously a parameter on this function; it now lives behind
 * [BackendUrlProvider] which the network impl injects. That keeps URL composition fully inside
 * `:core:network` and means consumers in `:core:data` no longer touch URL strings.
 */
interface StopSearchDataSource {
    suspend fun searchStops(term: String): List<Stop>
}
