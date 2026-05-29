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
     * Favourites screen. Each row is a favourited `(stopId, destinationKey)` pair; tapping one
     * navigates to [StopDetail] with `focusDestinationKey` set so the matching destination block
     * is hoisted to the top. The destination itself takes no args.
     */
    @Serializable
    data object Favourites : AppNavKey

    /**
     * Nearby map screen (issue #37). MapLibre + OpenFreeMap tiles, user-location dot, clustered
     * stop pins. The destination itself takes no args — the user's last camera position is held in
     * the ViewModel's StateFlow across tab switches (no DataStore for v1; a "remember last view"
     * cross-launch is a follow-up).
     */
    @Serializable
    data object Nearby : AppNavKey

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
}
