package ac.jfx.openptv.core.network

import ac.jfx.openptv.core.model.RouteType
import ac.jfx.openptv.core.model.StopDetail
import ac.jfx.openptv.core.model.StopId

/**
 * Network-layer fetch for a single [StopDetail]. Public surface so `:core:data` can inject it;
 * the Retrofit-backed implementation and the underlying [BackendApiService] stay `internal` to
 * this module so DTOs never leak past the boundary.
 *
 * If a future phase swaps Retrofit for Ktor, only the impl behind this interface changes.
 */
interface StopDetailDataSource {
    suspend fun getStopDetail(
        stopId: StopId,
        routeType: RouteType,
    ): StopDetail?
}
