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
    /**
     * The bottom-nav surface (Favourites / Nearby / Search tabs). This is the app's root
     * destination and the only place the bottom [androidx.compose.material3.NavigationBar] lives.
     *
     * `focusLat` / `focusLon` mirror the Nearby focus mechanism (issue #123): when both are
     * non-null the scaffold opens on the Nearby tab framed on that coordinate. Stop-detail's
     * "show on map" action uses this to drop the user back onto the bottom-nav surface with the
     * map already centred on the stop, instead of pushing a standalone full-screen Nearby
     * destination that hid the nav bar (issue #154). Default null keeps cold-launch on the
     * Favourites tab and leaves every existing `AppNavKey.Home()` call site source-compatible.
     */
    @Serializable
    data class Home(
        val focusLat: Double? = null,
        val focusLon: Double? = null,
    ) : AppNavKey

    @Serializable
    data object Search : AppNavKey

    @Serializable
    data object Settings : AppNavKey

    /**
     * Favourites screen. Each row is a favourited `(stopId, destinationKey)` pair; tapping one
     * navigates to [StopDetail] with `focusDestinationKey` set so the matching destination block
     * is hoisted to the top. The destination itself takes no args.
     */
    @Serializable
    data object Favourites : AppNavKey

    // The Nearby map screen (issue #37) has no standalone destination key. It is only ever a tab
    // inside [Home]'s scaffold; the "show this stop on the map" affordance (issue #123) routes to
    // it via [Home]'s `focusLat`/`focusLon` so the bottom nav bar stays visible (issue #154).

    /**
     * Stop detail destination. Carries the `stopId` and `routeType` raw int values rather than the
     * domain types because Navigation 3 serialises keys via `kotlinx.serialization` and the value
     * classes (`@JvmInline value class StopId(val value: Int)`) don't have a serializer wired —
     * passing the ints keeps the surface trivially `Bundle`-able across process death. The
     * destination's composable lifts them back into [`ac.jfx.openptv.core.model.StopId`] /
     * [`ac.jfx.openptv.core.model.RouteType`] at the boundary.
     *
     * `focusDestinationKey` is optional — when non-null the matching destination block is hoisted
     * to the top of the grouped list (favourites tap-through, issue #137). When null, stop-detail
     * renders its existing full grouped list. Default null keeps every existing call site
     * source-compatible.
     */
    @Serializable
    data class StopDetail(
        val stopId: Int,
        val routeTypeCode: Int,
        val focusDestinationKey: String? = null,
    ) : AppNavKey

    /**
     * Run-pattern destination (issue #132): the stopping pattern of a single service run,
     * reached by tapping a departure row on stop-detail. Carries the PTV `run_ref` (an opaque
     * string — the only stable way to address a run) plus the raw `route_type` code, same
     * primitive-serialisation trade as [StopDetail].
     *
     * `fromStopId` is the stop the user tapped through from, so the pattern screen can highlight
     * "you are here" in the stop list. Nullable so future entry points that don't originate at a
     * stop (e.g. a disruption banner) can omit it.
     */
    @Serializable
    data class RunPattern(
        val runRef: String,
        val routeTypeCode: Int,
        val fromStopId: Int? = null,
    ) : AppNavKey
}
