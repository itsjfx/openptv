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
}
