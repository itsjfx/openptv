package ac.jfx.openptv.core.datastore.preference

import android.os.Build
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope

/**
 * Material You dynamic-colour opt-in. Stored as a typed two-state preference rather than a
 * bare `Boolean` so future expansion (e.g. an `Off, OnLight, OnDark, Custom` quartet for the
 * disruption-mute Phase 11 work) is a sealed-class addition rather than a wire-format break.
 *
 * Default policy: `On` on Android 12+ where wallpaper-derived dynamic colour actually exists,
 * `Off` below — falling back to the hand-tuned palette in `:core:designsystem`. The default is
 * computed eagerly (`Build.VERSION.SDK_INT`) rather than guarded at every read site so
 * consumers only need to honour `value` without re-deciding per-platform behaviour.
 */
sealed class DynamicColourPreference : Preference<Boolean>() {
    data object On : DynamicColourPreference() {
        override val value: Boolean = true
    }

    data object Off : DynamicColourPreference() {
        override val value: Boolean = false
    }

    override fun put(
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        persist(scope, dataStore, PreferenceKeys.DYNAMIC_COLOUR, if (value) "On" else "Off")
    }

    companion object {
        /**
         * `On` only when wallpaper-derived dynamic colour is supported by the platform
         * (Android 12+). On older devices the theme falls back to the static palette anyway,
         * so the default is `Off` to make the stored value match the rendered behaviour
         * — keeps the future settings screen honest when it shows the current preference.
         */
        val default: DynamicColourPreference =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) On else Off

        /**
         * Reconstitute from the stored wire value. Unknown / null falls back to [default], so
         * upgrading from a pre-12 device that wrote `"Off"` to a 12+ device that prefers `"On"`
         * does **not** silently flip the user's choice — only a completely absent value resolves
         * to the platform-aware default.
         */
        fun fromValue(stored: String?): DynamicColourPreference =
            when (stored) {
                "On" -> On
                "Off" -> Off
                else -> default
            }
    }
}

/**
 * Composition local for dynamic-colour preference. `OpenPtvTheme` will read this once the
 * theme module integrates with the local; for now the wiring is owned by `:app` so the theme
 * stays decoupled from `:core:datastore`.
 */
val LocalDynamicColour = compositionLocalOf { DynamicColourPreference.default }
