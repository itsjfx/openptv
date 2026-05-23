package ac.jfx.openptv.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Top-level navigation keys for the app. Each `data object` is a Navigation 3 destination key.
 * Lives in `:core:navigation` so feature modules can navigate to each other's destinations
 * without depending on each other — `:feature:search` declaring "open Settings" just imports
 * [AppNavKey.Settings], it never has to know `:feature:settings` exists.
 *
 * Marked `@Serializable` because Navigation 3 serialises the back stack to `SavedStateHandle`
 * across process death; the keys end up in `Bundle` form.
 */
sealed interface AppNavKey : NavKey {
    @Serializable
    data object Home : AppNavKey

    @Serializable
    data object Search : AppNavKey

    @Serializable
    data object Settings : AppNavKey

    /**
     * Favourites screen. Each row is a favourited `(stopId, routeId, directionId)` triple; tapping
     * one navigates to [StopDetail] with `focusRouteId` + `focusDirectionId` set so the screen
     * renders only the matching group. The destination itself takes no args — the user's
     * favourites are read off the repository at composition time.
     */
    @Serializable
    data object Favourites : AppNavKey

    /**
     * Nearby map screen (issue #37). MapLibre + OpenFreeMap tiles, user-location dot, clustered
     * stop pins.
     *
     * `focusLat` / `focusLon` are optional one-shot camera-focus hints (issue #123). When both are
     * non-null the screen centres the map on the coordinate at a street-level zoom the first time
     * the destination composes after the key changes — the "show this stop on the map" affordance
     * on stop-detail uses this to jump the user back to the map already framed on the stop they
     * were looking at. Default null preserves the "open Nearby on whatever camera the VM already
     * holds" behaviour for every existing call site (the bottom-nav tab, deep-linkless re-entry).
     *
     * `focusStopId` / `focusStopRouteTypeCode` are the optional companion to the lat/lon — when
     * set, the Nearby screen also auto-selects the stop (i.e. opens the same bottom-sheet preview
     * that a pin tap would) so the user lands with the stop already highlighted instead of just
     * the camera centred (#139 review). The pair is consumed by the same one-shot consumer that
     * applies the camera focus — both halves of the request travel together. Default null leaves
     * the select-on-arrive behaviour off so the bottom-nav tab is unaffected.
     *
     * Carrying the focus as primitive doubles + ints keeps the key trivially `Bundle`-able across
     * process death — same trade as [StopDetail]. The Nearby ViewModel converts these into the
     * domain [`ac.jfx.openptv.core.model.Coordinates`] / [`ac.jfx.openptv.core.model.StopId`] /
     * [`ac.jfx.openptv.core.model.RouteType`] at the boundary.
     */
    @Serializable
    data class Nearby(
        val focusLat: Double? = null,
        val focusLon: Double? = null,
        val focusStopId: Int? = null,
        val focusStopRouteTypeCode: Int? = null,
    ) : AppNavKey

    /**
     * Stop detail destination. Carries the `stopId` and `routeType` raw int values rather than the
     * domain types because Navigation 3 serialises keys via `kotlinx.serialization` and the value
     * classes (`@JvmInline value class StopId(val value: Int)`) don't have a serializer wired —
     * passing the ints keeps the surface trivially `Bundle`-able across process death. The
     * destination's composable lifts them back into [`ac.jfx.openptv.core.model.StopId`] /
     * [`ac.jfx.openptv.core.model.RouteType`] at the boundary.
     *
     * `focusRouteId` + `focusDirectionId` are optional — when both are non-null the stop-detail
     * screen renders only the matching `(routeId, directionId)` group (single-group filtered view
     * for the favourites tap-through, per issue #35). When either is null, stop-detail renders its
     * existing full grouped list. Default null keeps every existing call site source-compatible.
     */
    @Serializable
    data class StopDetail(
        val stopId: Int,
        val routeTypeCode: Int,
        val focusRouteId: Int? = null,
        val focusDirectionId: Int? = null,
    ) : AppNavKey
}
